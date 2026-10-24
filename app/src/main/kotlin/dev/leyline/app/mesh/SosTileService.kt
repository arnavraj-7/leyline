package dev.leyline.app.mesh

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.leyline.app.LeylineApp
import dev.leyline.app.MainActivity

/**
 * Quick Settings tile: SOS from the notification shade, without unlocking or
 * finding the app. If the mesh is running it sends at once; otherwise it opens
 * the app with the SOS screen ready, because Android won't let a tile start a
 * foreground service by itself.
 */
class SosTileService : TileService() {
    override fun onStartListening() {
        val running = (application as LeylineApp).container.mesh.running.value
        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Leyline SOS"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = if (running) "Tap to send" else "Opens app"
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val container = (application as LeylineApp).container
        if (container.mesh.running.value) {
            container.sendQuickSos()
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_SOS, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
