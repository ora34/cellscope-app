package app.cellscope

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- Данные ----------

data class CellRow(
    val tech: String,
    val serving: Boolean,
    val operator: String?,
    val band: String?,
    val channel: Int?,
    val pci: Int?,
    val dbm: Int?,
    val details: List<Pair<String, String>>,
)

data class CellSnapshot(val time: Long, val cells: List<CellRow>) {
    val serving: CellRow? get() = cells.firstOrNull { it.serving }
}

private fun Any?.s(): String = this?.toString() ?: "—"
private fun Int?.dbm(): String = this?.let { "$it дБм" } ?: "—"

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
            )
            is CellWcdma -> CellRow(
                "3G", isServing, plmn, bandName, channel, psc, signal.rscp ?: signal.rssi,
                listOf(
                    "CI" to ci.s(), "CID" to cid.s(), "RNC" to rnc.s(), "LAC" to lac.s(),
                    "PSC" to psc.s(), "UARFCN" to channel.s(),
                    "RSSI" to signal.rssi.dbm(), "RSCP" to signal.rscp.dbm(),
                    "Ec/No" to (signal.ecno?.let { "$it дБ" } ?: "—"),
                ),
            )
            is CellTdscdma -> CellRow(
                "3G", isServing, plmn, bandName, channel, cpid, signal.rscp,
                listOf(
                    "CI" to ci.s(), "LAC" to lac.s(), "CPID" to cpid.s(),
                    "RSCP" to signal.rscp.dbm(),
                ),
            )
            is CellLte -> CellRow(
                "4G", isServing, plmn, bandName, channel, pci, signal.rsrp,
                listOf(
                    "ECI" to eci.s(), "eNB" to eNb.s(), "CID" to cid.s(), "TAC" to tac.s(),
                    "PCI" to pci.s(), "EARFCN" to channel.s(),
                    "RSRP" to signal.rsrp.dbm(),
                    "RSRQ" to (signal.rsrq?.let { "$it дБ" } ?: "—"),
                    "SNR" to (signal.snr?.let { "$it дБ" } ?: "—"),
                ),
            )
            is CellNr -> CellRow(
                "5G", isServing, plmn, bandName, channel, pci, signal.ssRsrp,
                listOf(
                    "NCI" to nci.s(), "TAC" to tac.s(), "PCI" to pci.s(),
                    "NRARFCN" to channel.s(), "SS-RSRP" to signal.ssRsrp.dbm(),
                    "SS-RSRQ" to (signal.ssRsrq?.let { "$it дБ" } ?: "—"),
                    "SS-SINR" to (signal.ssSinr?.let { "$it дБ" } ?: "—"),
                ),
            )
            else -> CellRow("?", isServing, plmn, bandName, channel, null, null, emptyList())
        }
    }
}

class CellViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CellRepository(app)

    private val _snapshot = MutableStateFlow<CellSnapshot?>(null)
    val snapshot: StateFlow<CellSnapshot?> = _snapshot

    private val _history = MutableStateFlow<List<CellSnapshot>>(emptyList())
    val history: StateFlow<List<CellSnapshot>> = _history

    private val _journal = MutableStateFlow<List<CellSnapshot>>(emptyList())
    val journal: StateFlow<List<CellSnapshot>> = _journal

    private var job: Job? = null

    fun start() {
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
                delay(2000)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}

// ---------- Интерфейс ----------

private val Green = Color(0xFF0FA968)
private val GreenLight = Color(0xFFE0F3EA)

class MainActivity : ComponentActivity() {
    private val vm: CellViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green)) {
                Root(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onStop() {
        super.onStop()
        vm.stop()
    }
}

private enum class Tab(val title: String, val icon: ImageVector) {
    Charts("Графики", Icons.Default.ShowChart),
    Map("Карта", Icons.Default.Map),
    Online("Онлайн", Icons.Default.CellTower),
    Journal("Журнал", Icons.Default.ListAlt),
    Menu("Меню", Icons.Default.Menu),
}

@Composable
private fun Root(vm: CellViewModel) {
    val context = LocalContext.current
    val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
    var granted by remember {
        mutableStateOf(perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.all { it }
        if (granted) vm.start()
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(perms)
    }

    var tab by remember { mutableStateOf(Tab.Online) }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                Tab.values().forEach {
                    NavigationBarItem(
                        selected = tab == it,
                        onClick = { tab = it },
                        icon = { Icon(it.icon, null) },
                        label = { Text(it.title, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = GreenLight),
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Color(0xFFF6F8F6))) {
            if (!granted) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Нужны разрешения", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Для чтения данных сотовой сети Android требует доступ к местоположению и состоянию телефона.")
                    Button(onClick = { launcher.launch(perms) }) { Text("Разрешить") }
                }
            } else when (tab) {
                Tab.Online -> OnlineScreen(vm)
                Tab.Charts -> ChartsScreen(vm)
                Tab.Map -> Placeholder("Карта", "Здесь будет карта вышек.")
                Tab.Journal -> JournalScreen(vm)
                Tab.Menu -> Placeholder("Меню", "Настройки, экспорт журнала, о приложении.")
            }
        }
    }
}

