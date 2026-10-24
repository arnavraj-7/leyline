package dev.leyline.app.mesh

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Makes this phone easy to find by ear and eye: alarm sound at full alarm
 * volume, vibration and a flashing torch, for [durationMillis] or until stopped.
 * A rescuer within Bluetooth range triggers it, because GPS (±50 m) can't point
 * at a person under rubble but a ringing phone can.
 *
 * Every step is best effort: a phone without a torch, or in Do Not Disturb,
 * still does whatever it can.
 */
class Alarm(private val context: Context, private val scope: CoroutineScope) {
    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var restoreVolume: Int? = null

    fun start(durationMillis: Long = 30_000) {
        if (_ringing.value) return
        _ringing.value = true
        job = scope.launch {
            try {
                playSound()
                vibrate()
                val torch = torchCamera()
                val end = System.currentTimeMillis() + durationMillis
                var on = false
                while (System.currentTimeMillis() < end) {
                    on = !on
                    torch?.let { setTorch(it, on) }
                    delay(TORCH_BLINK_MILLIS)
                }
            } finally {
                release()
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun playSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        val audio = context.getSystemService(AudioManager::class.java)
        runCatching {
            restoreVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }.onFailure { Log.w(TAG, "can't raise alarm volume", it) }
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "can't play alarm sound", it) }.getOrNull()
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private fun vibrate() {
        runCatching { vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)) }
    }

    private fun torchCamera(): String? = runCatching {
        val cameras = context.getSystemService(CameraManager::class.java)
        cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()

    private fun setTorch(camera: String, on: Boolean) {
        runCatching { context.getSystemService(CameraManager::class.java).setTorchMode(camera, on) }
    }

    private fun release() {
        player?.runCatching {
            stop()
            release()
        }
        player = null
        runCatching { vibrator()?.cancel() }
        torchCamera()?.let { setTorch(it, false) }
        restoreVolume?.let { volume ->
            runCatching { context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        }
        restoreVolume = null
        _ringing.value = false
    }

    private companion object {
        const val TAG = "LeylineAlarm"
        const val TORCH_BLINK_MILLIS = 300L
    }
}
