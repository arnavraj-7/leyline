package dev.leyline.app.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leyline.app.data.MessageEntity
import dev.leyline.app.data.MessageWithReceipts
import dev.leyline.mesh.Location
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.ResponseStatus
import dev.leyline.mesh.SosKind
import kotlinx.coroutines.delay

/** One SOS as a rescuer sees it: how urgent, how far, and who is already on it. */
data class LiveSos(
    val item: MessageWithReceipts,
    val responses: List<MessageEntity>,
    val metres: Double?,
    val direction: String?,
    val found: Boolean,
    val urgency: Int,
) {
    val message: MessageEntity get() = item.message
}

/** How long an SOS stays on the rescue list: as long as the mesh carries it. */
private const val SOS_SHOWN_MILLIS = 6 * 60 * 60_000L

/** Live SOS, most urgent then nearest first; ones already found go to the bottom. */
fun liveSos(state: MessagesUiState, now: Long = System.currentTimeMillis()): List<LiveSos> =
    state.messages
        .filter { it.message.sos && now - it.message.receivedAt < SOS_SHOWN_MILLIS }
        .map { item ->
            val m = item.message
            val responses = state.responses[m.id].orEmpty()
            val there = m.location()
            val here = state.myLocation
            val fix = if (!m.outgoing && there != null && here != null) distanceAndBearing(here, there) else null
            LiveSos(
                item = item,
                responses = responses,
                metres = fix?.first,
                direction = fix?.second,
                found = responses.any { it.responseStatus == ResponseStatus.FOUND.code },
                urgency = urgency(m),
            )
        }
        .sortedWith(
            compareBy<LiveSos> { it.found }
                .thenByDescending { it.urgency }
                .thenBy { it.metres ?: Double.MAX_VALUE }
                .thenByDescending { it.message.receivedAt },
        )

/**
 * Rough triage order: life-threatening kinds and injuries first, then how many
 * people. An SOS without details ranks above "need supplies": it could be anything.
 */
fun urgency(m: MessageEntity): Int {
    val kind = when (m.sosKind?.let(SosKind::fromCode)) {
        SosKind.MEDICAL, SosKind.FIRE -> 3
        SosKind.TRAPPED -> 2
        SosKind.SUPPLIES -> 1
        SosKind.GENERAL, null -> 2
    }
    return (kind + if (m.injured) 3 else 0) * 10 + m.people.coerceAtMost(9)
}

fun MessageEntity.location(): Location? =
    if (latE7 != null && lonE7 != null) Location(latE7, lonE7, accuracyMeters ?: 0) else null

fun kindLabel(kind: SosKind): String = when (kind) {
    SosKind.GENERAL -> "Help"
    SosKind.MEDICAL -> "Medical"
    SosKind.TRAPPED -> "Trapped"
    SosKind.FIRE -> "Fire"
    SosKind.SUPPLIES -> "Food / water"
}