@Composable
private fun OnlineScreen(vm: CellViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val s = snap
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Green)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            val srv = s.serving
            Column(Modifier.background(Color.White).padding(16.dp)) {
                if (srv == null) {
                    Text("Нет основной соты", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Оператор", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(srv.operator ?: "", fontSize = 20.sp, color = Color.Gray)
                    }
                    Text("${srv.tech} | ${srv.band ?: "—"}", fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    srv.details.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth()) {
                            pair.forEach { (k, v) ->
                                Row(Modifier.weight(1f)) {
                                    Text(k, Modifier.width(84.dp), fontSize = 17.sp)
                                    Text(v, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
        }
        items(s.cells.filter { !it.serving }) { c -> NeighbourRow(c) }
    }
}

@Composable
private fun NeighbourRow(c: CellRow) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .border(BorderStroke(1.dp, Green), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text(c.band ?: c.tech, color = Green, fontSize = 15.sp) }
            Spacer(Modifier.width(10.dp))
            Text(c.pci?.toString() ?: "", fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            c.channel?.let { Text(it.toString(), fontSize = 15.sp, color = Color.DarkGray) }
            Spacer(Modifier.width(10.dp))
            val d = c.dbm
            if (d != null) {
                Text(d.toString(), fontSize = 20.sp)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(10.dp).background(signalColor(d), RoundedCornerShape(50)))
            } else {
                Text(c.tech, fontSize = 20.sp)
            }
        }
        HorizontalDivider(color = Color(0xFFE2E2E2))
    }
}

private fun signalColor(dbm: Int): Color = when {
    dbm >= -90 -> Color(0xFF2EB872)
    dbm >= -100 -> Color(0xFFB6D35A)
    dbm >= -105 -> Color(0xFFF0D54A)
    dbm >= -110 -> Color(0xFFF5A35A)
    dbm >= -115 -> Color(0xFFF47A5A)
    else -> Color(0xFFF0507A)
}

@Composable
private fun ChartsScreen(vm: CellViewModel) {
    val hist by vm.history.collectAsStateWithLifecycle()
    val values = hist.mapNotNull { it.serving?.dbm }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Уровень основной соты, дБм", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (values.size < 2) {
            Text("Копим данные…", color = Color.Gray)
            return@Column
        }
        Text("Сейчас: ${values.last()} дБм   (мин ${values.min()}, макс ${values.max()})")
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier.fillMaxWidth().height(240.dp)
                .background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)
        ) {
            val lo = (values.min() - 3).toFloat()
            val hi = (values.max() + 3).toFloat()
            val stepX = size.width / (values.size - 1)
            fun y(v: Int) = size.height - (v - lo) / (hi - lo) * size.height
            for (i in 1 until values.size) {
                drawLine(
                    Green,
                    Offset((i - 1) * stepX, y(values[i - 1])),
                    Offset(i * stepX, y(values[i])),
                    strokeWidth = 5f, cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun JournalScreen(vm: CellViewModel) {
    val journal by vm.journal.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    if (journal.isEmpty()) {
        Placeholder("Журнал", "Пока пусто. Записи появляются при каждой смене основной соты.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(journal.reversed()) { s ->
            val c = s.serving
            if (c != null) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "${fmt.format(Date(s.time))}  ${c.tech} ${c.band ?: ""}",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        c.details.joinToString("  ") { "${it.first} ${it.second}" },
                        fontSize = 13.sp, color = Color.DarkGray,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Placeholder(title: String, text: String) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Color.DarkGray)
    }
}
