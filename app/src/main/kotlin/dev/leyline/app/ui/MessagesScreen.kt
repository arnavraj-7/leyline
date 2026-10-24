package dev.leyline.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leyline.app.data.MessageWithReceipts
import dev.leyline.mesh.Location
import dev.leyline.mesh.MessageBody
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.ResponseStatus
import dev.leyline.mesh.SosKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessagesScreen(viewModel: MessagesViewModel, openSos: Boolean, onSosHandled: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val replyTo by viewModel.replyTo.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var routeFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var findFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val sosDraft by viewModel.sosDraft.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(openSos, state.running) {
        // Opened from the Quick Settings tile: send as soon as the mesh is up.
        // Default text, not the half-typed chat draft.
        if (openSos && state.running) {
            viewModel.send("", sos = true) {}
            onSosHandled()
        }
    }
    LaunchedEffect(notice) {
        notice?.let {
            viewModel.dismissNotice()
            snackbar.showSnackbar(it)
        }
    }
    // New message at the bottom: follow it. The list is newest-first, reversed.
    LaunchedEffect(state.messages.firstOrNull()?.message?.id) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            MeshStatusStrip(state)
            if (state.ringing) RingingBanner(onStop = viewModel::stopRinging)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (state.messages.isEmpty()) item { EmptyState(state.running) }
                items(state.messages, key = { it.message.id }) { item ->
                    MessageItem(
                        item,
                        state,
                        onReply = viewModel::replyPrivately,
                        onOpen = { routeFor = it.message.id },
                        onRespond = viewModel::respond,
                        onFind = { findFor = it },
                    )
                }
            }
            // Keyboard open: give the conversation the room; SOS comes back when it closes.
            AnimatedVisibility(visible = !WindowInsets.isImeVisible) {
                Column {
                    SosDetailsPicker(
                        sosDraft,
                        onKind = viewModel::setSosKind,
                        onPeople = viewModel::setSosPeople,
                        onInjured = viewModel::toggleInjured,
                    )
                    SosButton(enabled = state.running) { viewModel.send(draft, sos = true) { if (it) draft = "" } }
                }
            }
            Composer(
                draft = draft,
                onDraft = { draft = it },
                replyTo = replyTo?.let { state.names[it] ?: it.toString() },
                onCancelReply = { viewModel.replyPrivately(null) },
                enabled = state.running,
                onSend = { viewModel.send(draft, sos = false) { if (it) draft = "" } },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 52.dp))
    }
    routeFor?.let { id ->
        state.messages.firstOrNull { it.message.id == id }?.let { RouteSheet(it, state) { routeFor = null } }
    }
    findFor?.let { id ->
        state.messages.firstOrNull { it.message.id == id }?.message?.let { m ->
            FinderSheet(m, m.senderName ?: state.names[NodeId(m.origin)] ?: NodeId(m.origin).toString(), viewModel) { findFor = null }
        }
    }
}

/** A rescuer set this phone ringing; the person may want it quiet once they've been found. */
@Composable
private fun RingingBanner(onStop: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MeshColors.sos, contentColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("A rescuer is ringing your phone to find you", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onStop) { Text("Stop", color = Color.White) }
        }
    }
}

/**
 * Optional triage before holding SOS: what's wrong, how many, anyone hurt. It
 * never gets in the way: holding the button without touching these still sends.
 */
@Composable
private fun SosDetailsPicker(draft: SosDraft, onKind: (SosKind) -> Unit, onPeople: (Int) -> Unit, onInjured: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(SosKind.MEDICAL, SosKind.TRAPPED, SosKind.FIRE, SosKind.SUPPLIES).forEach { kind ->
            FilterChip(selected = draft.kind == kind, onClick = { onKind(kind) }, label = { Text(kindLabel(kind)) })
        }
        FilterChip(selected = draft.injured, onClick = onInjured, label = { Text("Injured") })
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onPeople(draft.people - 1) }, enabled = draft.people > 0) { Text("−") }
                Text(if (draft.people == 0) "People" else "${draft.people} people", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { onPeople(draft.people + 1) }) { Text("+") }
            }
        }
    }
}

