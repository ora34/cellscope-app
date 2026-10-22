package app.cellscope

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MenuScreen(vm: CellViewModel) {
    val key by vm.apiKey.collectAsStateWithLifecycle()
    val credits by vm.credits.collectAsStateWithLifecycle()
    val text by remember(key) { mutableStateOf(key) }
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Настройки", fontSize = 24.sp, fontWeight = FontWeight.Bold)

            Text("Собственные измерения", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Пока приложение открыто, оно записывает ваши GPS-точки и уровень сигнала станций, " +
                        "к которой вы подключены, и оценивает положение вышки. Чем с большего числа " +
                        "сторон вы пройдете вокруг вышки, тем точнее оценка. Работает без интернета и без ключа.",
                fontSize = 14.sp,
            )
            OutlinedButton(onClick = { vm.clearOwn() }) { Text("Очистить мои измерения") }

            Text("Ключ OpenCellID (необязательно)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "С ключом приложение поддерживает координаты вышек из открытой базы OpenCellID. " +
                        "\"Данные OpenCellID важнее собственных оценок.",
                fontSize = 14.sp
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("API-ключ OpenCellID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.setKey(text) }) { Text("Сохранить") }
            Text("Как получить ключ:", fontWeight = FontWeight.Bold)
            Text("1. Откройте в браузере opencellid.org и зарегистрируйтесь (бесплатно).")
            Text("2. Войдите, откройте профиль и скопируйте «API Access Token».")
            Text("3. Вставьте его выше и нажмите «Сохранить».")

            Text(
                "Использовано запросов сегодня: $credits из 1000. Каждая за­груженная сота " +
                        "считается как один запрос, поэтому вышки сохраняются на телефоне и повторно не запрашиваются.",
                fontSize = 13.sp, color = Color.DarkGray
            )
        }
    }
}

@Composable
fun ChartsScreen(vm: CellViewModel) {
    val hist by vm.history.collectAsStateWithLifecycle()
    val values = hist.mapNotNull { it.serving?.dbm }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Уровень основной соты, дБм", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (values.size < 2) {
            Text("Копим данные...", color = Color.Gray)
            return@Column
        }
        Text("Сейчас: ${values.last()} дБм   (мин ${values.min()}, макс ${values.max()}))")
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier.fillMaxWidth().height(24.dp)
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
                    strokeWidth = 5f, cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun JournalScreen(vm: CellViewModel) {
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
                        "${fmt.format(Date(s.time))} — ${c.tech} ${c.band} ?",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        c.details.joinToString(" ") { "${it.first} ${it.second})" },
                        fontSize = 13.sp, color = Color.DarkGray
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun Placeholder(title: String, text: String) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Color.Gray)
    }
}
// END
