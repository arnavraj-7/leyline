package dev.leyline.app.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryState(val percent: Int, val charging: Boolean) {
    /** Relaying for others is the first thing to give up when the phone is about to die. */
    val low: Boolean get() = !charging && percent in 0 until LOW_PERCENT

    companion object {
        const val LOW_PERCENT = 15
    }
}

/** Battery level from the sticky ACTION_BATTERY_CHANGED broadcast; no polling. */
class BatteryMonitor(private val context: Context) {
    private val _state = MutableStateFlow(BatteryState(percent = 100, charging = false))
    val state: StateFlow<BatteryState> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            update(intent)
        }
    }

    fun start() {
        // Sticky broadcast: registering returns the latest value immediately.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::update)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun update(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level >= 0 && scale > 0) _state.value = BatteryState(level * 100 / scale, charging)
    }
}
