package com.parag.prolens.ui.components

import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parag.prolens.GridType
import com.parag.prolens.ui.theme.Pro
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun GridOverlay(type: GridType, modifier: Modifier = Modifier) {
    if (type == GridType.OFF) return
    Canvas(modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.35f)
        val sw = 1.dp.toPx()
        val fractions = when (type) {
            GridType.GOLDEN -> listOf(0.382f, 0.618f)
            else -> listOf(1f / 3f, 2f / 3f)
        }
        for (f in fractions) {
            drawLine(c, Offset(size.width * f, 0f), Offset(size.width * f, size.height), sw)
            drawLine(c, Offset(0f, size.height * f), Offset(size.width, size.height * f), sw)
        }
        if (type == GridType.CROSS) {
            val cx = size.width / 2; val cy = size.height / 2; val l = 14.dp.toPx()
            drawLine(Pro.Gold, Offset(cx - l, cy), Offset(cx + l, cy), sw * 1.5f)
            drawLine(Pro.Gold, Offset(cx, cy - l), Offset(cx, cy + l), sw * 1.5f)
        }
    }
}

/** Spirit level: a horizon line that turns green when the camera is perfectly level. */
@Composable
fun LevelOverlay(roll: Float, pitch: Float, modifier: Modifier = Modifier) {
    if (pitch > 70f) return
    val level = abs(roll) < 1f
    val color = if (level) Pro.Good else Color.White.copy(alpha = 0.8f)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2
            val half = size.width * 0.18f
            val gap = 18.dp.toPx()
            rotate(-roll, Offset(cx, cy)) {
                drawLine(color, Offset(cx - half, cy), Offset(cx - gap, cy), 2.dp.toPx())
                drawLine(color, Offset(cx + gap, cy), Offset(cx + half, cy), 2.dp.toPx())
            }
            // fixed reference ticks
            drawLine(Color.White.copy(alpha = 0.4f), Offset(cx - half - 16.dp.toPx(), cy), Offset(cx - half - 6.dp.toPx(), cy), 1.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.4f), Offset(cx + half + 6.dp.toPx(), cy), Offset(cx + half + 16.dp.toPx(), cy), 1.5.dp.toPx())
        }
        if (!level) {
            Text(
                "${roll.roundToInt()}°",
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.padding(top = 36.dp),
            )
        }
    }
}

@Composable
fun FaceOverlay(faces: List<RectF>, mirror: Boolean, modifier: Modifier = Modifier) {
    if (faces.isEmpty()) return
    Canvas(modifier.fillMaxSize()) {
        for (f in faces) {
            val l = (if (mirror) 1f - f.right else f.left) * size.width
            val r = (if (mirror) 1f - f.left else f.right) * size.width
            val t = f.top * size.height
            val b = f.bottom * size.height
            val len = (r - l) * 0.22f
            val sw = 2.dp.toPx()
            val c = Pro.Gold
            // corner brackets, like a pro body's eye/face AF box
            drawLine(c, Offset(l, t), Offset(l + len, t), sw); drawLine(c, Offset(l, t), Offset(l, t + len), sw)
            drawLine(c, Offset(r, t), Offset(r - len, t), sw); drawLine(c, Offset(r, t), Offset(r, t + len), sw)
            drawLine(c, Offset(l, b), Offset(l + len, b), sw); drawLine(c, Offset(l, b), Offset(l, b - len), sw)
            drawLine(c, Offset(r, b), Offset(r - len, b), sw); drawLine(c, Offset(r, b), Offset(r, b - len), sw)
        }
    }
}

@Composable
fun FocusRing(at: Pair<Offset, Long>?, modifier: Modifier = Modifier) {
    if (at == null) return
    val anim = remember(at.second) { Animatable(1.5f) }
    val alpha = remember(at.second) { Animatable(1f) }
    LaunchedEffect(at.second) {
        anim.animateTo(1f, tween(220))
        alpha.animateTo(0f, tween(900, delayMillis = 900))
    }
    Canvas(modifier.fillMaxSize().alpha(alpha.value)) {
        val s = 72.dp.toPx() * anim.value
        drawRoundRect(
            Pro.Gold,
            topLeft = Offset(at.first.x - s / 2, at.first.y - s / 2),
            size = Size(s, s),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(1.6.dp.toPx()),
        )
        drawCircle(Pro.Gold, 2.5.dp.toPx(), at.first)
    }
}

@Composable
fun Histogram(bins: IntArray, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 112.dp, height = 48.dp)
            .background(Pro.Glass, RoundedCornerShape(8.dp))
            .padding(4.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val maxV = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
            val path = Path()
            val step = size.width / (bins.size - 1)
            path.moveTo(0f, size.height)
            bins.forEachIndexed { i, v ->
                val h = kotlin.math.sqrt(v / maxV) * size.height
                path.lineTo(i * step, size.height - h)
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, Color.White.copy(alpha = 0.55f))
            // clipping warnings
            if (bins.last() > maxV * 0.15f) drawRect(Pro.Rec, Offset(size.width - 3.dp.toPx(), 0f), Size(3.dp.toPx(), size.height))
            if (bins.first() > maxV * 0.15f) drawRect(Color(0xFF5B8CFF), Offset.Zero, Size(3.dp.toPx(), size.height))
        }
    }
}

@Composable
fun Countdown(value: Int, modifier: Modifier = Modifier) {
    if (value <= 0) return
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$value", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
fun ShutterBlink(trigger: Int, modifier: Modifier = Modifier) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        a.snapTo(0.85f)
        a.animateTo(0f, tween(260))
    }
    if (a.value > 0f) Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = a.value)))
}
