package dev.leyline.app.mesh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.leyline.app.MainActivity
import dev.leyline.app.R
import dev.leyline.mesh.ResponseStatus

/** Notification channels: a quiet one for the relay service, a loud one for incoming SOS. */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESH, "Mesh relay", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Leyline relays messages in the background"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SOS, "Incoming SOS", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "SOS messages received over the mesh"
                enableVibration(true)
            },
        )
    }

    fun serviceNotification(neighbours: Int, relaying: Boolean, outbox: Int): Notification {
        val text = buildString {
            append(if (neighbours == 1) "1 phone nearby" else "$neighbours phones nearby")
            append(if (relaying) " · relaying" else " · relay off")
            if (outbox > 0) append(" · carrying $outbox")
        }
        return Notification.Builder(context, CHANNEL_MESH)
            .setSmallIcon(R.drawable.ic_stat_leyline)
            .setContentTitle("Leyline mesh active")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    PendingIntent.getService(
                        context,
                        1,
                        Intent(context, MeshService::class.java).setAction(MeshService.ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
            .build()
    }

    fun incomingSos(messageId: Long, sender: String, text: String, hops: Int) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        val notification = Notification.Builder(context, CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_stat_leyline)
            .setContentTitle("SOS from $sender · ${if (hops == 1) "1 hop" else "$hops hops"}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(messageId.hashCode(), notification)
    }

    /** A rescuer answered our SOS. Loud, on the SOS channel: it's the news the person is waiting for. */
    fun helpComing(id: Long, rescuer: String, status: ResponseStatus) {
        if (!allowed()) return
        val (title, text) = when (status) {
            ResponseStatus.ON_THE_WAY -> "Help is coming" to "$rescuer is on the way to you"
            ResponseStatus.FOUND -> "You've been found" to "$rescuer has reached you"
        }
        val notification = Notification.Builder(context, CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_stat_leyline)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(id.hashCode(), notification)
    }

    /** Shown while the alarm rings, with a way to stop it from the lock screen. */
    fun ringing(rescuer: String) {
        if (!allowed()) return
        val notification = Notification.Builder(context, CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_stat_leyline)
            .setContentTitle("$rescuer is looking for you")
            .setContentText("Your phone is ringing so they can find you by sound")
            .setCategory(Notification.CATEGORY_ALARM)
            .setTimeoutAfter(RING_NOTICE_MILLIS)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop ringing",
                    PendingIntent.getService(
                        context,
                        2,
                        Intent(context, MeshService::class.java).setAction(MeshService.ACTION_STOP_RING),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
            .build()
        manager.notify(RING_ID, notification)
    }

    fun cancelRinging() = manager.cancel(RING_ID)

    private fun allowed() = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_MESH = "mesh"
        const val CHANNEL_SOS = "sos"
        const val SERVICE_ID = 1
        private const val RING_ID = 2
        private const val RING_NOTICE_MILLIS = 30_000L
    }
}
