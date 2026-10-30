package com.dynamicisland.engine
import android.media.session.PlaybackState
import com.dynamicisland.engine.core.model.MediaPlaybackSnapshot
import org.junit.Assert.*
import org.junit.Test
class MediaCapabilitiesTest {
    @Test fun `unknown sessions expose no unsupported controls`() {
        val snapshot=MediaPlaybackSnapshot(durationMs=1000)
        assertFalse(snapshot.canSeek);assertFalse(snapshot.canNext);assertFalse(snapshot.canPrevious);assertFalse(snapshot.canPlayPause)
    }
    @Test fun `seek needs both capability and duration`() {
        val snapshot=MediaPlaybackSnapshot(supportedActions=PlaybackState.ACTION_SEEK_TO)
        assertFalse(snapshot.canSeek);assertTrue(snapshot.copy(durationMs=1000).canSeek)
    }
    @Test fun `paused player offers play only when supported`() {
        val snapshot=MediaPlaybackSnapshot(supportedActions=PlaybackState.ACTION_PAUSE,isPlaying=false)
        assertFalse(snapshot.canPlayPause);assertTrue(snapshot.copy(isPlaying=true).canPlayPause)
        assertTrue(snapshot.copy(supportedActions=PlaybackState.ACTION_PLAY_PAUSE).canPlayPause)
    }
    @Test fun `next never enables previous`() {
        val snapshot=MediaPlaybackSnapshot(supportedActions=PlaybackState.ACTION_SKIP_TO_NEXT)
        assertTrue(snapshot.canNext);assertFalse(snapshot.canPrevious)
    }
}
