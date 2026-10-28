package dev.leyline.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leyline.mesh.Packet

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onStartMesh: () -> Unit, onStopMesh: () -> Unit) {
    val s = viewModel.settings
    val name by s.name.collectAsStateWithLifecycle()
    val relay by s.relay.collectAsStateWithLifecycle()
    val saver by s.batterySaver.collectAsStateWithLifecycle()
    val shareLocation by s.shareLocation.collectAsStateWithLifecycle()
    val ttl by s.ttl.collectAsStateWithLifecycle()
    val running by viewModel.mesh.running.collectAsStateWithLifecycle()
    val nodes by viewModel.knownNodes.collectAsStateWithLifecycle()
    var nameDraft by rememberSaveable(name) { mutableStateOf(name) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val forgeryResult by viewModel.forgeryResult.collectAsStateWithLifecycle()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Group {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, viewModel.mesh.self, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Your Leyline ID: $name#${viewModel.mesh.self} · app v${viewModel.mesh.appVersion}\nShare it: anyone can message you with it, even from another group",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it.take(24) },
                    label = { Text("Your name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { s.setName(nameDraft) }, enabled = nameDraft.isNotBlank() && nameDraft != name) { Text("Save") }
            }
        }

        SectionLabel("Mesh")
        Group {
            Toggle("Relay for others", "Forward messages you hear so they reach further", relay, s::setRelay)
            Divider()
            Toggle("Battery saver", "Stop relaying under 15% unless charging", saver, s::setBatterySaver)
            Divider()
            Toggle("GPS in SOS", "Works offline; location must be on", shareLocation, s::setShareLocation)
            Divider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row {
                    Text("Hop limit", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text("$ttl ${if (ttl == 1) "hop" else "hops"}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = ttl.toFloat(),
                    onValueChange = { s.setTtl(it.toInt()) },
                    valueRange = 1f..Packet.MAX_TTL.toFloat(),
                    steps = Packet.MAX_TTL - 2,
                )
                Text(
                    "How far a message may travel. Higher reaches further but uses more airtime. 4 suits a building.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Demo tools: folded away so everyday settings come first.
        Collapsible("Topology mode", nodes.count { it.blocked }.let { if (it == 0) "for multi-hop demos" else "$it blocked" }) {
        Group {
            Text(
                "Treat chosen phones as out of range, to force multi-hop when everyone is in one room. Block on both phones of a pair.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (nodes.isEmpty()) {
                Text("No phones seen yet", Modifier.padding(start = 16.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            nodes.forEach { node ->
                Divider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(node.name ?: node.id.toString(), node.id, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(node.name ?: node.id.toString(), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (node.blocked) "Blocked: messages must hop around it" else if (node.linked) "Linked" else "Known",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (node.blocked) MeshColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = node.blocked, onCheckedChange = { s.setBlocked(node.id, it) })
                }
            }
        }
        }

        Collapsible("Security test", "try to fake an SOS") {
        Group {
            Text(
                "Every message is signed with a key kept in this phone's secure hardware. Try to fake one: this sends an SOS that claims to come from another phone. Every receiver should drop it.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val linked = nodes.filter { it.linked }
            if (linked.isEmpty()) {
                Text("Link to a phone first", Modifier.padding(start = 16.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            linked.forEach { node ->
                Divider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(node.name ?: node.id.toString(), node.id, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        node.name ?: node.id.toString(),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedButton(
                        onClick = { viewModel.sendForgedSos(node) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshColors.sos),
                    ) { Text("Fake their SOS") }
                }
            }
            forgeryResult?.let {
                Divider()
                Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        }

        SectionLabel("Service")
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (running) "The mesh runs in the background and keeps relaying with the screen off." else "The mesh is off. You won't receive or relay anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        OutlinedButton(onClick = onStopMesh) { Text("Stop mesh") }
                    } else {
                        Button(onClick = onStartMesh) { Text("Start mesh") }
                    }
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshColors.sos),
                    ) { Text("Clear data") }
                }
            }
        }
        Spacer(Modifier.padding(8.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear everything?") },
            text = { Text("Deletes all messages and the packets this phone is carrying for others.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text("Clear", color = MeshColors.sos) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
