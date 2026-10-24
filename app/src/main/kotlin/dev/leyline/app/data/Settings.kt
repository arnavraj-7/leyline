package dev.leyline.app.data

import android.content.Context
import androidx.core.content.edit
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Packet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences, exposed as flows so the UI and the mesh both react the moment
 * something changes. [nodeId] comes from this install's signing key.
 */
class Settings(context: Context, val nodeId: NodeId) {
    private val prefs = context.getSharedPreferences("leyline", Context.MODE_PRIVATE)

    private val _name = MutableStateFlow(prefs.getString(KEY_NAME, null) ?: "Phone-${nodeId.toString().takeLast(4)}")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _relay = MutableStateFlow(prefs.getBoolean(KEY_RELAY, true))
    val relay: StateFlow<Boolean> = _relay.asStateFlow()

    /** Turn relaying off by itself under 15% battery, unless charging. */
    private val _batterySaver = MutableStateFlow(prefs.getBoolean(KEY_BATTERY_SAVER, true))
    val batterySaver: StateFlow<Boolean> = _batterySaver.asStateFlow()

    private val _shareLocation = MutableStateFlow(prefs.getBoolean(KEY_LOCATION, true))
    val shareLocation: StateFlow<Boolean> = _shareLocation.asStateFlow()

    private val _ttl = MutableStateFlow(prefs.getInt(KEY_TTL, Packet.DEFAULT_TTL))
    val ttl: StateFlow<Int> = _ttl.asStateFlow()

    /** Topology mode: nodes treated as out of range, to force multi-hop for a demo. */
    private val _blocked = MutableStateFlow(
        prefs.getStringSet(KEY_BLOCKED, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.map(::NodeId).toSet(),
    )
    val blocked: StateFlow<Set<NodeId>> = _blocked.asStateFlow()

    fun setName(value: String) {
        val trimmed = value.trim().take(24).ifEmpty { return }
        _name.value = trimmed
        prefs.edit { putString(KEY_NAME, trimmed) }
    }

    fun setRelay(value: Boolean) {
        _relay.value = value
        prefs.edit { putBoolean(KEY_RELAY, value) }
    }

    fun setBatterySaver(value: Boolean) {
        _batterySaver.value = value
        prefs.edit { putBoolean(KEY_BATTERY_SAVER, value) }
    }

    fun setShareLocation(value: Boolean) {
        _shareLocation.value = value
        prefs.edit { putBoolean(KEY_LOCATION, value) }
    }

    fun setTtl(value: Int) {
        val ttl = value.coerceIn(1, Packet.MAX_TTL)
        _ttl.value = ttl
        prefs.edit { putInt(KEY_TTL, ttl) }
    }

    fun setBlocked(node: NodeId, blocked: Boolean) {
        val next = if (blocked) _blocked.value + node else _blocked.value - node
        _blocked.value = next
        prefs.edit { putStringSet(KEY_BLOCKED, next.map { it.value.toString() }.toSet()) }
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_RELAY = "relay"
        const val KEY_BATTERY_SAVER = "battery_saver"
        const val KEY_LOCATION = "share_location"
        const val KEY_TTL = "ttl"
        const val KEY_BLOCKED = "blocked"
    }
}
