package dev.leyline.app.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.leyline.mesh.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Field tool for the range slide: leave one phone still, walk away with this one.
 * Every [ROW_MILLIS] it logs each phone it can hear (signal, GPS distance, linked
 * or not) to a CSV. Where the rows for a phone stop is where the range ends.
 * Distance uses our fresh fix and the other phone's last presence beacon, which
 * is accurate when that phone isn't moving.
 */
@Composable
fun RangeTest(state: MeshUiState, freshLocation: suspend () -> Location?) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf<File?>(null) }
    var last by remember { mutableStateOf<File?>(null) }
    var rows by remember { mutableIntStateOf(0) }
    var me by remember { mutableStateOf<Location?>(null) }
    val latest by rememberUpdatedState(state)
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }

    LaunchedEffect(recording) {
        val file = recording ?: return@LaunchedEffect
        val started = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText("time,elapsed_s,peer,name,rssi_dbm,distance_m,my_accuracy_m,linked,seen_ago_s\n")
        }
        while (true) {
            me = freshLocation() ?: me
            val s = latest
            val t = SystemClock.elapsedRealtime()
            val lines = s.sightings.values.joinToString("") { sighting ->
                val node = sighting.info.node
                val distance = me?.let { here -> s.topology.positions[node]?.let { distanceAndBearing(here, it).first } }
                listOf(
                    Instant.now().toString(),
                    (t - started) / 1_000,
                    node,
                    (s.topology.names[node] ?: "").replace(',', ' '),
                    sighting.rssi,
                    distance?.let { "%.0f".format(java.util.Locale.US, it) } ?: "",
                    me?.accuracyMeters ?: "",
                    node in s.topology.direct,
                    (t - sighting.seenAt) / 1_000,
                ).joinToString(",") + "\n"
            }
            withContext(Dispatchers.IO) { file.appendText(lines) }
            rows += s.sightings.size
            delay(ROW_MILLIS)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Leave one phone still and walk away with this one. Keep this screen on. Where a phone's rows stop, the range ends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.sightings.isEmpty()) Text("No phones in range", style = MaterialTheme.typography.bodyMedium)
            state.sightings.values.sortedByDescending { it.rssi }.forEach { sighting ->
                val node = sighting.info.node
                val distance = me?.let { here -> state.topology.positions[node]?.let { formatDistance(distanceAndBearing(here, it).first) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.topology.names[node] ?: node.toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            "${sighting.rssi} dBm",
                            distance ?: "no GPS",
                            if (node in state.topology.direct) "linked" else "heard",
                            "${(now - sighting.seenAt).coerceAtLeast(0) / 1_000} s ago",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recording == null) {
                    Button(onClick = {
                        rows = 0
                        recording = File(context.filesDir, "rangetest/range-${System.currentTimeMillis()}.csv")
                    }) { Text("Start recording") }
                } else {
                    OutlinedButton(onClick = {
                        last = recording
                        recording = null
                    }) { Text("Stop · $rows rows") }
                }
                last?.takeIf { recording == null }?.let { file -> TextButton(onClick = { share(context, file) }) { Text("Share CSV") } }
            }
        }
    }
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share range test").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private const val ROW_MILLIS = 2_000L
