package app.cellscope

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import org.osmdroid.config.Configuration

internal val Green = Color(0xFF0FA968)
internal val GreenLight = Color(0xFFE0F3EA)

class MainActivity : ComponentActivity() {
    private val vm: CellViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
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
    Online("Монитор", Icons.Default.CellTower),
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
                Tab.Online -> MonitorScreen(vm)
                Tab.Charts -> ChartsScreen(vm)
                Tab.Journal -> JournalScreen(vm)
                Tab.Menu -> MenuScreen(vm)
            }
        }
    }
}

@Composable
private fun MonitorScreen(vm: CellViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val stations by vm.stations.collectAsStateWithLifecycle()
    val loc by vm.location.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val credits by vm.credits.collectAsStateWithLifecycle()
    val selKey by vm.selectedKey.collectAsStateWithLifecycle()
    val bandsVer by vm.bandsVersion.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TowerMap(vm, Modifier.fillMaxWidth().weight(0.42f))
        Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                "$status  ·  запросов сегодня: $credits/1000",
                fontSize = 11.sp, color = Color.DarkGray,
            )
            Text(
                "Зелёная: ваша БС · красные: OpenCelliD · фиолетовые (~): оценка по вашим измерениям",
                fontSize = 10.sp, color = Color.Gray,
            )
        }
        val s = snap
        if (s == null) {
            Box(Modifier.fillMaxWidth().weight(0.58f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Green)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(0.58f)) {
                item {
                    val srv = s.serving
                    val id = srv?.ident
                    val servKey = id?.let { stationKey(it) }
                    val st = stations.firstOrNull { it.key == servKey }
                    val raw = if (id != null && id.radio == "LTE" && bandsVer >= 0)
                        vm.bandsFor(id.mcc, id.mnc, id.cell shr 8) else emptyList()
                    ServingCard(srv, st, loc, raw)
                    HorizontalDivider()
                }
                val sel = stations.firstOrNull { it.key == selKey }
                if (sel != null) {
                    item {
                        val raw = if (sel.radio == "LTE" && bandsVer >= 0)
                            vm.bandsFor(sel.mcc, sel.mnc, sel.id) else emptyList()
                        StationCard(sel, loc, raw) { vm.select(null) }
                        HorizontalDivider()
                    }
                }
                items(s.cells.filter { !it.serving }) { c -> NeighbourRow(c) }
            }
        }
    }
}

internal fun bandSummary(raw: List<String>): String =
    raw.mapNotNull { it.split(";").getOrNull(1)?.takeIf { b -> b.isNotBlank() } }
        .distinct().joinToString(", ")

@Composable
private fun ServingCard(srv: CellRow?, st: Station?, loc: Location?, rawBands: List<String>) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
        if (srv == null) {
            Text("Нет основной соты", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            return@Column
        }
        val id = srv.ident
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Вы подключены", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                if (id != null) operatorName(id.mcc, id.mnc) else (srv.operator ?: ""),
                fontSize = 18.sp, color = Color.Gray,
            )
        }
        Text("${srv.tech} | диапазон ${srv.band ?: "—"}", fontSize = 18.sp)
        if (id != null && id.radio == "LTE") {
            Text(
                "БС (eNB) ${id.cell shr 8} · сектор ${id.cell and 0xFFL}",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Green,
            )
        } else if (id != null) {
            Text("Сота ${id.cell}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Green)
        }
        if (st != null && loc != null) {
            val d = fmtDist(distanceM(loc.latitude, loc.longitude, st.lat, st.lon))
            if (st.own) {
                Text(
                    "До БС: ~$d · положение оценено по вашим измерениям (${st.samples} точек), ±${st.range} м",
                    fontSize = 14.sp,
                )
            } else {
                Text("До БС: $d · точность координат БС ±${st.range} м (OpenCelliD)", fontSize = 14.sp)
            }
        } else if (id != null) {
            Text(
                "Координат БС пока нет. Походите или поездьте рядом: приложение оценит положение по вашим измерениям",
                fontSize = 14.sp, color = Color.Gray,
            )
        }
        Spacer(Modifier.height(8.dp))
        srv.details.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { (k, v) ->
                    Row(Modifier.weight(1f)) {
                        Text(k, Modifier.width(80.dp), fontSize = 16.sp)
                        Text(v, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        if (rawBands.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Частоты на этой БС (замечены телефоном): ${bandSummary(rawBands)}",
                fontWeight = FontWeight.Bold, fontSize = 14.sp,
            )
            rawBands.forEach { r ->
                val p = r.split(";")
                Text(
                    "сектор ${p.getOrNull(0)}: диапазон ${p.getOrNull(1)?.ifBlank { "—" } ?: "—"}" +
                            " · EARFCN ${p.getOrNull(2)?.ifBlank { "—" } ?: "—"}",
                    fontSize = 13.sp, color = Color.DarkGray,
                )
            }
        }
        if (st != null && !st.own && st.sectors.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Секторы в базе OpenCelliD: ${st.sectors.joinToString(", ")}", fontSize = 13.sp, color = Color.DarkGray)
        }
    }
}

@Composable
private fun StationCard(st: Station, loc: Location?, rawBands: List<String>, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFFFF4F2)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${st.radio} · ${if (st.radio == "LTE") "eNB " else "сота "}${st.id}",
                fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onClose) { Text("Закрыть") }
        }
        Text("Оператор: ${operatorName(st.mcc, st.mnc)}", fontSize = 14.sp)
        Text(
            if (st.own) "Источник: оценка по вашим измерениям (${st.samples} точек)" else "Источник: OpenCelliD",
            fontSize = 14.sp,
        )
        if (loc != null) {
            Text("Расстояние: ${fmtDist(distanceM(loc.latitude, loc.longitude, st.lat, st.lon))} · ±${st.range} м", fontSize = 14.sp)
        }
        if (st.sectors.isNotEmpty()) {
            Text("Секторы: ${st.sectors.joinToString(", ")}", fontSize = 14.sp)
        }
        if (rawBands.isNotEmpty()) {
            Text("Частоты (замечены телефоном): ${bandSummary(rawBands)}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        } else if (st.radio == "LTE") {
            Text("Частоты пока не замечены телефоном", fontSize = 13.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun NeighbourRow(c: CellRow) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .border(BorderStroke(1.dp, Green), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text(c.band ?: c.tech, color = Green, fontSize = 14.sp) }
            Spacer(Modifier.width(10.dp))
            Text(c.pci?.toString() ?: "", fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            c.channel?.let { Text(it.toString(), fontSize = 14.sp, color = Color.DarkGray) }
            Spacer(Modifier.width(10.dp))
            val d = c.dbm
            if (d != null) {
                Text(d.toString(), fontSize = 18.sp)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(10.dp).background(signalColor(d), RoundedCornerShape(50)))
            } else {
                Text(c.tech, fontSize = 18.sp)
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

// END