/** Kind, head count and injuries as pills; nothing if the sender gave no details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SosDetailsRow(m: MessageEntity, modifier: Modifier = Modifier) {
    val kind = m.sosKind?.let(SosKind::fromCode)
    if (kind == null && m.people == 0 && !m.injured) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (kind != null && kind != SosKind.GENERAL) Pill(kindLabel(kind).uppercase(), MeshColors.sos, filled = true)
        if (m.people > 0) Pill(if (m.people == 1) "1 person" else "${m.people} people", MeshColors.warning)
        if (m.injured) Pill("Injured", MeshColors.sos)
    }
}

/** Who answered this SOS, latest status per rescuer. */
@Composable
fun Responders(m: MessageEntity, responses: List<MessageEntity>, state: MessagesUiState) {
    if (responses.isEmpty()) return
    val latest = responses.groupBy { it.origin }.values.map { it.last() }.sortedBy { it.receivedAt }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        latest.forEach { r ->
            val who = if (r.outgoing) "You" else r.senderName ?: state.names[NodeId(r.origin)] ?: NodeId(r.origin).toString().takeLast(4)
            val found = r.responseStatus == ResponseStatus.FOUND.code
            val away = if (r.outgoing) null else r.location()?.let { there -> state.myLocation?.let { "${formatDistance(distanceAndBearing(it, there).first)} away" } }
            val what = when {
                found && m.outgoing -> "✓ $who reached you"
                found -> "✓ Found by $who"
                r.outgoing -> "You're on the way"
                else -> "$who is on the way"
            }
            Text(
                listOfNotNull(what, away, formatTime(r.receivedAt)).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = if (found) MeshColors.linked else MeshColors.relay,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** "I'm coming" until we've answered, then "Found them"; and the finder. Not on our own SOS. */
@Composable
fun RescueActions(m: MessageEntity, responses: List<MessageEntity>, onRespond: (ResponseStatus) -> Unit, onFind: () -> Unit) {
    if (m.outgoing) return
    val mine = responses.lastOrNull { it.outgoing }
    val found = responses.any { it.responseStatus == ResponseStatus.FOUND.code }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            found -> Unit
            mine == null -> Button(
                onClick = { onRespond(ResponseStatus.ON_THE_WAY) },
                colors = ButtonDefaults.buttonColors(containerColor = MeshColors.sos, contentColor = Color.White),
            ) { Text("I'm coming") }
            else -> Button(
                onClick = { onRespond(ResponseStatus.FOUND) },
                colors = ButtonDefaults.buttonColors(containerColor = MeshColors.linked, contentColor = Color.White),
            ) { Text("Found them") }
        }
        OutlinedButton(onClick = onFind) { Text("Find") }
    }
}

/**
 * Responder view: every live SOS in one list, the most urgent and nearest on
 * top, with who's already going so two rescuers don't run to the same person.
 */
@Composable
fun RescueScreen(viewModel: MessagesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var findFor by rememberSaveable { mutableStateOf<Long?>(null) }
    // Ticks once a minute so "4 min ago" and the 6 h cut-off stay current on a quiet mesh.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val list = liveSos(state, now)
    val open = list.filter { !it.found }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { RescueSummary(open) }
        if (list.isEmpty()) {
            item {
                Text(
                    "No SOS right now. When someone sends one, it appears here, the most urgent and nearest first.",
                    Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        items(list, key = { it.message.id }) { sos ->
            RescueCard(sos, state, now, onRespond = { viewModel.respond(sos.message.id, it) }, onFind = { findFor = sos.message.id })
        }
    }

    findFor?.let { id ->
        list.firstOrNull { it.message.id == id }?.let { sos ->
            FinderSheet(sos.message, nameOf(sos.message, state), viewModel) { findFor = null }
        }
    }
}

private fun nameOf(m: MessageEntity, state: MessagesUiState): String =
    if (m.outgoing) "You" else m.senderName ?: state.names[NodeId(m.origin)] ?: NodeId(m.origin).toString()

@Composable
private fun RescueSummary(open: List<LiveSos>) {
    val people = open.sumOf { maxOf(it.message.people, 1) }
    val injured = open.count { it.message.injured }
    val covered = open.count { sos -> sos.responses.isNotEmpty() }
    Card(
        colors = CardDefaults.cardColors(containerColor = MeshColors.radar),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryStat("${open.size}", "open SOS", if (open.isEmpty()) MeshColors.linked else MeshColors.sos)
            SummaryStat("$people", "people", Color.White)
            SummaryStat("$injured", "injured", if (injured == 0) Color.White else MeshColors.sos)
            SummaryStat("$covered/${open.size}", "have help", MeshColors.relay)
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun RescueCard(sos: LiveSos, state: MessagesUiState, now: Long, onRespond: (ResponseStatus) -> Unit, onFind: () -> Unit) {
    val m = sos.message
    val name = nameOf(m, state)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (sos.found) MaterialTheme.colorScheme.surfaceContainer else MeshColors.sos.copy(alpha = 0.08f),
        ),
        border = BorderStroke(1.dp, if (sos.found) MeshColors.linked.copy(alpha = 0.5f) else MeshColors.sos.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!m.outgoing) Avatar(name, NodeId(m.origin), 28.dp)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            ageLabel(m.receivedAt, now),
                            if (m.outgoing) "your SOS" else hopsLabel(m.hops).lowercase(),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    sos.found -> Pill("FOUND", MeshColors.linked, filled = true)
                    sos.metres != null -> Pill("${formatDistance(sos.metres)} ${sos.direction}", MeshColors.mesh)
                    !m.outgoing && m.latE7 == null -> Pill("No GPS", MeshColors.idle)
                }
            }
            SosDetailsRow(m, Modifier.padding(top = 8.dp))
            Text(m.text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge)
            Responders(m, sos.responses, state)
            RescueActions(m, sos.responses, onRespond, onFind)
        }
    }
}

