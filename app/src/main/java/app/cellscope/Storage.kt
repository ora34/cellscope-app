package app.cellscope

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/** Хранилище: кэш вышек, свои измерения, диапазоны, ключ, счётчик запросов. */
class Storage(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("cellscope", Context.MODE_PRIVATE)
    private val file = File(ctx.filesDir, "towers.json")
    private val obsFile = File(ctx.filesDir, "obs.json")
    private val towers = LinkedHashMap<String, Tower>()
    private val tiles = HashMap<String, Int>()
    private val bands = HashMap<String, MutableSet<String>>()

    /** Свои измерения: ключ станции -> список [широта, долгота, уровень дБм]. */
    private val obs = HashMap<String, MutableList<DoubleArray>>()
    private var obsDirty = false

    init {
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (n in 0 until arr.length()) {
                    val o = arr.getJSONObject(n)
                    val t = Tower(
                        o.getString("r"), o.getInt("mcc"), o.getInt("mnc"), o.getInt("a"),
                        o.getLong("c"), o.getDouble("la"), o.getDouble("lo"), o.getInt("rg"),
                    )
                    towers[t.key] = t
                }
            }
        } catch (e: Exception) {
        }
        try {
            val o = JSONObject(prefs.getString("tiles", "{}") ?: "{}")
            o.keys().forEach { k -> tiles[k] = o.getInt(k) }
        } catch (e: Exception) {
        }
        try {
            val o = JSONObject(prefs.getString("bands", "{}") ?: "{}")
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                val set = HashSet<String>()
                for (n in 0 until a.length()) set.add(a.getString(n))
                bands[k] = set
            }
        } catch (e: Exception) {
        }
        try {
            if (obsFile.exists()) {
                val o = JSONObject(obsFile.readText())
                o.keys().forEach { k ->
                    val a = o.getJSONArray(k)
                    val list = ArrayList<DoubleArray>()
                    for (n in 0 until a.length()) {
                        val p = a.getJSONArray(n)
                        list.add(doubleArrayOf(p.getDouble(0), p.getDouble(1), p.getDouble(2)))
                    }
                    obs[k] = list
                }
            }
        } catch (e: Exception) {
        }
    }

    var apiKey: String
        get() = prefs.getString("key", "") ?: ""
        set(v) {
            prefs.edit().putString("key", v.trim()).apply()
        }

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    @Synchronized
    fun credits(): Int = if (prefs.getString("cday", "") == today()) prefs.getInt("cn", 0) else 0

    @Synchronized
    fun addCredits(n: Int) {
        prefs.edit().putString("cday", today()).putInt("cn", credits() + n).apply()
    }

    @Synchronized
    fun addTowers(list: List<Tower>) {
        list.forEach { towers[it.key] = it }
        val arr = JSONArray()
        towers.values.forEach { t ->
            arr.put(
                JSONObject().put("r", t.radio).put("mcc", t.mcc).put("mnc", t.mnc)
                    .put("a", t.area).put("c", t.cell).put("la", t.lat).put("lo", t.lon)
                    .put("rg", t.range)
            )
        }
        try {
            file.writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun towersNear(lat: Double, lon: Double, dLat: Double, dLon: Double): List<Tower> =
        towers.values.filter { abs(it.lat - lat) <= dLat && abs(it.lon - lon) <= dLon }

    fun tileKey(lat: Double, lon: Double, mcc: Int?, mnc: Int?): String =
        "${mcc ?: 0}:${mnc ?: 0}:${floor(lat / 0.02).toInt()}:${floor(lon / 0.04).toInt()}"

    /** latMin, lonMin, latMax, lonMax */
    fun box(lat: Double, lon: Double): DoubleArray {
        val la = floor(lat / 0.02) * 0.02
        val lo = floor(lon / 0.04) * 0.04
        return doubleArrayOf(la, lo, la + 0.02, lo + 0.04)
    }

    @Synchronized
    fun tileLoaded(key: String): Int? = tiles[key]

    @Synchronized
    fun setTile(key: String, count: Int) {
        tiles[key] = count
        val o = JSONObject()
        for ((k, v) in tiles) o.put(k, v)
        prefs.edit().putString("tiles", o.toString()).apply()
    }

    /** Запоминаем, на каких диапазонах телефон видел секторы станции. true, если запись новая. */
    @Synchronized
    fun addBand(i: Ident, band: String?, channel: Int?): Boolean {
        if (i.radio != "LTE" || (band == null && channel == null)) return false
        val k = "${i.mcc}|${i.mnc}|${i.cell shr 8}"
        val v = "${i.cell and 0xFFL};${band ?: ""};${channel ?: ""}"
        val set = bands.getOrPut(k) { HashSet() }
        if (!set.add(v)) return false
        val o = JSONObject()
        for ((key, s) in bands) o.put(key, JSONArray(s.toList()))
        prefs.edit().putString("bands", o.toString()).apply()
        return true
    }

    @Synchronized
    fun bandsFor(mcc: Int, mnc: Int, enb: Long): List<String> =
        (bands["$mcc|$mnc|$enb"] ?: emptySet<String>())
            .sortedBy { it.substringBefore(";").toIntOrNull() ?: 0 }

    @Synchronized
    fun addObs(key: String, lat: Double, lon: Double, dbm: Int) {
        val list = obs.getOrPut(key) { ArrayList() }
        val near = list.indexOfFirst { distanceM(it[0], it[1], lat, lon) < 25.0 }
        if (near >= 0) {
            if (dbm > list[near][2]) {
                list[near] = doubleArrayOf(lat, lon, dbm.toDouble())
                obsDirty = true
            }
            return
        }
        list.add(doubleArrayOf(lat, lon, dbm.toDouble()))
        if (list.size > 60) {
            var worst = 0
            for (n in list.indices) if (list[n][2] < list[worst][2]) worst = n
            list.removeAt(worst)
        }
        obsDirty = true
    }

    @Synchronized
    fun flushObs() {
        if (!obsDirty) return
        obsDirty = false
        try {
            val o = JSONObject()
            for ((k, list) in obs) {
                val a = JSONArray()
                list.forEach { p -> a.put(JSONArray().put(p[0]).put(p[1]).put(p[2])) }
                o.put(k, a)
            }
            obsFile.writeText(o.toString())
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun clearObs() {
        obs.clear()
        obsDirty = false
        try {
            obsFile.delete()
        } catch (e: Exception) {
        }
    }

    /** Ключ станции -> [широта, долгота, погрешность м, число точек]. Нужно минимум 4 точки. */
    @Synchronized
    fun ownEstimates(): Map<String, DoubleArray> {
        val res = HashMap<String, DoubleArray>()
        for ((k, list) in obs) {
            if (list.size < 4) continue
            val top = list.sortedByDescending { it[2] }.take(10)
            var sw = 0.0
            var la = 0.0
            var lo = 0.0
            for (o in top) {
                val base = max(1.0, o[2] + 140.0)
                val w = base * base
                sw += w
                la += o[0] * w
                lo += o[1] * w
            }
            val cLat = la / sw
            val cLon = lo / sw
            val err = top.map { distanceM(cLat, cLon, it[0], it[1]) }.average()
            res[k] = doubleArrayOf(cLat, cLon, max(50.0, err), list.size.toDouble())
        }
        return res
    }
}

class ApiError(val code: Int, msg: String) : IOException(msg)

/** Клиент открытой базы OpenCelliD. */
object OpenCellId {
    private const val BASE = "https://opencellid.org"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw ApiError(
                    code,
                    when (code) {
                        429 -> "дневной лимит запросов исчерпан"
                        403 -> "ключ не принят (403)"
                        404 -> "не найдено (404)"
                        502, 503, 504, 521, 522, 523, 524 -> "сервер OpenCelliD недоступен ($code)"
                        else -> "HTTP $code"
                    }
                )
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun parse(o: JSONObject): Tower? = try {
        val cell = if (o.has("cellid")) o.getLong("cellid") else o.getLong("cell")
        Tower(
            o.getString("radio"), o.getInt("mcc"), o.getInt("mnc"),
            o.optInt("lac", o.optInt("tac", 0)), cell,
            o.getDouble("lat"), o.getDouble("lon"), o.optInt("range", 0),
        )
    } catch (e: Exception) {
        null
    }

    fun inArea(key: String, box: DoubleArray, mcc: Int?, mnc: Int?, offset: Int): List<Tower> {
        val sb = StringBuilder("$BASE/cell/getInArea?key=${URLEncoder.encode(key, "UTF-8")}")
        sb.append("&BBOX=${box[0]},${box[1]},${box[2]},${box[3]}")
        if (mcc != null) sb.append("&mcc=$mcc")
        if (mnc != null) sb.append("&mnc=$mnc")
        sb.append("&limit=50&offset=$offset&format=json")
        val o = JSONObject(get(sb.toString()))
        val arr = o.optJSONArray("cells") ?: throw IOException(o.optString("error", "пустой ответ"))
        return (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it)) }
    }

    fun cell(key: String, i: Ident): Tower? {
        val url = "$BASE/cell/get?key=${URLEncoder.encode(key, "UTF-8")}" +
                "&mcc=${i.mcc}&mnc=${i.mnc}&lac=${i.area ?: 0}&cellid=${i.cell}" +
                "&radio=${i.radio}&format=json"
        return try {
            parse(JSONObject(get(url)))
        } catch (e: ApiError) {
            if (e.code == 404) null else throw e
        }
    }
}

// END
