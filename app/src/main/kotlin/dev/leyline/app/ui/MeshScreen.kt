package dev.leyline.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leyline.app.mesh.Topology
import dev.leyline.app.mesh.TraceEntry
import dev.leyline.app.mesh.TraceKind
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Rejection
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private enum class MeshView(val label: String) { HOPS("Hops"), COMPASS("Compass"), MAP("Map") }

@Composable
fun MeshScreen(viewModel: MeshViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(MeshView.HOPS) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MeshColors.radar),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Live mesh", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        val located = state.topology.positions.size - (if (state.topology.self in state.topology.positions) 1 else 0)
                        Text(
                            when {
                                view == MeshView.HOPS -> "${state.topology.direct.size} linked · ${state.topology.hops.size} known · rings are hops"
                                state.topology.self !in state.topology.positions -> "Waiting for your GPS fix · turn location on"
                                else -> "$located with GPS · real direction and distance, north up"
                            },
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (state.carryingSos) Pill("SOS BEACON", MeshColors.sos, filled = true)
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MeshView.entries.forEach {
                        Pill(it.label, if (it == view) MeshColors.mesh else Color.White.copy(alpha = 0.5f), Modifier.clickable { view = it }.padding(vertical = 4.dp), filled = it == view)
                    }
                }
                if (view == MeshView.MAP) {
                    OfflineMap(state, Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 8.dp))
                } else {
                    MeshMap(state, compass = view == MeshView.COMPASS, Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend(MeshColors.linked, "Linked")
                    Legend(MeshColors.relay, "Via relays")
                    Legend(MeshColors.idle, "In range")
                    Legend(MeshColors.sos, "SOS")
                }
            }
        }

        SectionLabel("Nearby phones")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                if (state.sightings.isEmpty()) {
                    Text(
                        "No Leyline phones in range yet",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.sightings.values.sortedByDescending { it.rssi }.forEach { sighting ->
                    val id = sighting.info.node
                    val name = state.topology.names[id] ?: id.toString()
                    val linked = id in state.topology.direct
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name, id, 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text("${sighting.rssi} dBm · $id", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (sighting.info.carryingSos) Pill("SOS", MeshColors.sos, filled = true, modifier = Modifier.padding(end = 6.dp))
                        Pill(if (linked) "Linked" else "Seen", if (linked) MeshColors.linked else MeshColors.idle, modifier = Modifier.padding(end = 8.dp))
                        SignalBars(sighting.rssi)
                    }
                }
            }
        }

        SectionLabel("Results")
        val r = state.results
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "Delivered",
                if (r.asked == 0) "—" else "${100 * r.confirmed / r.asked}% · ${r.confirmed}/${r.asked}",
                Modifier.weight(1f),
                good = if (r.asked == 0) null else r.confirmed == r.asked,
            )
            StatTile("Avg hops in", r.averageHops?.let { "%.1f".format(Locale.US, it) } ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Round trip", r.medianRoundTripMillis?.let(::formatDuration) ?: "—", Modifier.weight(1f))
            StatTile(
                "Battery",
                r.drainPerHour?.let { "%.1f%%/h".format(Locale.US, it.coerceAtLeast(0.0)) } ?: if (state.battery.charging) "Charging" else "Measuring…",
                Modifier.weight(1f),
            )
        }
        Text(
            "Delivered: SOS and private messages with a receipt back. Round trip: median, send to receipt, timed on this phone alone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val s = state.stats
        Collapsible("Security", if (s.rejected == 0) "nothing blocked" else "${s.rejected} fakes blocked") {
            SecurityCard(state)
        }
        Collapsible("Packet log", state.trace.firstOrNull()?.let { "last ${formatClock(it.at).take(8)}" } ?: "quiet") {
            PacketLog(state)
        }
        Collapsible("Radio and battery", "${if (state.radio.bluetoothOn) "Bluetooth on" else "Bluetooth off"} · ${state.battery.percent}%") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val r = state.radio
                StatTile("Bluetooth", if (r.bluetoothOn) "On" else "Off", Modifier.weight(1f), good = r.bluetoothOn)
                StatTile("Advertising", if (r.advertising) "On" else "Off", Modifier.weight(1f), good = r.advertising)
                StatTile("Scanning", if (r.scanning) "On" else "Off", Modifier.weight(1f), good = r.scanning)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Battery",
                    "${state.battery.percent}%${if (state.battery.charging) " ⚡" else ""}",
                    Modifier.weight(1f),
                    good = !state.battery.low,
                )
                StatTile(
                    "Relay",
                    when {
                        state.relaying -> "On"
                        state.battery.low -> "Saving"
                        else -> "Off"
                    },
                    Modifier.weight(1f),
                    good = state.relaying,
                )
                StatTile("Links", "${state.radio.links}", Modifier.weight(1f))
            }
        }
        Collapsible("Traffic", "${s.originated} sent · ${s.relayed} relayed") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Sent", "${s.originated}", Modifier.weight(1f))
                StatTile("Delivered", "${s.delivered}", Modifier.weight(1f))
                StatTile("Relayed", "${s.relayed}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Radio writes", "${s.sent}", Modifier.weight(1f))
                StatTile("Receipts", "${s.receipts}", Modifier.weight(1f))
                StatTile("Upgrades", "${s.upgrades}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Duplicates", "${s.duplicates}", Modifier.weight(1f))
                StatTile("Write fails", "${s.sendFailures}", Modifier.weight(1f), good = s.sendFailures == 0)
                StatTile("Malformed", "${s.malformed}", Modifier.weight(1f), good = s.malformed == 0)
            }
        }
        Collapsible("Range test", "signal against distance") {
            RangeTest(state, viewModel::freshLocation)
        }
        Spacer(Modifier.padding(4.dp))
    }
}

