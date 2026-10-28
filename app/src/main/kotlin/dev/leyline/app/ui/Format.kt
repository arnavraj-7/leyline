package dev.leyline.app.ui

import androidx.compose.ui.graphics.Color
import dev.leyline.mesh.Location
import dev.leyline.mesh.NodeId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object MeshColors {
    val sos = Color(0xFFE5484D)
    val linked = Color(0xFF4CB782)
    val idle = Color(0xFF8B8D94)
    val relay = Color(0xFF8E94D8)
    val mesh = Color(0xFF5FB8AC)
    val warning = Color(0xFFD4A24C)

    /** Radar background: always dark, in both themes, so glowing links read well. */
    val radar = Color(0xFF101114)

    private val avatars = listOf(
        Color(0xFF5B9BD5), Color(0xFF9B87D6), Color(0xFFD98C5F), Color(0xFF5DB38A),
        Color(0xFFD47FA6), Color(0xFF7C86D9), Color(0xFFC9A94E), Color(0xFF5AAFA6),
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
    millis < 60_000 -> "%.1f s".format(Locale.US, millis / 1_000.0)
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
    val degrees = bearingDegrees(from, to)
    val compass = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((degrees + 22.5) / 45).toInt() % 8]
    return distance to compass
}

/** Initial great-circle bearing from [from] to [to], degrees clockwise from true north. */
fun bearingDegrees(from: Location, to: Location): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/** Metres east and north from [from] to [to]. Flat-earth, fine for the few km a mesh spans. */
fun eastNorthMetres(from: Location, to: Location): Pair<Double, Double> {
    val r = 6_371_000.0
    val east = Math.toRadians(to.longitude - from.longitude) * r * cos(Math.toRadians(from.latitude))
    val north = Math.toRadians(to.latitude - from.latitude) * r
    return east to north
}

fun formatDistance(metres: Double): String {
    val rounded = (metres / 10).roundToInt() * 10
    return if (rounded < 1_000) "$rounded m" else "%.1f km".format(Locale.US, metres / 1_000)
}
