package com.dynamicisland.engine.ingestion.system

import android.media.AudioDeviceInfo
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build

/** Conservative interpretation of public, anonymized recording configurations. */
internal object MicrophoneRecordingPolicy {
    fun isMicrophoneActive(config: AudioRecordingConfiguration): Boolean =
        isMicrophoneActive(config, Build.VERSION.SDK_INT)

    // The injected SDK is used only by JVM tests; production always passes the
    // real SDK above. Lint cannot infer that guarantee from the test parameter.
    @android.annotation.SuppressLint("NewApi")
    internal fun isMicrophoneActive(config: AudioRecordingConfiguration, sdk: Int): Boolean {
        // Before API 29 the public API cannot tell whether an active client is silenced.
        if (sdk < 29) return false
        return runCatching {
            if (config.isClientSilenced) return@runCatching false
            val source = config.clientAudioSource
            if (source !in setOf(MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.VOICE_PERFORMANCE)) return@runCatching false
            // Remote submix and playback-capture configurations are not a microphone.
            // If the device is unavailable we cannot safely assert its type.
            val device = config.audioDevice ?: return@runCatching false
            device.isSource && device.type in setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET)
        }.getOrDefault(false)
    }
}
