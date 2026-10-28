package dev.leyline.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A photo small enough for the mesh: at most [MAX_SIDE] px, JPEG quality lowered
 * until it is under [PHOTO_BUDGET] bytes (about 25 packets). Enough to show an
 * injury, a blocked road or a face; a full camera photo would be 3 MB.
 */
fun compressPhoto(context: Context, uri: Uri): ByteArray? = runCatching {
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder applies the camera's rotation, so photos aren't sideways.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale < 1) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
    }
    var quality = 60
    var bytes: ByteArray
    do {
        bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        quality -= 10
    } while (bytes.size > PHOTO_BUDGET && quality >= 15)
    bytes
}.getOrNull()

/**
 * Push-to-talk recording: Opus at 12 kbps in Ogg on Android 10+ (about 1.5 KB a
 * second), AMR narrowband below that. Speech stays clear; at most [MAX_VOICE_MILLIS].
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(): Boolean = runCatching {
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.audio")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioEncodingBitRate(12_000)
            r.setAudioSamplingRate(16_000)
        } else {
            r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
        }
        r.setAudioChannels(1)
        r.setMaxDuration(MAX_VOICE_MILLIS)
        r.setOutputFile(out.path)
        r.prepare()
        r.start()
        recorder = r
        file = out
        startedAt = System.currentTimeMillis()
    }.isSuccess

    /** The clip, or null if it was too short to be meant (a tap, not a hold). */
    fun stop(): ByteArray? {
        val r = recorder ?: return null
        val out = file
        recorder = null
        file = null
        // stop() throws if nothing was recorded yet, or if the max duration already stopped it.
        runCatching { r.stop() }
        r.release()
        if (System.currentTimeMillis() - startedAt < MIN_VOICE_MILLIS) {
            out?.delete()
            return null
        }
        return out?.let { f -> f.readBytes().also { f.delete() } }?.takeIf { it.isNotEmpty() }
    }
}

private const val MAX_SIDE = 640
private const val PHOTO_BUDGET = 22 * 1024
const val MAX_VOICE_MILLIS = 15_000
private const val MIN_VOICE_MILLIS = 600
