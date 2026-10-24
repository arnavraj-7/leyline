package dev.leyline.app.mesh

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.leyline.app.LeylineApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the mesh alive with the screen off. Android kills background apps that
 * hold radios; a foreground service of type connectedDevice, with its ongoing
 * notification, is the sanctioned way to keep BLE links open.
 */
class MeshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationUpdates: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as LeylineApp).container
        if (intent?.action == ACTION_STOP_RING) {
            container.mesh.stopRinging()
            container.notifier.cancelRinging()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            container.mesh.stop()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = container.notifier.serviceNotification(neighbours = 0, relaying = true, outbox = 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifier.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(Notifier.SERVICE_ID, notification)
        }
        holdWakeLock()
        container.mesh.start()

        notificationUpdates?.cancel()
        notificationUpdates = scope.launch {
            combine(container.mesh.neighbours, container.mesh.relaying, container.repository.outboxSize) { n, relay, out ->
                container.notifier.serviceNotification(n.size, relay, out)
            }.collect { container.notificationManager.notify(Notifier.SERVICE_ID, it) }
        }
        // Restarted by the system after being killed: come back and keep relaying.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * A foreground service keeps the process alive, not the CPU. With the screen off
     * the phone dozes and relay delays, handshake timeouts and resyncs stall until
     * something else wakes it. A relay is only useful awake, so hold the lock for as
     * long as the mesh runs; the user stops it from the notification.
     */
    @SuppressLint("WakelockTimeout")
    private fun holdWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "leyline:mesh")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "dev.leyline.STOP"
        const val ACTION_STOP_RING = "dev.leyline.STOP_RING"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeshService::class.java))
        }
    }
}
