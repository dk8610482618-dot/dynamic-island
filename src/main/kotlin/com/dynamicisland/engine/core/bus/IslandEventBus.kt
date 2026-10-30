package com.dynamicisland.engine.core.bus

import android.app.PendingIntent
import android.graphics.Bitmap
import android.net.Uri
import android.service.notification.StatusBarNotification
import com.dynamicisland.engine.core.model.IslandAction
import com.dynamicisland.engine.core.model.TransientAlert
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Common base sealed class for all hardware and system ingestion events.
 */
sealed interface SystemIngestionEvent {

    // --- Media Ingestion Events ---
    sealed interface Media : SystemIngestionEvent {
        data class PlaybackChanged(
            val packageName: String,
            val isPlaying: Boolean,
            val playbackState: Int,
            val positionMs: Long,
            val durationMs: Long,
            val playbackSpeed: Float,
            val lastUpdateElapsedRealtime: Long,
            val supportedActions: Long = 0L
        ) : Media

        data class MetadataChanged(
            val packageName: String,
            val title: String,
            val artist: String?,
            val album: String?,
            val albumArtBitmap: Bitmap?,
            val albumArtUri: Uri?,
            val launchIntent: PendingIntent? = null,
            val dominantColorHex: String? = null,
            val lyricLine: String? = null,
            val audioRouteLabel: String? = null
        ) : Media

        data class WaveformVisualization(
            val packageName: String,
            val amplitudes: List<Float>
        ) : Media

        data class SessionDestroyed(
            val packageName: String
        ) : Media
    }

    // --- Notification Ingestion Events ---
    sealed interface NotificationEvent : SystemIngestionEvent {
        object AccessRevoked : NotificationEvent

        data class Posted(
            val key: String,
            val packageName: String,
            val notificationId: Int,
            val category: String?,
            val isOngoing: Boolean,
            val title: String,
            val text: String?,
            val subText: String?,
            val progressCurrent: Int?,
            val progressMax: Int?,
            val isProgressIndeterminate: Boolean,
            val chronometerBase: Long?,
            val showChronometer: Boolean,
            val isChronometerCountDown: Boolean = false,
            val actions: List<IslandAction>,
            val contentIntent: PendingIntent?,
            val statusBarNotification: StatusBarNotification?,
            val shouldAlert: Boolean = true,
            val postedAtMillis: Long? = null,
            val appLabel: String? = null,
            val groupKey: String? = null,
            val importance: Int = 3,
            val isProgressStyle: Boolean = false,
            val progressSegments: Int = 0,
            val progressTrackerLabel: String? = null,
            val staleAtMillis: Long? = null,
            val relevanceScore: Float = 0f
        ) : NotificationEvent

        data class Removed(
            val key: String,
            val packageName: String,
            val notificationId: Int,
            val reason: Int
        ) : NotificationEvent
    }

    // --- Telecom Ingestion Events ---
    sealed interface Telecom : SystemIngestionEvent {
        enum class CallStatus {
            INCOMING_RINGING,
            ACTIVE_CONNECTED,
            ON_HOLD,
            DISCONNECTED
        }

        data class CallStateChanged(
            val callId: String,
            val status: CallStatus,
            val callerName: String?,
            val callerNumber: String?,
            val avatarUri: Uri?,
            val avatarBitmap: Bitmap?,
            val durationSeconds: Long = 0L,
            val isMicrophoneMuted: Boolean = false,
            val isSpeakerphoneOn: Boolean = false,
            val isControllable: Boolean = false,
            val answerIntent: PendingIntent? = null,
            val disconnectIntent: PendingIntent? = null
        ) : Telecom

        data class CallDurationTick(
            val callId: String,
            val durationSeconds: Long
        ) : Telecom
    }

    // --- System State Ingestion Events ---
    sealed interface SystemState : SystemIngestionEvent {
        data class Battery(
            val percentage: Int,
            val isCharging: Boolean,
            val isPlugged: Boolean,
            val plugSource: PlugSource,
            val isFastCharging: Boolean
        ) : SystemState {
            enum class PlugSource { AC, USB, WIRELESS, NONE }
        }

        data class RingerDnd(
            val ringerMode: RingerMode,
            val isDndActive: Boolean,
            val interruptionFilter: Int
        ) : SystemState {
            enum class RingerMode { NORMAL, VIBRATE, SILENT }
        }

        data class BluetoothAccessory(
            val deviceAddress: String,
            val deviceName: String,
            val isConnected: Boolean,
            val isAudioOutput: Boolean,
            val leftBudBatteryPercent: Int? = null,
            val rightBudBatteryPercent: Int? = null,
            val caseBatteryPercent: Int? = null,
            val genericBatteryPercent: Int? = null
        ) : SystemState

        data class Hotspot(
            val isEnabled: Boolean,
            val connectedClientsCount: Int
        ) : SystemState

        data class KeyguardLocked(
            val isLocked: Boolean
        ) : SystemState

        data class Microphone(
            val isInUse: Boolean
        ) : SystemState
    }

    // --- Direct Transient Alerts ---
    data class TransientAlertPost(
        val alert: TransientAlert
    ) : SystemIngestionEvent
}

/**
 * User touch commands directed at the Dynamic Island overlay.
 */
sealed interface IslandUserCommand {
    object TapPrimary : IslandUserCommand
    object TapSecondary : IslandUserCommand
    object TapTertiary : IslandUserCommand
    object LongPressPrimary : IslandUserCommand
    object LongPressSecondary : IslandUserCommand
    object LongPressTertiary : IslandUserCommand
    data class CycleActivityFocus(val direction: Int = 1) : IslandUserCommand
    object CollapseExpanded : IslandUserCommand
    object DismissAlertPeek : IslandUserCommand
    object OpenInbox : IslandUserCommand
    object CloseInbox : IslandUserCommand
    object ClearInbox : IslandUserCommand
    data class DismissNotification(val key: String) : IslandUserCommand
    data class OpenNotification(val key: String) : IslandUserCommand
    data class DismissActivity(val activityId: String) : IslandUserCommand
    data class CancelTimer(val activityId: String) : IslandUserCommand
    data class ExecuteAction(val activityId: String, val actionId: String) : IslandUserCommand
    data class SeekMedia(val positionMs: Long) : IslandUserCommand
}

/**
 * Thread-safe Event Bus connecting all ingestion providers to the priority arbiter and repository.
 */
class IslandEventBus {
    private val _events = MutableSharedFlow<SystemIngestionEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<SystemIngestionEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<IslandUserCommand>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commands: SharedFlow<IslandUserCommand> = _commands.asSharedFlow()

    fun emitEvent(event: SystemIngestionEvent): Boolean {
        return _events.tryEmit(event)
    }

    suspend fun emitEventSuspend(event: SystemIngestionEvent) {
        _events.emit(event)
    }

    fun emitCommand(command: IslandUserCommand): Boolean {
        return _commands.tryEmit(command)
    }

    suspend fun emitCommandSuspend(command: IslandUserCommand) {
        _commands.emit(command)
    }
}
