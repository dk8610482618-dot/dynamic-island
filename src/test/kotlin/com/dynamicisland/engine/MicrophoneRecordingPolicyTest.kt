package com.dynamicisland.engine

import android.media.AudioDeviceInfo
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import com.dynamicisland.engine.ingestion.system.MicrophoneRecordingPolicy
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MicrophoneRecordingPolicyTest {
    private fun recording(source: Int, deviceType: Int?, silenced: Boolean = false): AudioRecordingConfiguration {
        val config = mock<AudioRecordingConfiguration>()
        whenever(config.clientAudioSource).thenReturn(source)
        whenever(config.isClientSilenced).thenReturn(silenced)
        if (deviceType != null) {
            val device = mock<AudioDeviceInfo>()
            whenever(device.isSource).thenReturn(true)
            whenever(device.type).thenReturn(deviceType)
            whenever(config.audioDevice).thenReturn(device)
        }
        return config
    }
    @Test fun `active microphone inputs are reported`() {
        val input = recording(MediaRecorder.AudioSource.MIC, AudioDeviceInfo.TYPE_BUILTIN_MIC)
        assertTrue(MicrophoneRecordingPolicy.isMicrophoneActive(input, 36))
    }
    @Test fun `silenced clients and unknown devices are not reported`() {
        assertFalse(MicrophoneRecordingPolicy.isMicrophoneActive(recording(MediaRecorder.AudioSource.MIC, AudioDeviceInfo.TYPE_BUILTIN_MIC, true), 36))
        assertFalse(MicrophoneRecordingPolicy.isMicrophoneActive(recording(MediaRecorder.AudioSource.MIC, null), 36))
    }
    @Test fun `playback capture is not mistaken for microphone use`() {
        assertFalse(MicrophoneRecordingPolicy.isMicrophoneActive(recording(8, AudioDeviceInfo.TYPE_BUILTIN_MIC), 36))
        assertFalse(MicrophoneRecordingPolicy.isMicrophoneActive(recording(MediaRecorder.AudioSource.MIC, AudioDeviceInfo.TYPE_REMOTE_SUBMIX), 36))
    }
    @Test fun `old platforms cannot establish silence state`() {
        assertFalse(MicrophoneRecordingPolicy.isMicrophoneActive(recording(MediaRecorder.AudioSource.MIC, AudioDeviceInfo.TYPE_BUILTIN_MIC), 28))
    }
}
