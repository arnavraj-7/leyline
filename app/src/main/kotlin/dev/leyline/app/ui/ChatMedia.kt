package dev.leyline.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.leyline.app.data.MessageEntity
import dev.leyline.mesh.MediaKind
import dev.leyline.mesh.NodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A photo or voice clip inside a chat bubble. */
@Composable
fun MediaBody(m: MessageEntity, file: File, color: Color) {
    when (m.mediaKind) {
        MediaKind.PHOTO.code -> {
            val image = remember(file) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
            if (image != null) {
                Image(
                    image,
                    contentDescription = "Photo",
                    modifier = Modifier.padding(4.dp).width(230.dp).clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.FillWidth,
                )
            } else {
                Text("Photo unavailable", Modifier.padding(14.dp), color = color)
            }
        }
        MediaKind.VOICE.code -> VoicePlayer(file, color)
    }
}

@Composable
private fun VoicePlayer(file: File, color: Color) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val seconds = remember(file) {
        runCatching {
            MediaMetadataRetriever().run {
                setDataSource(file.path)
                (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) / 1_000
            }
        }.getOrDefault(0)
    }
    DisposableEffect(file) { onDispose { player?.release() } }
    Row(
        Modifier
            .clickable {
                val current = player
                if (current != null) {
                    current.release()
                    player = null
                } else {
                    player = runCatching {
                        MediaPlayer().apply {
                            setDataSource(file.path)
                            setOnCompletionListener {
                                it.release()
                                player = null
                            }
                            prepare()
                            start()
                        }
                    }.getOrNull()
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (player != null) "■" else "▶", color = color, style = MaterialTheme.typography.titleMedium)
        Text("  Voice message · ${seconds.coerceAtLeast(1)} s", color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Who a chat message goes to: everyone, someone the mesh has seen, or anyone by
 * their Leyline ID. An ID works even for a phone we've never met: the message
 * floods the mesh and waits in phones' outboxes until it reaches them, maybe in
 * another group, carried by someone walking between the two.
 */
@Composable
fun RecipientPicker(names: Map<NodeId, String>, self: NodeId, to: NodeId?, onPick: (NodeId?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var askId by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(RoundedCornerShape(50)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("To: ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                to?.let { "${names[it] ?: it} · private" } ?: "Everyone nearby",
                style = MaterialTheme.typography.labelLarge,
                color = if (to != null) MeshColors.relay else MaterialTheme.colorScheme.primary,
            )
            Text(" ▾", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Everyone nearby") }, onClick = { onPick(null); open = false })
            names.filterKeys { it != self }.entries.sortedBy { it.value.lowercase() }.forEach { (id, name) ->
                DropdownMenuItem(text = { Text("$name  ·  $id") }, onClick = { onPick(id); open = false })
            }
            DropdownMenuItem(text = { Text("Someone not here: enter their ID…") }, onClick = { askId = true; open = false })
        }
    }
    if (askId) {
        var text by remember { mutableStateOf("") }
        val id = parseLeylineId(text)
        AlertDialog(
            onDismissRequest = { askId = false },
            title = { Text("Message by Leyline ID") },
            text = {
                OutlinedTextField(
                    text,
                    { text = it },
                    label = { Text("ID, e.g. Ravi#3F2A91C0") },
                    singleLine = true,
                    supportingText = { Text("They find theirs in Settings. It reaches them even if they're in another group, once someone carries it across.") },
                )
            },
            confirmButton = { TextButton(onClick = { onPick(id); askId = false }, enabled = id != null) { Text("Message them") } },
            dismissButton = { TextButton(onClick = { askId = false }) { Text("Cancel") } },
        )
    }
}

/** "Ravi#3F2A91C0", "#3f2a91c0" or "3F2A91C0": the 8 hex digits after the last '#'. */
fun parseLeylineId(text: String): NodeId? {
    val hex = text.trim().substringAfterLast('#')
    if (hex.length != 8) return null
    return hex.toLongOrNull(16)?.let { NodeId(it.toInt()) }?.takeIf { it != NodeId.BROADCAST }
}

/** Camera or gallery photo, and hold-to-talk voice, next to the text field. */
@Composable
fun AttachButtons(enabled: Boolean, onPhoto: (ByteArray) -> Unit, onVoice: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    val recorder = remember { VoiceRecorder(context) }
    val capture = remember { File(context.filesDir, "capture/photo.jpg") }

    fun send(uri: Uri?) {
        uri ?: return
        scope.launch { withContext(Dispatchers.IO) { compressPhoto(context, uri) }?.let(onPhoto) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) send(captureUri(context, capture)) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { send(it) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(recording) {
        elapsed = 0
        while (recording) {
            delay(1_000)
            elapsed++
        }
    }

    Box {
        TextButton(onClick = { menu = true }, enabled = enabled) { Text("📷", style = MaterialTheme.typography.titleLarge) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Take photo") }, onClick = {
                menu = false
                capture.parentFile?.mkdirs()
                camera.launch(captureUri(context, capture))
            })
            DropdownMenuItem(text = { Text("Choose photo") }, onClick = {
                menu = false
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
        }
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (recording) MeshColors.sos else Color.Transparent)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        return@detectTapGestures
                    }
                    if (!recorder.start()) return@detectTapGestures
                    recording = true
                    tryAwaitRelease()
                    recording = false
                    recorder.stop()?.let(onVoice)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (recording) "${elapsed}s" else "🎙️", style = if (recording) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge, color = if (recording) Color.White else Color.Unspecified)
    }
}

private fun captureUri(context: android.content.Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
