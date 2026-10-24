package dev.leyline.app.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import dev.leyline.mesh.Location
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * One GPS fix for an SOS. GPS needs satellites, not internet, so this works in
 * airplane mode with location on. Uses the platform LocationManager rather than
 * Play Services, which some phones lack and which wants a network.
 */
class LocationProvider(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Last fix any provider has, without waiting; for showing distance to an SOS. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!permitted() || manager == null) return null
        val fix = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        return fix?.let { Location.of(it.latitude, it.longitude, it.accuracy) }
    }

    /**
     * A fresh fix if one arrives within [timeoutMillis], else the last known one.
     * An SOS should not wait long: a cold GPS start indoors can take minutes.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(timeoutMillis: Long = 4_000): Location? {
        if (!permitted() || manager == null) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine<android.location.Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(providers.first(), signal, executor) { continuation.resume(it) }
                }
            }
        } else {
            null
        }
        val fix = fresh ?: providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        return fix?.let { Location.of(it.latitude, it.longitude, it.accuracy) }
    }
}
