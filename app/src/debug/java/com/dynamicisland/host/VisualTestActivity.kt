package com.dynamicisland.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dynamicisland.engine.core.model.MediaPlaybackSnapshot
import com.dynamicisland.engine.settings.BehaviorOptions
import com.dynamicisland.engine.ui.FixedIslandCapsule

/** Debug-only test harness. Never registered or packaged in release builds. */
class VisualTestActivity : ComponentActivity() {
    var scenario by mutableStateOf("Idle")
    var taps by mutableIntStateOf(0)
    var plays by mutableIntStateOf(0)
    var outsideTaps by mutableIntStateOf(0)
    var nextTracks = 0
    var previousTracks = 0
    var transportPlays = 0
    var transportPauses = 0
    private var testSession: android.media.session.MediaSession? = null
    fun startTestMedia() {
        testSession?.release()
        val session = android.media.session.MediaSession(this, "Island instrumentation")
        testSession = session
        fun publish(playing: Boolean) {
            session.setMetadata(android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Test track ${nextTracks - previousTracks + 1}")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Device test")
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 180000).build())
            session.setPlaybackState(android.media.session.PlaybackState.Builder()
                .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
                    android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or android.media.session.PlaybackState.ACTION_SEEK_TO)
                .setState(if(playing) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED, 10000, if(playing) 1f else 0f).build())
        }
        session.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() { transportPlays++; publish(true) }
            override fun onPause() { transportPauses++; publish(false) }
            override fun onSkipToNext() { nextTracks++; publish(true) }
            override fun onSkipToPrevious() { previousTracks++; publish(true) }
        })
        publish(true)
        session.isActive = true
    }
    override fun onDestroy() { testSession?.release(); super.onDestroy() }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme()) {
                Column(Modifier.fillMaxSize().background(Color(0xFF202420)).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("Simulation · $scenario",color=Color.White)
                    Spacer(Modifier.height(40.dp))
                    Box(Modifier.size(130.dp,38.dp)) {
                        FixedIslandCapsule(previewState(scenario),MediaPlaybackSnapshot(isActive=scenario=="Music",isPlaying=scenario=="Music",supportedActions=android.media.session.PlaybackState.ACTION_PLAY_PAUSE),false,0f,{taps++},{plays++},BehaviorOptions(reducedMotion=true))
                    }
                    Text("Tap count: $taps",color=Color.White)
                    Spacer(Modifier.height(100.dp))
                    Button(onClick={outsideTaps++}){Text("Underlying touch target")}
                }
            }
        }
    }
}
