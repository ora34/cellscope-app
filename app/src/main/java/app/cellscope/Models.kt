package app.cellscope

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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
 * own = true: положение оценено по измерениям телефона, а не взято из OpenCelliD.
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

// END
