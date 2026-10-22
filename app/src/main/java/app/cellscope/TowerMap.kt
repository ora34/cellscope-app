package app.cellscope

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.max
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private fun stationIcon(ctx: Context, l1: String, l2: String, fill: Int, big: Boolean): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; color = 0xFF111111.toInt() }
    val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d; color = 0xFF555555.toInt() }
    val hasSub = l2.isNotBlank()
    val tw = max(text.measureText(l1), if (hasSub) sub.measureText(l2) else 0f)
    val boxW = tw + 14f * d
    val w = max(boxW, 30f * d).toInt() + 2
    val r = (if (big) 11f else 8f) * d
    val cy = 14f * d
    val boxTop = 28f * d
    val boxH = ((if (hasSub) 2 else 1) * 15f + 8f) * d
    val h = (boxTop + boxH).toInt() + 2
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.style = Paint.Style.FILL
    p.color = fill
    cv.drawCircle(w / 2f, cy, r, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = 2f * d
    p.color = 0xFFFFFFFF.toInt()
    cv.drawCircle(w / 2f, cy, r, p)
    val rect = RectF((w - boxW) / 2f, boxTop, (w + boxW) / 2f, boxTop + boxH)
    p.style = Paint.Style.FILL
    p.color = 0xFFFFFFFF.toInt()
    cv.drawRoundRect(rect, 6f * d, 6f * d, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = 1f * d
    p.color = 0xFF999999.toInt()
    cv.drawRoundRect(rect, 6f * d, 6f * d, p)
    cv.drawText(l1, rect.left + 7f * d, boxTop + 15f * d, text)
    if (hasSub) cv.drawText(l2, rect.left + 7f * d, boxTop + 29f * d, sub)
    return BitmapDrawable(ctx.resources, bmp)
}

private fun createMap(ctx: Context): MapView = MapView(ctx).apply {
    setTileSource(TileSourceFactory.MAPNIK)
    setMultiTouchControls(true)
    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    minZoomLevel = 4.0
    maxZoomLevel = 19.0
    controller.setZoom(4.0)
    controller.setCenter(GeoPoint(60.0, 90.0))
}

@Composable
internal fun TowerMap(vm: CellViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val stations by vm.stations.collectAsStateWithLifecycle()
    val loc by vm.location.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val bandsVer by vm.bandsVersion.collectAsStateWithLifecycle()

    val map = remember { createMap(ctx) }
    val centered = remember { booleanArrayOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, map) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            map.onDetach()
        }
    }

    val curStations = stations
    val me = loc
    val servKey = snap?.serving?.ident?.let { stationKey(it) }
    val ver = bandsVer
    val density = ctx.resources.displayMetrics.density

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { map },
            update = { m ->
                m.overlays.clear()
                val servSt = curStations.firstOrNull { it.key == servKey }
                if (me != null && servSt != null) {
                    m.overlays.add(Polyline().apply {
                        setPoints(listOf(GeoPoint(me.latitude, me.longitude), GeoPoint(servSt.lat, servSt.lon)))
                        outlinePaint.color = 0xFFFF9800.toInt()
                        outlinePaint.strokeWidth = 6f
                    })
                }
                curStations.forEach { st ->
                    val raw = if (st.radio == "LTE" && ver >= 0) vm.bandsFor(st.mcc, st.mnc, st.id) else emptyList()
                    val isServing = st.key == servKey
                    val color = when {
                        isServing -> 0xFF0FA968.toInt()
                        st.own -> 0xFF8E24AA.toInt()
                        else -> 0xFFE53935.toInt()
                    }
                    val icon = stationIcon(
                        ctx,
                        (if (st.own) "~" else "") + st.id.toString(),
                        if (st.radio == "LTE") bandSummary(raw) else st.radio,
                        color,
                        isServing,
                    )
                    m.overlays.add(Marker(m).apply {
                        position = GeoPoint(st.lat, st.lon)
                        setAnchor(Marker.ANCHOR_CENTER, (14f * density) / icon.intrinsicHeight)
                        setIcon(icon)
                        setOnMarkerClickListener { _, _ ->
                            vm.select(st.key)
                            true
                        }
                    })
                }
                if (me != null) {
                    val icon = stationIcon(ctx, "Вы", "", 0xFF1E88E5.toInt(), false)
                    m.overlays.add(Marker(m).apply {
                        position = GeoPoint(me.latitude, me.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, (14f * density) / icon.intrinsicHeight)
                        setIcon(icon)
                        setOnMarkerClickListener { _, _ -> true }
                    })
                    if (!centered[0]) {
                        centered[0] = true
                        m.controller.setZoom(15.0)
                        m.controller.setCenter(GeoPoint(me.latitude, me.longitude))
                    }
                }
                m.invalidate()
            },
        )
        Row(
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = { vm.load(more = false, force = true) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("Обновить", fontSize = 12.sp) }
            Button(
                onClick = { vm.load(more = true, force = false) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("Ещё вышки", fontSize = 12.sp) }
        }
        Column(
            Modifier.align(Alignment.CenterEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(
                onClick = { map.controller.zoomIn() },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White),
            ) { Icon(Icons.Default.Add, "Приблизить") }
            IconButton(
                onClick = { map.controller.zoomOut() },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White),
            ) { Icon(Icons.Default.Remove, "Отдалить") }
            IconButton(
                onClick = {
                    val l = me
                    if (l != null) {
                        map.controller.animateTo(GeoPoint(l.latitude, l.longitude))
                        map.controller.setZoom(15.0)
                    }
                },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White),
            ) { Icon(Icons.Default.MyLocation, "Где я") }
        }
    }
}

// END
