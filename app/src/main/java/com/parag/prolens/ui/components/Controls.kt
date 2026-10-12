package com.parag.prolens.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parag.prolens.ai.ShootMode
import com.parag.prolens.ui.theme.Pro
import kotlin.math.abs

@Composable
fun GlassIcon(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    rotation: Float = 0f,
    size: Dp = 40.dp,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(if (active) Pro.Gold else Pro.Text, label = "tint")
    androidx.compose.foundation.layout.Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp).rotate(rotation))
        Text(label, color = tint, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
fun Pill(
    text: String,
    icon: ImageVector? = null,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val bg = if (selected) Pro.Gold else Pro.Glass
    val fg = if (selected) Color.Black else Pro.Text
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Swipeable mode dial, like the mode wheel on a DSLR. */
@Composable
fun ModeSelector(current: ShootMode, onSelect: (ShootMode) -> Unit, modifier: Modifier = Modifier) {
    val modes = ShootMode.entries
    val state = rememberLazyListState()
    LaunchedEffect(current) {
        state.animateScrollToItem((modes.indexOf(current) - 2).coerceAtLeast(0))
    }
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(modes) { _, m ->
            val sel = m == current
            val color by animateColorAsState(if (sel) Pro.Gold else Pro.TextDim, label = "mode")
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) Pro.Gold.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onSelect(m) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(m.icon, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    m.title.uppercase(), color = color, fontSize = 12.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, letterSpacing = 1.2.sp,
                )
            }
        }
    }
}

@Composable
fun ShutterButton(busy: Boolean, aiMode: Boolean, onClick: () -> Unit) {
    val pressScale by animateFloatAsState(if (busy) 0.9f else 1f, label = "press")
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Box(
        Modifier
            .size(82.dp)
            .scale(pressScale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White, size.minDimension / 2 - 2.dp.toPx(), style = Stroke(4.dp.toPx()))
            if (busy) {
                drawArc(
                    Pro.Gold, angle, 90f, false,
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                    style = Stroke(4.dp.toPx()),
                )
            }
        }
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    if (aiMode) Brush.radialGradient(listOf(Color.White, Color(0xFFFFE8B0)))
                    else Brush.radialGradient(listOf(Color.White, Color.White))
                )
        )
    }
}

@Composable
fun GalleryThumb(image: ImageBitmap?, rotation: Float, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .rotate(rotation)
            .clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.7f)), RoundedCornerShape(14.dp))
            .background(Pro.PanelHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.PhotoLibrary, "Gallery", tint = Pro.TextDim)
        }
    }
}

@Composable
fun FlipButton(rotation: Float, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Pro.PanelHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.FlipCameraAndroid, "Switch camera", tint = Pro.Text, modifier = Modifier.rotate(rotation))
    }
}

/** Lens buttons (ultra-wide / main / tele...), built from the zoom range the phone reports. */
@Composable
fun ZoomChips(zoom: Float, min: Float, max: Float, equivMm: Float, onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    val stops = buildList {
        if (min < 0.95f) add(min)
        add(1f)
        listOf(2f, 3.5f, 5f, 10f).forEach { if (it <= max + 0.01f) add(it) }
    }.distinct()
    val nearest = stops.minByOrNull { abs(it - zoom) } ?: 1f
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Pro.Glass)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stops.forEach { s ->
            val sel = s == nearest
            val label = if (sel && abs(zoom - s) > 0.05f) "%.1f×".format(zoom) else fmtZoom(s)
            Box(
                Modifier
                    .size(if (sel) 40.dp else 34.dp)
                    .clip(CircleShape)
                    .background(if (sel) Color.Black.copy(alpha = 0.6f) else Color.Transparent)
                    .clickable { onZoom(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) Pro.Gold else Pro.Text, fontSize = if (sel) 12.sp else 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "${(equivMm * zoom).toInt()}mm", color = Pro.TextDim, fontSize = 10.sp,
            modifier = Modifier.padding(start = 2.dp, end = 8.dp), fontFamily = FontFamily.Monospace,
        )
    }
}

private fun fmtZoom(z: Float): String = when {
    z < 1f -> "%.1f".format(z).removePrefix("0") + "×"
    z == z.toInt().toFloat() -> "${z.toInt()}×"
    else -> "%.1f×".format(z)
}

/** The data strip a DSLR shows at the bottom of its viewfinder. */
@Composable
fun ReadoutStrip(items: List<Pair<String, Boolean>>, meterStops: Float, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Pro.Glass)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { (t, manual) ->
            Text(
                t, color = if (manual) Pro.Gold else Pro.Text, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            )
        }
        ExposureMeter(meterStops)
    }
}

/** -2 ... 0 ... +2 exposure level indicator. */
@Composable
fun ExposureMeter(stops: Float) {
    Canvas(Modifier.width(54.dp).height(14.dp)) {
        val mid = size.width / 2
        val unit = size.width / 4.4f
        for (i in -2..2) {
            val x = mid + i * unit
            drawLine(Color.White.copy(alpha = 0.6f), Offset(x, size.height * (if (i == 0) 0.1f else 0.35f)), Offset(x, size.height * 0.65f), 1.dp.toPx())
        }
        val v = stops.coerceIn(-2.2f, 2.2f)
        val c = if (abs(stops) < 0.35f) Pro.Good else Pro.Gold
        drawRect(c, Offset(mid + minOf(0f, v * unit), size.height * 0.75f), Size(abs(v * unit).coerceAtLeast(2.dp.toPx()), 3.dp.toPx()))
    }
}

@Composable
fun SectionTab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c by animateColorAsState(if (selected) Pro.Gold else Pro.TextDim, label = "tab")
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Pro.Gold.copy(alpha = 0.12f) else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) Pro.Gold.copy(alpha = 0.5f) else Pro.Line), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
