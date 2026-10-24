package dev.leyline.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leyline.mesh.NodeId

/** Initials on a colour that belongs to this node. */
@Composable
fun Avatar(name: String, node: NodeId, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MeshColors.avatar(node)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp)
    }
}

/** Small rounded label: hop count, "SOS", "linked". */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = if (filled) Color.White else color, style = MaterialTheme.typography.labelSmall)
    }
}

/** A dot that breathes while something is live (linked, beacon on). */
@Composable
fun PulsingDot(color: Color, live: Boolean, size: Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Restart),
        label = "scale",
    )
    Canvas(Modifier.size(size * 2.4f)) {
        val r = size.toPx() / 2
        if (live) drawCircle(color.copy(alpha = (2.4f - scale) / 1.4f * 0.45f), radius = r * scale)
        drawCircle(if (live) color else MeshColors.idle, radius = r)
    }
}

/** Four bars from RSSI: about -55 dBm is arm's length, -90 dBm the edge of range. */
@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    val level = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(18.dp, 14.dp)) {
        val w = size.width / 5.5f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4
            drawRoundRect(
                color = if (i < level) on else off,
                topLeft = Offset(i * w * 1.5f, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 3),
            )
        }
    }
}

/** The brand mark: three linked nodes. */
@Composable
fun LeylineMark(size: Dp = 28.dp) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val a = Offset(s * 0.22f, s * 0.30f)
        val b = Offset(s * 0.78f, s * 0.30f)
        val c = Offset(s * 0.50f, s * 0.80f)
        val stroke = s * 0.08f
        drawLine(color, a, b, stroke)
        drawLine(color, a, c, stroke)
        drawLine(color, b, c, stroke)
        listOf(a, b, c).forEach { drawCircle(color, radius = s * 0.14f, center = it) }
        drawCircle(MeshColors.sos, radius = s * 0.08f, center = c)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
    )
}

@Composable
fun StatusRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}
