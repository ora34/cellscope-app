package app.cellscope

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CellViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CellRepository(app)
    private val tracker = LocationTracker(app)
    private val store = Storage(app)

    private val _snapshot = MutableStateFlow<CellSnapshot?>(null)
    val snapshot: StateFlow<CellSnapshot?> = _snapshot

    private val _history = MutableStateFlow<List<CellSnapshot>>(emptyList())
    val history: StateFlow<List<CellSnapshot>> = _history

    private val _journal = MutableStateFlow<List<CellSnapshot>>(emptyList())
    val journal: StateFlow<List<CellSnapshot>> = _journal

    val location: StateFlow<Location?> = tracker.location

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations

    val status = MutableStateFlow("")
    val apiKey = MutableStateFlow(store.apiKey)
    val credits = MutableStateFlow(store.credits())
    val bandsVersion = MutableStateFlow(0)
    val selectedKey = MutableStateFlow<String?>(null)

    @Volatile private var loading = false
    @Volatile private var nextTryAt = 0L
    @Volatile private var lastAutoTile = ""
    private val resolved = HashSet<String>()
    private var job: Job? = null
    private var tick = 0

    fun start() {
        tracker.start()
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            while (isActive) {
                val s = withContext(Dispatchers.IO) { repo.read() }
                _snapshot.value = s
                _history.value = (_history.value + s).takeLast(120)
                val last = _journal.value.lastOrNull()?.serving
                val cur = s.serving
                if (cur != null && (last == null || last.pci != cur.pci ||
                            last.channel != cur.channel || last.tech != cur.tech)
                ) {
                    _journal.value = _journal.value + s
                }

                var changed = false
                s.cells.forEach { c ->
                    val id = c.ident
                    if (id != null && store.addBand(id, c.band, c.channel)) changed = true
                }
                if (changed) bandsVersion.value = bandsVersion.value + 1

                // Собственные измерения: точка + уровень сигнала основной соты
                val sid = cur?.ident
                val sdbm = cur?.dbm
                val loc = location.value
                if (sid != null && sdbm != null && loc != null &&
                    loc.accuracy <= 80f && System.currentTimeMillis() - loc.time < 20000
                ) {
                    store.addObs(stationKey(sid), loc.latitude, loc.longitude, sdbm)
                }
                tick++
                if (tick % 15 == 0) store.flushObs()

                autoLoad(s)
                delay(2000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        tracker.stop()
        store.flushObs()
    }

    fun select(key: String?) {
        selectedKey.value = key
    }

    fun setKey(k: String) {
        store.apiKey = k
        apiKey.value = store.apiKey
        lastAutoTile = ""
        nextTryAt = 0L
        resolved.clear()
        status.value = if (store.apiKey.isBlank()) "Ключ удалён" else "Ключ сохранён"
    }

    fun clearOwn() {
        store.clearObs()
        publish()
        status.value = "Свои измерения очищены"
    }

    fun bandsFor(mcc: Int, mnc: Int, enb: Long): List<String> = store.bandsFor(mcc, mnc, enb)

    /** Вышки OpenCelliD + оценки по своим измерениям (если для станции нет данных OpenCelliD). */
    private fun publish() {
        val loc = location.value ?: return
        val open = buildStations(store.towersNear(loc.latitude, loc.longitude, 0.06, 0.12))
        val have = open.map { it.key }.toSet()
        val own = ArrayList<Station>()
        for ((k, e) in store.ownEstimates()) {
            if (k in have) continue
            if (abs(e[0] - loc.latitude) > 0.06 || abs(e[1] - loc.longitude) > 0.12) continue
            val parts = k.split("|")
            if (parts.size < 4) continue
            val mcc = parts[1].toIntOrNull() ?: continue
            val mnc = parts[2].toIntOrNull() ?: continue
            val id = parts[3].toLongOrNull() ?: continue
            val sectors = if (parts[0] == "LTE")
                store.bandsFor(mcc, mnc, id).mapNotNull { it.substringBefore(";").toIntOrNull() }.distinct().sorted()
            else emptyList()
            own.add(
                Station(
                    k, parts[0], mcc, mnc, id, e[0], e[1], sectors,
                    e[2].toInt(), own = true, samples = e[3].toInt(),
                )
            )
        }
        _stations.value = open + own
    }

    private fun autoLoad(s: CellSnapshot) {
        publish()
        val srv = s.serving?.ident ?: return
        val loc = location.value ?: return
        if (apiKey.value.isBlank()) {
            if (status.value.isBlank()) {
                status.value = "Ключ OpenCelliD не задан: карта строится по вашим измерениям"
            }
            return
        }
        if (System.currentTimeMillis() < nextTryAt) return
        val sk = stationKey(srv)
        val tile = store.tileKey(loc.latitude, loc.longitude, srv.mcc, srv.mnc)
        if (tile != lastAutoTile) {
            lastAutoTile = tile
            load(more = false, force = false)
        } else if (_stations.value.none { it.key == sk && !it.own } && !resolved.contains(sk)) {
            load(more = false, force = false)
        }
    }

    /** more = догрузить следующие 50 сот, force = обновить участок заново. */
    fun load(more: Boolean, force: Boolean) {
        if (loading) return
        val key = apiKey.value
        val loc = location.value
        val srv = snapshot.value?.serving?.ident
        if (key.isBlank()) {
            status.value = "Нужен ключ OpenCelliD: Меню → ключ"
            return
        }
        if (loc == null) {
            status.value = "Жду координаты телефона (включите геолокацию)…"
            return
        }
        loading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tile = store.tileKey(loc.latitude, loc.longitude, srv?.mcc, srv?.mnc)
                val loaded = store.tileLoaded(tile)
                if (loaded == null || more || force) {
                    if (store.credits() >= 950) {
                        status.value = "Дневной лимит OpenCelliD почти исчерпан"
                    } else {
                        val offset = if (more) (loaded ?: 0) else 0
                        val list = OpenCellId.inArea(
                            key, store.box(loc.latitude, loc.longitude), srv?.mcc, srv?.mnc, offset
                        )
                        store.addTowers(list)
                        store.setTile(tile, offset + list.size)
                        store.addCredits(max(list.size, 1))
                        status.value = "Загружено сот: ${list.size}"
                    }
                }
                publish()
                if (srv != null) {
                    val sk = stationKey(srv)
                    if (_stations.value.none { it.key == sk && !it.own } && resolved.add(sk)) {
                        resolveServing(key, srv)
                    }
                }
            } catch (e: Exception) {
                status.value = "OpenCelliD: ${e.message}. Показываю свои данные"
                nextTryAt = System.currentTimeMillis() + 300_000L
                lastAutoTile = ""
            } finally {
                loading = false
                credits.value = store.credits()
            }
        }
    }

    private fun resolveServing(key: String, i: Ident) {
        if (store.credits() >= 990) return
        try {
            val t = OpenCellId.cell(key, i)
            store.addCredits(1)
            if (t != null) {
                store.addTowers(listOf(t))
                publish()
            } else {
                status.value = "Этой БС нет в базе OpenCelliD"
            }
        } catch (e: Exception) {
            status.value = "БС: ${e.message}"
            nextTryAt = System.currentTimeMillis() + 300_000L
        }
    }
}

// END
