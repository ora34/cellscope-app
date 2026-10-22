package app.cellscope

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import cz.mroczis.netmonster.core.factory.NetMonsterFactory
import cz.mroczis.netmonster.core.model.cell.CellGsm
import cz.mroczis.netmonster.core.model.cell.CellLte
import cz.mroczis.netmonster.core.model.cell.CellNr
import cz.mroczis.netmonster.core.model.cell.CellTdscdma
import cz.mroczis.netmonster.core.model.cell.CellWcdma
import cz.mroczis.netmonster.core.model.cell.ICell
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private fun Any?.s(): String = this?.toString() ?: "—"
private fun Number?.dbm(): String = this?.let { "${it.toInt()} дБм" } ?: "—"
private fun Number?.l(): Long? = this?.toLong()
private fun Number?.i(): Int? = this?.toInt()

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

/** Координаты телефона (GPS и сеть). */
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

// END