private fun ageLabel(at: Long, now: Long): String {
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        else -> "${minutes / 60} h ${minutes % 60} min ago"
    }
}

/**
 * The last 30 metres. GPS is ±50 m, useless for finding someone under rubble,
 * but this phone hears the victim's Bluetooth advert about once a second. Its
 * signal strength (RSSI) rises as you get closer, so walking and watching it
 * get "warmer" leads you in; then "Ring" makes their phone sound and flash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinderSheet(sos: MessageEntity, name: String, viewModel: MessagesViewModel, onDismiss: () -> Unit) {
    val target = NodeId(sos.origin)
    val sightings by viewModel.sightings.collectAsStateWithLifecycle()
    val sighting = sightings[target]
    var smoothed by remember { mutableStateOf<Double?>(null) }
    var history by remember { mutableStateOf(emptyList<Double>()) }
    LaunchedEffect(sighting?.seenAt) {
        val s = sighting ?: return@LaunchedEffect
        // One reading jumps by 10 dB as a body or door gets in the way; average them.
        val next = smoothed?.let { it * 0.6 + s.rssi * 0.4 } ?: s.rssi.toDouble()
        smoothed = next
        history = (history + next).takeLast(8)
    }
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    val rssi = smoothed?.takeIf { sighting != null && now - sighting.seenAt < STALE_MILLIS }
    val trend = if (history.size >= 4) history.last() - history[history.size - 4] else 0.0

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Find $name", style = MaterialTheme.typography.titleLarge)
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                SignalMeter(rssi)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rssi?.let { "${it.toInt()} dBm" } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (rssi != null) {
                        Text(
                            when {
                                trend > 2 -> "Warmer ↑"
                                trend < -2 -> "Colder ↓"
                                else -> "Steady"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = when {
                                trend > 2 -> MeshColors.linked
                                trend < -2 -> MeshColors.warning
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            Text(proximity(rssi), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            // Far away the signal says nothing about direction; GPS does. Up close it's the other way round.
            sos.location()?.let { there ->
                val state by viewModel.state.collectAsStateWithLifecycle()
                PointerArrow(state.myLocation, there)
            }
            Text(
                "Signal strength, not distance: walls and bodies weaken it. Walk a few steps at a time and follow it as it gets warmer.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { viewModel.ring(target, sos.id) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MeshColors.sos, contentColor = Color.White),
            ) { Text("Ring their phone") }
            Text(
                "Their phone plays an alarm and flashes its torch for 30 s. Only the phone that sent this SOS will ring.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val STALE_MILLIS = 8_000L

private fun proximity(rssi: Double?): String = when {
    rssi == null -> "Not in range. Move around to pick up their signal."
    rssi >= -55 -> "Right next to you"
    rssi >= -65 -> "Very close: a few metres"
    rssi >= -75 -> "Close: this room or the next"
    rssi >= -85 -> "Nearby: keep going"
    else -> "Far: at the edge of range"
}

/** A 270° gauge filling from cold (grey) to hot (green) between -100 and -45 dBm. */
@Composable
private fun SignalMeter(rssi: Double?) {
    val fraction = rssi?.let { ((it + 100) / 55).toFloat().coerceIn(0f, 1f) } ?: 0f
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = when {
        fraction > 0.75f -> MeshColors.linked
        fraction > 0.45f -> MeshColors.mesh
        fraction > 0.2f -> MeshColors.warning
        else -> MeshColors.idle
    }
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 16.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 135f, 270f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (fraction > 0f) {
            drawArc(color, 135f, 270f * fraction, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