/** Every packet is signed; this lists the ones that failed the check and were dropped. */
@Composable
private fun SecurityCard(state: MeshUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Signed messages only", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Each phone signs what it sends with a key locked in its secure hardware. Forged or altered messages are dropped here and never passed on.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${state.stats.rejected}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (state.stats.rejected == 0) MeshColors.linked else MeshColors.sos,
                    )
                    Text("blocked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.blocked.take(5).forEach { b ->
                val claimed = state.topology.names[b.claimedOrigin] ?: b.claimedOrigin.toString()
                val via = state.topology.names[b.from] ?: b.from.toString()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.Top) {
                    Pill(if (b.sos) "FAKE SOS" else "FAKE", MeshColors.sos, filled = b.sos)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Claimed to be $claimed · ${rejectionLabel(b.reason)}", style = MaterialTheme.typography.titleSmall)
                        b.text?.let {
                            Text("“$it”", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        Text(
                            "Handed over by $via at ${formatTime(b.at)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Demo mode: every routing decision as it happens, newest first. */
@Composable
private fun PacketLog(state: MeshUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            if (state.trace.isEmpty()) {
                Text(
                    "Nothing yet. Send a message and watch each phone pass it on.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.trace.take(LOG_ROWS).forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 34.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val (title, detail) = describe(entry, state.topology)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Canvas(Modifier.padding(top = 5.dp).size(10.dp)) { drawCircle(traceColor(entry)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        entry.text?.let {
                            Text("“$it”", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatClock(entry.at),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One log line: what happened, then the numbers behind it. */
private fun describe(e: TraceEntry, topology: Topology): Pair<String, String> {
    fun name(id: NodeId) = if (id == topology.self) "you" else topology.names[id] ?: id.toString().takeLast(4)
    val what = when {
        e.receipt -> "receipt"
        e.sos -> "SOS"
        else -> "message"
    }
    val whose = if (e.origin == topology.self) "your" else "${name(e.origin)}'s"
    val hops = if (e.hops <= 1) "1 hop" else "${e.hops} hops"
    return when (e.kind) {
        TraceKind.RECEIVED -> {
            val relays = e.route.drop(1).dropLast(1)
            "Got ${name(e.origin)}'s $what" to listOf(
                if (relays.isEmpty()) "direct" else "via ${relays.joinToString(" → ") { name(it) }}",
                hops,
                if (e.ttl <= 1) "TTL spent, stops here" else "TTL ${e.ttl}",
                "signature ✓",
            ).joinToString(" · ")
        }
        TraceKind.SENT -> "Sent $whose $what to ${name(e.peer)}" to "TTL ${e.ttl}"
        TraceKind.RELAYED ->
            "Relayed $whose $what to ${name(e.peer)}" to "TTL ${e.ttl} left · ${e.route.joinToString(" → ") { name(it) }}"
        TraceKind.CARRIED ->
            "Carried $whose $what for ${formatDuration(e.millis ?: 0)}" to
                "Handed to ${name(e.peer)} when it came in range · store-and-forward"
        TraceKind.DUPLICATE -> "Ignored a copy from ${name(e.peer)}" to "Already had $whose $what · not sent on again"
        TraceKind.ECHO -> "Your $what came back from ${name(e.peer)}" to "Dropped: we sent it"
        TraceKind.LOOP -> "Dropped a looping copy from ${name(e.peer)}" to "We're already on its path"
        TraceKind.RECEIPT -> "${name(e.peer)} got your $what" to
            listOfNotNull("$hops away", e.millis?.let { "${formatDuration(it)} round trip" }).joinToString(" · ")
        TraceKind.BLOCKED -> "Blocked a fake $what claiming to be ${name(e.origin)}" to
            "From ${name(e.peer)} · ${rejectionLabel(e.rejection ?: Rejection.BAD_SIGNATURE)}"
    }
}

private fun traceColor(e: TraceEntry): Color = when (e.kind) {
    TraceKind.BLOCKED -> MeshColors.sos
    TraceKind.DUPLICATE, TraceKind.ECHO, TraceKind.LOOP -> MeshColors.idle
    TraceKind.RECEIPT -> MeshColors.linked
    TraceKind.CARRIED -> MeshColors.warning
    else -> when {
        e.receipt -> MeshColors.linked
        e.sos -> MeshColors.sos
        else -> MeshColors.mesh
    }
}

private const val LOG_ROWS = 15
/** Compass radar sizes: the smallest that fits the farthest phone. */
private val COMPASS_SCALES = listOf(30.0, 60.0, 150.0, 300.0, 600.0, 1_500.0, 3_000.0, 6_000.0)

private const val TRAIL_HOP_MS = 600f
private const val TRAIL_FADE_MS = 700f

private fun rejectionLabel(reason: Rejection) = when (reason) {
    Rejection.WRONG_ORIGIN -> "wrong signer"
    Rejection.BAD_SIGNATURE -> "altered on the way"
    Rejection.UNSIGNED -> "not signed"
    Rejection.IMPOSTOR -> "key doesn't match"
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, good: Boolean? = null) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = when (good) {
                    true -> MeshColors.linked
                    false -> MeshColors.warning
                    null -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.padding(end = 4.dp).width(8.dp).aspectRatio(1f)) { drawCircle(color) }
        Text(label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Radar view: we sit at the centre. In hop mode rings are hop distance and angle is
 * spread evenly per ring; in compass mode phones sit at their real GPS direction and distance. Links between far nodes come from packet paths, so this
 * shows topology no single phone can scan.
 */
@Composable
private fun MeshMap(state: MeshUiState, compass: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val topology = state.topology
    val sosNodes = state.sightings.values.filter { it.info.carryingSos }.map { it.info.node }.toSet()
    val sweep by rememberInfiniteTransition(label = "radar").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val outer = min(size.width, size.height) / 2 * 0.86f
        val positions = HashMap<NodeId, Offset>()
        positions[topology.self] = center
        val me = topology.positions[topology.self]

        if (compass && me != null) {
            // Real geography, north up: each phone at its true direction and distance from us.
            val offsets = topology.positions.filterKeys { it != topology.self }.mapValues { eastNorthMetres(me, it.value) }
            val farthest = offsets.values.maxOfOrNull { hypot(it.first, it.second) } ?: 0.0
            val scale = COMPASS_SCALES.firstOrNull { it >= farthest } ?: farthest
            for (ring in 1..3) {
                val radius = outer * ring / 3
                drawCircle(Color.White.copy(alpha = 0.10f), radius = radius, center = center, style = Stroke(1.dp.toPx()))
                val label = measurer.measure(formatDistance(scale * ring / 3), TextStyle(fontSize = 9.sp, color = Color.White.copy(alpha = 0.45f)))
                drawText(label, topLeft = center + Offset(4.dp.toPx(), -radius))
            }
            val north = measurer.measure("N", TextStyle(fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold))
            drawText(north, topLeft = Offset(center.x - north.size.width / 2f, center.y - outer - north.size.height - 2.dp.toPx()))
            offsets.forEach { (node, en) ->
                positions[node] = center + Offset((en.first / scale * outer).toFloat(), (-en.second / scale * outer).toFloat())
            }
        } else {
            val maxHops = (topology.hops.values.maxOrNull() ?: 1).coerceIn(1, 4)
            val ringStep = outer / maxHops
            for (ring in 1..maxHops) {
                drawCircle(Color.White.copy(alpha = 0.10f), radius = ringStep * ring, center = center, style = Stroke(1.dp.toPx()))
            }
            // Every node we know, plus in-range phones not yet linked, on ring 1.
            val hops = topology.hops + state.sightings.keys.filter { it !in topology.hops }.associateWith { 1 }
            hops.entries.groupBy { it.value.coerceAtMost(maxHops) }.forEach { (ring, nodes) ->
                nodes.sortedBy { it.key.value }.forEachIndexed { index, (node, _) ->
                    val angle = 2 * PI * index / nodes.size + ring * 0.6 - PI / 2
                    positions[node] = center + Offset((cos(angle) * ringStep * ring).toFloat(), (sin(angle) * ringStep * ring).toFloat())
                }
            }
        }
        // Expanding pulse: we're advertising.
        drawCircle(MeshColors.mesh.copy(alpha = 0.25f * (1 - sweep)), radius = outer * sweep, center = center, style = Stroke(2.dp.toPx()))

        topology.edges.keys.forEach { edge ->
            val a = positions[edge.a] ?: return@forEach
            val b = positions[edge.b] ?: return@forEach
            val live = (edge.a == topology.self && edge.b in topology.direct) || (edge.b == topology.self && edge.a in topology.direct)
            val color = if (live) MeshColors.linked else MeshColors.relay
            drawLine(color.copy(alpha = 0.25f), a, b, strokeWidth = if (live) 9.dp.toPx() else 6.dp.toPx())
            drawLine(color, a, b, strokeWidth = if (live) 2.5.dp.toPx() else 1.5.dp.toPx())
        }
        state.sightings.keys.filter { it !in topology.direct }.forEach { node ->
            val p = positions[node] ?: return@forEach
            drawLine(MeshColors.idle.copy(alpha = 0.7f), center, p, strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        }

        positions.forEach { (node, p) ->
            val self = node == topology.self
            val color = when {
                node in sosNodes -> MeshColors.sos
                self -> MeshColors.mesh
                node in topology.direct -> MeshColors.linked
                node in state.sightings -> MeshColors.idle
                else -> MeshColors.relay
            }
            val radius = if (self) 11.dp.toPx() else 8.dp.toPx()
            drawCircle(color.copy(alpha = 0.25f), radius = radius * 1.9f, center = p)
            drawCircle(color, radius = radius, center = p)
            val label = if (self) "You" else topology.names[node] ?: node.toString().takeLast(4)
            val text = measurer.measure(label, TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium))
            drawText(text, topLeft = p + Offset(-text.size.width / 2f, radius + 6.dp.toPx()))
        }

        // Packets in flight: each new log entry runs along its route, one hop per
        // TRAIL_HOP_MS, then fades. The radar sweep redraws every frame, so reading
        // the clock here is all it takes to animate.
        val now = System.currentTimeMillis()
        state.trace.takeWhile { now - it.at < 10_000 }.forEach { e ->
            val points = e.route.mapNotNull { positions[it] }
            if (points.size < 2) return@forEach
            val hops = points.size - 1
            val progress = (now - e.at) / TRAIL_HOP_MS
            val fade = ((progress - hops) * TRAIL_HOP_MS / TRAIL_FADE_MS).coerceIn(0f, 1f)
            if (progress < 0 || fade >= 1f) return@forEach
            val color = traceColor(e).copy(alpha = 1 - fade)
            for (i in 0 until hops) {
                val t = (progress - i).coerceIn(0f, 1f)
                if (t <= 0f) break
                val head = lerp(points[i], points[i + 1], t)
                drawLine(color.copy(alpha = color.alpha * 0.3f), points[i], head, strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, points[i], head, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                if (t < 1f) drawCircle(color, radius = 6.dp.toPx(), center = head)
            }
            // A forgery is stopped at the first phone it reaches: cross it out there.
            if (e.kind == TraceKind.BLOCKED && progress >= hops) {
                val p = points.last()
                val r = 10.dp.toPx()
                drawLine(color, p + Offset(-r, -r), p + Offset(r, r), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, p + Offset(-r, r), p + Offset(r, -r), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
