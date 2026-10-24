package dev.leyline.app.ui

import androidx.compose.ui.graphics.Color
import dev.leyline.mesh.Location
import dev.leyline.mesh.NodeId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object MeshColors {
    val sos = Color(0xFFF43F5E)
    val linked = Color(0xFF22C55E)
    val idle = Color(0xFF94A3B8)
    val relay = Color(0xFF818CF8)
    val mesh = Color(0xFF2DD4BF)
    val warning = Color(0xFFF59E0B)

    /** Radar background: always dark, in both themes, so glowing links read well. */
    val radar = Color(0xFF070D19)

    private val avatars = listOf(
        Color(0xFF0EA5E9), Color(0xFF8B5CF6), Color(0xFFF97316), Color(0xFF10B981),
        Color(0xFFEC4899), Color(0xFF6366F1), Color(0xFFEAB308), Color(0xFF14B8A6),
    )

    /** Stable colour per node, so a person keeps their colour across screens and phones. */
    fun avatar(node: NodeId): Color = avatars[Math.floorMod(node.value * 31 + 7, avatars.size)]
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

fun formatTime(epochMillis: Long): String = timeFormat.format(Instant.ofEpochMilli(epochMillis))

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

/** To the millisecond, for the packet log. */
fun formatClock(epochMillis: Long): String = clockFormat.format(Instant.ofEpochMilli(epochMillis))

fun formatDuration(millis: Long): String = when {
    millis < 1_000 -> "$millis ms"
    millis < 60_000 -> "%.1f s".format(millis / 1_000.0)
    else -> "${millis / 60_000} min ${millis / 1_000 % 60} s"
}

fun initials(name: String): String {
    val parts = name.trim().split(' ', '-', '_').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

fun hopsLabel(hops: Int) = if (hops <= 1) "Direct" else "$hops hops"

/** Great-circle distance in metres and compass bearing from [from] to [to]. */
fun distanceAndBearing(from: Location, to: Location): Pair<Double, String> {
    val r = 6_371_000.0
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    val distance = 2 * r * atan2(sqrt(a), sqrt(1 - a))
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val degrees = (Math.toDegrees(atan2(y, x)) + 360) % 360
    val compass = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((degrees + 22.5) / 45).toInt() % 8]
    return distance to compass
}

fun formatDistance(metres: Double): String = when {
    metres < 1_000 -> "${(metres / 10).roundToInt() * 10} m"
    else -> "%.1f km".format(metres / 1_000)
}
