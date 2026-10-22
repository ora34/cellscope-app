package app.cellscope

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cz.mroczis.netmonster.core.factory.NetMonsterFactory
import cz.mroczis.netmonster.core.model.cell.CellGsm
import cz.mroczis.netmonster.core.model.cell.CellLte
import cz.mroczis.netmonster.core.model.cell.CellNr
import cz.mroczis.netmonster.core.model.cell.CellTdscdma
import cz.mroczis.netmonster.core.model.cell.CellWcdma
import cz.mroczis.netmonster.core.model.cell.ICell
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

// =====================================================================
// Модели
// =====================================================================

/** Идентификатор соты в терминах OpenCelliD. */
data class Ident(val radio: String, val mcc: Int, val mnc: Int, val area: Int?, val cell: Long)

data class CellRow(
    val tech: String,
    val serving: Boolean,
    val operator: String?,
    val band: String?,
    val channel: Int?,
    val pci: Int?,
    val dbm: Int?,
    val details: List<Pair<String, String>>,
    val ident: Ident? = null,
)

data class CellSnapshot(val time: Long, val cells: List<CellRow>) {
    val serving: CellRow? get() = cells.firstOrNull { it.serving }
}

/** Одна сота из базы OpenCelliD. */
data class Tower(
    val radio: String, val mcc: Int, val mnc: Int, val area: Int, val cell: Long,
    val lat: Double, val lon: Double, val range: Int,
) {
    val key: String get() = "$radio|$mcc|$mnc|$cell"
}

/**
 * Базовая станция (для LTE это все сектора одной eNB).
 * own = true: положение оценено по измерениям самого телефона, а не взято из OpenCelliD.
 */
data class Station(
    val key: String, val radio: String, val mcc: Int, val mnc: Int, val id: Long,
    val lat: Double, val lon: Double, val sectors: List<Int>, val range: Int,
    val own: Boolean = false, val samples: Int = 0,
)

fun stationKey(i: Ident): String =
    if (i.radio == "LTE") "${i.radio}|${i.mcc}|${i.mnc}|${i.cell shr 8}"
    else "${i.radio}|${i.mcc}|${i.mnc}|${i.cell}"

fun buildStations(towers: List<Tower>): List<Station> {
    val groups = towers.groupBy { t ->
        if (t.radio == "LTE") "${t.radio}|${t.mcc}|${t.mnc}|${t.cell shr 8}" else t.key
    }
    return groups.map { (k, list) ->
        val f = list.first()
        val lte = f.radio == "LTE"
        Station(
            key = k, radio = f.radio, mcc = f.mcc, mnc = f.mnc,
            id = if (lte) f.cell shr 8 else f.cell,
            lat = list.map { it.lat }.average(),
            lon = list.map { it.lon }.average(),
            sectors = if (lte) list.map { (it.cell and 0xFFL).toInt() }.sorted() else emptyList(),
            range = list.maxOf { it.range },
        )
    }
}

fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(sqrt(a))
}

fun fmtDist(m: Double): String =
    if (m < 1000) "${m.roundToInt()} м" else String.format(Locale.getDefault(), "%.1f км", m / 1000)

fun operatorName(mcc: Int, mnc: Int): String = when ("$mcc-$mnc") {
    "250-1" -> "МТС"
    "250-2" -> "МегаФон"
    "250-99" -> "Билайн"
    "250-20" -> "Tele2"
    "250-11" -> "Yota"
    else -> "$mcc $mnc"
}

private fun Any?.s(): String = this?.toString() ?: "—"
private fun Number?.dbm(): String = this?.let { "${it.toInt()} дБм" } ?: "—"
private fun Number?.l(): Long? = this?.toLong()
private fun Number?.i(): Int? = this?.toInt()

// =====================================================================
// Чтение сот через netmonster-core
// =====================================================================

class CellRepository(context: Context) {
    private val netMonster = NetMonsterFactory.get(context.applicationContext)

    fun read(): CellSnapshot {
        val cells = try {
            netMonster.getCells().map { it.toRow() }
        } catch (e: SecurityException) {
            emptyList()
        }
        val sorted = cells.sortedWith(
            compareByDescending<CellRow> { it.serving }.thenByDescending { it.dbm ?: Int.MIN_VALUE }
        )
        return CellSnapshot(System.currentTimeMillis(), sorted)
    }