@Composable
private fun MeshStatusStrip(state: MessagesUiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(start = 2.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(MeshColors.linked, live = state.linked > 0, size = 8.dp)
                Text(
                    when {
                        !state.running -> "Mesh off"
                        state.linked == 0 -> "Searching · ${state.nearby} nearby"
                        else -> "${state.linked} linked · ${state.nearby} nearby"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Pill(if (state.relaying) "Relay on" else "Relay off", if (state.relaying) MeshColors.mesh else MeshColors.idle)
        if (state.carrying > 0) Pill("Carrying ${state.carrying}", MeshColors.relay)
    }
}

@Composable
private fun MessageItem(
    item: MessageWithReceipts,
    state: MessagesUiState,
    onReply: (NodeId) -> Unit,
    onOpen: (MessageWithReceipts) -> Unit,
    onRespond: (Long, ResponseStatus) -> Unit,
    onFind: (Long) -> Unit,
) {
    when {
        item.message.sos -> SosCard(
            item,
            state,
            onOpen = { onOpen(item) },
            onRespond = { onRespond(item.message.id, it) },
            onFind = { onFind(item.message.id) },
        )
        item.message.outgoing -> OutgoingBubble(item, state) { onOpen(item) }
        else -> IncomingBubble(item, state, onReply) { onOpen(item) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IncomingBubble(item: MessageWithReceipts, state: MessagesUiState, onReply: (NodeId) -> Unit, onOpen: () -> Unit) {
    val m = item.message
    val origin = NodeId(m.origin)
    val name = m.senderName ?: state.names[origin] ?: origin.toString()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Avatar(name, origin, 34.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 290.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = MaterialTheme.typography.labelLarge)
                Pill(hopsLabel(m.hops), MeshColors.mesh)
                if (m.destination != NodeId.BROADCAST.value) {
                    Pill("Private", MeshColors.relay)
                    Pill(if (m.encrypted) "\uD83D\uDD12 Encrypted" else "Not encrypted", if (m.encrypted) MeshColors.linked else MeshColors.warning)
                }
            }
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = { onReply(origin) }),
            ) {
                Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                metaLine(item, state.names),
                Modifier.padding(start = 4.dp, top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutgoingBubble(item: MessageWithReceipts, state: MessagesUiState, onOpen: () -> Unit) {
    val m = item.message
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (m.destination != NodeId.BROADCAST.value) {
            val to = NodeId(m.destination)
            Text(
                "Private to ${state.names[to] ?: to} \u00b7 " +
                    if (m.encrypted) "\uD83D\uDD12 encrypted" else "not encrypted: they haven't sent you a message yet",
                Modifier.padding(end = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp)).clickable(onClick = onOpen),
        ) {
            Text(
                m.text,
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        val (status, delivered) = deliveryLine(item, state)
        Text(
            "${formatTime(m.receivedAt)} · $status",
            Modifier.padding(end = 4.dp, top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (delivered) MeshColors.linked else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SosCard(
    item: MessageWithReceipts,
    state: MessagesUiState,
    onOpen: () -> Unit,
    onRespond: (ResponseStatus) -> Unit,
    onFind: () -> Unit,
) {
    val responses = state.responses[item.message.id].orEmpty()
    val m = item.message
    val context = LocalContext.current
    val origin = NodeId(m.origin)
    val name = if (m.outgoing) "You" else m.senderName ?: state.names[origin] ?: origin.toString()
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MeshColors.sos.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, MeshColors.sos.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("SOS", MeshColors.sos, filled = true)
                if (!m.outgoing) Avatar(name, origin, 24.dp)
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!m.outgoing) Pill(hopsLabel(m.hops), MeshColors.mesh)
            }
            SosDetailsRow(m, Modifier.padding(top = 10.dp))
            Text(m.text, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
            if (!m.outgoing) {
                // Unsigned or forged packets never reach the database, so everything shown here passed.
                Text(
                    "✓ Signature verified · node $origin",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshColors.linked,
                )
            }
            if (m.latE7 != null && m.lonE7 != null) {
                val there = Location(m.latE7, m.lonE7, m.accuracyMeters ?: 0)
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MeshColors.sos)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            val mine = state.myLocation
                            if (!m.outgoing && mine != null) {
                                val (metres, direction) = distanceAndBearing(mine, there)
                                Text("≈ ${formatDistance(metres)} $direction of you", style = MaterialTheme.typography.labelLarge)
                            }
                            Text(
                                "%.5f, %.5f · ±%d m".format(Locale.US, there.latitude, there.longitude, there.accuracyMeters),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            val uri = "geo:${there.latitude},${there.longitude}?q=${there.latitude},${there.longitude}(${Uri.encode(name)})".toUri()
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        }) { Text("Map") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (m.outgoing) {
                val (status, delivered) = deliveryLine(item, state)
                Text(
                    "${formatTime(m.receivedAt)} · $status",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (delivered) MeshColors.linked else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(metaLine(item, state.names), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Responders(m, responses, state)
            RescueActions(m, responses, onRespond, onFind)
        }
    }
}

/** Relay ids stored with a message, in the order it crossed them. */
private fun relaysOf(path: String): List<NodeId> =
    path.split(',').filter { it.isNotBlank() }.map { NodeId(it.toLong(16).toInt()) }

private fun metaLine(item: MessageWithReceipts, names: Map<NodeId, String>): String {
    val m = item.message
    val via = relaysOf(m.path).map { names[it] ?: it.toString().takeLast(4) }
    return if (via.isEmpty()) formatTime(m.receivedAt) else "${formatTime(m.receivedAt)} · via ${via.joinToString(" → ")}"
}

/**
 * Tap a message: the way it travelled. Incoming, the chain of phones it crossed.
 * Ours, who confirmed it and the round trip, which starts and ends on our clock
 * and so is exact even though phones in a blackout disagree about the time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteSheet(item: MessageWithReceipts, state: MessagesUiState, onDismiss: () -> Unit) {
    val m = item.message
    fun name(id: NodeId) = if (id == state.self) "You" else state.names[id] ?: m.senderName?.takeIf { id.value == m.origin } ?: id.toString().takeLast(4)
    val kind = if (m.sos) "SOS" else "message"
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (m.outgoing) "Where your $kind went" else "How this $kind got here", style = MaterialTheme.typography.titleLarge)
            if (!m.outgoing) {
                RouteChain((listOf(NodeId(m.origin)) + relaysOf(m.path) + state.self).map { it to name(it) })
                Fact("Hops", hopsLabel(m.hops))
                Fact("Signed by", "node ${NodeId(m.origin)} \u00b7 verified")
                Fact("Sent", "${formatClock(m.createdAt)} (their clock)")
                Fact("Arrived", formatClock(m.receivedAt))
                Text(
                    "Phones without internet can't sync clocks, so sent and arrived can disagree by seconds. Round trips on your own messages are exact.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Fact("Sent", formatClock(m.createdAt))
                Fact("To", if (m.destination == NodeId.BROADCAST.value) "Everyone in range" else name(NodeId(m.destination)))
                if (item.receipts.isEmpty()) {
                    Fact("Status", deliveryLine(item, state).first)
                    if (!m.sos && m.destination == NodeId.BROADCAST.value) {
                        Text(
                            "Messages to everyone don't ask for receipts: with every phone answering, the replies would crowd out real traffic. SOS and private messages do.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text("Confirmed by", style = MaterialTheme.typography.titleSmall)
                    item.receipts.sortedBy { it.hops }.forEach { r ->
                        val id = NodeId(r.node)
                        val who = r.name ?: name(id)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(who, id, 30.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(who, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${hopsLabel(r.hops)} \u00b7 ${formatDuration(r.receivedAt - m.createdAt)} round trip",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteChain(nodes: List<Pair<NodeId, String>>) {
    FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nodes.forEachIndexed { index, (id, name) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    Text("\u2192", Modifier.padding(horizontal = 6.dp), color = MeshColors.mesh, style = MaterialTheme.typography.titleMedium)
                }
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name, id, 24.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(name, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What we know about where our message got to, and whether that counts as delivered. */
private fun deliveryLine(item: MessageWithReceipts, state: MessagesUiState): Pair<String, Boolean> {
    val receipts = item.receipts
    if (receipts.isNotEmpty()) {
        val who = receipts.sortedBy { it.hops }.joinToString(", ") {
            val id = NodeId(it.node)
            "${it.name ?: state.names[id] ?: id.toString().takeLast(4)} (${hopsLabel(it.hops).lowercase()})"
        }
        return "✓ Received by $who" to true
    }
    val handed = state.handedTo[item.message.id] ?: 0
    return when {
        handed > 0 && (item.message.sos || item.message.destination != NodeId.BROADCAST.value) ->
            "Handed to $handed ${if (handed == 1) "phone" else "phones"} · waiting for receipt" to false
        handed > 0 -> "✓ Handed to $handed ${if (handed == 1) "phone" else "phones"}" to true
        else -> "Waiting for a phone in range" to false
    }
}

/**
 * Press and hold for 1.2 s. A tap would be too easy to trigger in a pocket, and
 * an SOS floods every phone in range, so it's worth a deliberate gesture.
 */
@Composable
private fun SosButton(enabled: Boolean, onSos: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var job by remember { mutableStateOf<Job?>(null) }
    val gradient = if (enabled) {
        Brush.horizontalGradient(listOf(Color(0xFFE11D48), Color(0xFFF43F5E), Color(0xFFFB7185)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF64748B), Color(0xFF94A3B8)))
    }

    Box(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(gradient)
            .drawBehind { drawRect(Color.White.copy(alpha = 0.28f), size = size.copy(width = size.width * progress.value)) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    job = scope.launch {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        progress.animateTo(1f, tween(HOLD_MILLIS, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSos()
                    }
                    tryAwaitRelease()
                    job?.cancel()
                    scope.launch { progress.animateTo(0f, tween(250)) }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (progress.value > 0f) "Keep holding…" else "Hold to send SOS",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text("with your GPS · reaches every phone in range", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyState(running: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LeylineMark(64.dp)
        Text(if (running) "You're on the mesh" else "The mesh is off", style = MaterialTheme.typography.titleLarge)
        Text(
            if (running) {
                "No internet, no SIM needed. Leyline phones within about 30 m link up by themselves, and each one " +
                    "carries messages on to the next."
            } else {
                "Turn it on in Settings to start relaying."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    replyTo: String?,
    onCancelReply: () -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
        if (replyTo != null) {
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Private", MeshColors.relay)
                Text(" to $replyTo", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onCancelReply) { Icon(Icons.Filled.Close, contentDescription = "Send to everyone instead") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = draft,
                onValueChange = { if (it.length <= MAX_CHAT_CHARS) onDraft(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (replyTo != null) "Message $replyTo" else "Message everyone nearby") },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                supportingText = if (draft.length > MessageBody.SOS_MAX_CHARS) {
                    { Text("Over ${MessageBody.SOS_MAX_CHARS}: fine as chat, too long for SOS") }
                } else {
                    null
                },
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onSend, enabled = enabled && draft.isNotBlank(), modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

private const val HOLD_MILLIS = 1_200
private const val MAX_CHAT_CHARS = 500
