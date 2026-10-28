package dev.leyline.app.ui

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.leyline.mesh.Location

/**
 * Arrow that points at [target] as you turn: GPS gives the bearing from us to
 * them, the rotation sensor gives where the phone's top points. Corrected from
 * magnetic to true north, since GPS bearings are true. Below about the GPS
 * error the direction means nothing, so it says to follow the signal instead.
 */
@Composable
fun PointerArrow(me: Location?, target: Location, modifier: Modifier = Modifier) {
    val heading = rememberHeading(me)
    if (me == null) {
        Text("Waiting for your GPS fix to point the way", modifier, style = MaterialTheme.typography.labelSmall)
        return
    }
    val (distance, compass) = distanceAndBearing(me, target)
    val rough = distance < maxOf(15.0, (me.accuracyMeters + target.accuracyMeters).toDouble())
    val bearing = bearingDegrees(me, target).toFloat()
    // Animate along the short way round, so 359° → 1° is a nudge, not a full spin.
    val angle = remember { Animatable(0f) }
    LaunchedEffect(bearing, heading) {
        val goal = bearing - (heading ?: 0f)
        val delta = ((goal - angle.value) % 360 + 540) % 360 - 180
        angle.animateTo(angle.value + delta)
    }
    val color = if (rough) MeshColors.idle else MeshColors.mesh
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(120.dp)) {
            drawCircle(color.copy(alpha = 0.15f))
            drawCircle(color.copy(alpha = 0.5f), style = Stroke(2.dp.toPx()))
            rotate(angle.value) {
                val w = size.width
                val arrow = Path().apply {
                    moveTo(w / 2, w * 0.14f)
                    lineTo(w * 0.72f, w * 0.62f)
                    lineTo(w / 2, w * 0.52f)
                    lineTo(w * 0.28f, w * 0.62f)
                    close()
                }
                drawPath(arrow, color)
                drawCircle(color, radius = 4.dp.toPx(), center = Offset(w / 2, w / 2))
            }
        }
        Text(
            when {
                rough -> "≈ ${formatDistance(distance)}: closer than GPS can tell, follow the signal"
                heading == null -> "${formatDistance(distance)} $compass · no compass on this phone, arrow is north-up"
                else -> "${formatDistance(distance)} $compass · hold the phone flat and turn"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Where the top of the phone points, degrees from true north; null without a rotation sensor. */
@Composable
private fun rememberHeading(at: Location?): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    val declination = remember(at?.latE7?.div(100_000), at?.lonE7?.div(100_000)) {
        at?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, System.currentTimeMillis()).declination } ?: 0f
    }
    DisposableEffect(declination) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                heading = (Math.toDegrees(orientation[0].toDouble()).toFloat() + declination + 360) % 360
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager?.unregisterListener(listener) }
    }
    return heading
}