    private fun ICell.makeIdent(radio: String, area: Int?, cell: Long?): Ident? {
        val n = network ?: return null
        val mcc = "${n.mcc}".toIntOrNull() ?: return null
        val mnc = "${n.mnc}".toIntOrNull() ?: return null
        if (cell == null) return null
        return Ident(radio, mcc, mnc, area, cell)
    }

    private fun ICell.toRow(): CellRow {
        val isServing = connectionStatus is PrimaryConnection
        val plmn = network?.let { "${it.mcc} ${it.mnc}" }
        val bandName = band?.name
        val channel = band?.channelNumber

        return when (this) {
            is CellGsm -> CellRow(
                "2G", isServing, plmn, bandName, channel, bsic, signal.rssi,
                listOf(
                    "CID" to cid.s(), "LAC" to lac.s(), "BSIC" to bsic.s(),
                    "ARFCN" to channel.s(), "RSSI" to signal.rssi.dbm(),
                ),
                makeIdent("GSM", lac.i(), cid.l()),
            )
            is CellWcdma -> CellRow(
                "3G", isServing, plmn, bandName, channel, psc, signal.rscp ?: signal.rssi,
                listOf(
                    "CI" to ci.s(), "CID" to cid.s(), "RNC" to rnc.s(), "LAC" to lac.s(),
                    "PSC" to psc.s(), "UARFCN" to channel.s(),
                    "RSSI" to signal.rssi.dbm(), "RSCP" to signal.rscp.dbm(),
                    "Ec/No" to (signal.ecno?.let { "$it дБ" } ?: "—"),
                ),
                makeIdent("UMTS", lac.i(), ci.l()),
            )
            is CellTdscdma -> CellRow(
                "3G", isServing, plmn, bandName, channel, cpid, signal.rscp,
                listOf(
                    "CI" to ci.s(), "LAC" to lac.s(), "CPID" to cpid.s(),
                    "RSCP" to signal.rscp.dbm(),
                ),
            )
            is CellLte -> CellRow(
                "4G", isServing, plmn, bandName, channel, pci, signal.rsrp?.toInt(),
                listOf(
                    "ECI" to eci.s(), "eNB" to eci?.let { it shr 8 }.s(),
                    "CID" to cid.s(), "TAC" to tac.s(),
                    "PCI" to pci.s(), "EARFCN" to channel.s(),
                    "RSRP" to signal.rsrp.dbm(),
                    "RSRQ" to (signal.rsrq?.let { "$it дБ" } ?: "—"),
                    "SNR" to (signal.snr?.let { "$it дБ" } ?: "—"),
                ),
                makeIdent("LTE", tac.i(), eci.l()),
            )
            is CellNr -> CellRow(
                "5G", isServing, plmn, bandName, channel, pci, signal.ssRsrp,
                listOf(
                    "NCI" to nci.s(), "TAC" to tac.s(), "PCI" to pci.s(),
                    "NRARFCN" to channel.s(), "SS-RSRP" to signal.ssRsrp.dbm(),
                    "SS-RSRQ" to (signal.ssRsrq?.let { "$it дБ" } ?: "—"),
                    "SS-SINR" to (signal.ssSinr?.let { "$it дБ" } ?: "—"),
                ),
                makeIdent("NR", tac.i(), nci.l()),
            )
            else -> CellRow("?", isServing, plmn, bandName, channel, null, null, emptyList())
        }
    }
}

// =====================================================================
// Координаты телефона
// =====================================================================

class LocationTracker(ctx: Context) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> get() = _location

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _location.value = location
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
                if (lm.isProviderEnabled(p)) {
                    if (_location.value == null) lm.getLastKnownLocation(p)?.let { _location.value = it }
                    lm.requestLocationUpdates(p, 3000L, 5f, listener, Looper.getMainLooper())
                }
            }
        } catch (e: SecurityException) {
            // разрешение ещё не выдано
        } catch (e: Exception) {
            // провайдер недоступен
        }
    }

    fun stop() {
        try {
            lm.removeUpdates(listener)
        } catch (e: Exception) {
        }
    }
}

// =====================================================================
// Хранилище: кэш вышек, свои измерения, диапазоны, ключ, счётчик запросов
// =====================================================================

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

    /** Запоминаем, на каких диапазонах телефон видел секторы станции. Вернёт true, если запись новая. */
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

    // ---------- Свои измерения ----------

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

// =====================================================================
// OpenCelliD
// =====================================================================

class ApiError(val code: Int, msg: String) : IOException(msg)

object OpenCellId {
    private const val BASE = "https://opencellid.org"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent
