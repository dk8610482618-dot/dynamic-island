package com.dynamicisland.engine.ingestion.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dynamicisland.engine.core.bus.IslandEventBus
import com.dynamicisland.engine.core.bus.SystemIngestionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Android Telecom and Telephony call tracker.
 *
 * Two ingestion paths, in strict priority order:
 *
 *  1. **InCallService** — authoritative, and only bound when this app is the
 *     user's default dialer. While it is bound the telephony broadcast path is
 *     suppressed, because it sees only a coarse `CALL_STATE_*` and would fight
 *     with the rich `Call` objects.
 *  2. **TelephonyManager** — the fallback for every other install.
 *
 * The two must be mutually exclusive *and* both functional.
 */
class TelecomCallTracker(
    private val context: Context,
    private val eventBus: IslandEventBus,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TelecomCallTracker"

        /** Active tracker instance if running. */
        @Volatile
        internal var activeInstance: TelecomCallTracker? = null
            private set
    }

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val telephonyRegistrations = mutableListOf<Pair<TelephonyManager, Any>>()
    private val fallbackCalls = ConcurrentHashMap<String, SystemIngestionEvent.Telecom.CallStateChanged>()

    // Track active call states and ongoing duration jobs
    private val activeCallDurations = ConcurrentHashMap<String, Job>()
    private val callStartTimes = ConcurrentHashMap<String, Long>()
    private val callCallbacks = ConcurrentHashMap<Call, Call.Callback>()
    private val _currentActiveCall = MutableStateFlow<SystemIngestionEvent.Telecom.CallStateChanged?>(null)
    val currentActiveCall: StateFlow<SystemIngestionEvent.Telecom.CallStateChanged?> = _currentActiveCall.asStateFlow()

    fun start() {
        activeInstance = this
        registerTelephonyFallback()
    }

    fun stop() {
        if (activeInstance === this) {
            activeInstance = null
        }
        unregisterTelephonyFallback()
        callCallbacks.forEach { (call, cb) ->
            try { call.unregisterCallback(cb) } catch (e: Exception) {}
        }
        callCallbacks.clear()
        activeCallDurations.values.forEach { it.cancel() }
        activeCallDurations.clear()
        callStartTimes.clear()
        fallbackCalls.clear()
        _currentActiveCall.value = null
    }

    private fun hasTelephonyPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+) normal permission: satisfied at install time without runtime prompt
            val hasBasic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_BASIC_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
            if (hasBasic) return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerTelephonyFallback() {
        val tm=telephonyManager ?: return
        if(!hasTelephonyPermission()) return
        unregisterTelephonyFallback()
        val subscriptions = if(ContextCompat.checkSelfPermission(context,Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED) {
            try { context.getSystemService(android.telephony.SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty() }
            catch(e:SecurityException) { emptyList() }
        } else emptyList()
        val managers = subscriptions.distinctBy {it.subscriptionId}.take(4).map {
            tm.createForSubscriptionId(it.subscriptionId) to "telephony_subscription_${it.subscriptionId}"
        }.ifEmpty {listOf(tm to "telephony_default")}
        managers.forEach { (manager,id) ->
            try {
                if(Build.VERSION.SDK_INT>=31) {
                    val callback=object:TelephonyCallback(),TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state:Int) { handleTelephonyState(state,null,id) }
                    }
                    manager.registerTelephonyCallback(context.mainExecutor,callback)
                    telephonyRegistrations += manager to callback
                } else {
                    @Suppress("DEPRECATION")
                    val listener=object:PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state:Int,number:String?) {handleTelephonyState(state,number,id)}
                    }
                    @Suppress("DEPRECATION")
                    manager.listen(listener,PhoneStateListener.LISTEN_CALL_STATE)
                    telephonyRegistrations += manager to listener
                }
            } catch(e:SecurityException) { Log.w(TAG,"Call-state access was refused",e) }
        }
    }

    private fun unregisterTelephonyFallback() {
        telephonyRegistrations.forEach { (manager,callback) -> runCatching {
            if(Build.VERSION.SDK_INT>=31 && callback is TelephonyCallback) manager.unregisterTelephonyCallback(callback)
            else if(callback is PhoneStateListener) { @Suppress("DEPRECATION") manager.listen(callback,PhoneStateListener.LISTEN_NONE) }
        } }
        telephonyRegistrations.clear()
    }

    private fun publishFallback(event:SystemIngestionEvent.Telecom.CallStateChanged) {
        if(event.status==SystemIngestionEvent.Telecom.CallStatus.DISCONNECTED) fallbackCalls.remove(event.callId)
        else fallbackCalls[event.callId]=event
        _currentActiveCall.value=fallbackCalls.values.sortedWith(compareBy<SystemIngestionEvent.Telecom.CallStateChanged> {
            if(it.status==SystemIngestionEvent.Telecom.CallStatus.INCOMING_RINGING) 0 else 1
        }.thenBy {it.callId}).firstOrNull()
        eventBus.emitEvent(event)
    }

    private fun handleTelephonyState(state: Int, incomingNumber: String?, callId:String) {
        if(callCallbacks.isNotEmpty()) return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val event = SystemIngestionEvent.Telecom.CallStateChanged(
                    callId = callId,
                    status = SystemIngestionEvent.Telecom.CallStatus.INCOMING_RINGING,
                    callerName = incomingNumber ?: "Incoming Call",
                    callerNumber = incomingNumber,
                    avatarUri = null,
                    avatarBitmap = null,
                    durationSeconds = 0L,
                    isMicrophoneMuted = isMicrophoneMuted(),
                    isSpeakerphoneOn = isSpeakerphoneOn(),
                    isControllable = false
                )
                publishFallback(event)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                callStartTimes.putIfAbsent(callId, SystemClock.elapsedRealtime())
                val duration = getCallDurationSeconds(callId, details = null)
                val event = SystemIngestionEvent.Telecom.CallStateChanged(
                    callId = callId,
                    status = SystemIngestionEvent.Telecom.CallStatus.ACTIVE_CONNECTED,
                    callerName = incomingNumber ?: "Active Call",
                    callerNumber = incomingNumber,
                    avatarUri = null,
                    avatarBitmap = null,
                    durationSeconds = duration,
                    isMicrophoneMuted = isMicrophoneMuted(),
                    isSpeakerphoneOn = isSpeakerphoneOn(),
                    isControllable = false
                )
                publishFallback(event)
                startCallDurationTicker(callId)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                stopCallDurationTicker(callId)
                callStartTimes.remove(callId)
                val event = SystemIngestionEvent.Telecom.CallStateChanged(
                    callId = callId,
                    status = SystemIngestionEvent.Telecom.CallStatus.DISCONNECTED,
                    callerName = null,
                    callerNumber = null,
                    avatarUri = null,
                    avatarBitmap = null,
                    durationSeconds = 0L,
                    isControllable = false
                )
                publishFallback(event)
            }
        }
    }



    internal fun onInCallServiceCallAdded(call: Call) {
        if (callCallbacks.containsKey(call)) return
        val callback = object : Call.Callback() {
            override fun onStateChanged(targetCall: Call?, state: Int) {
                targetCall?.let { handleInCallStateChanged(it) }
            }

            override fun onDetailsChanged(targetCall: Call?, details: Call.Details?) {
                targetCall?.let { handleInCallStateChanged(it) }
            }
        }
        callCallbacks[call] = callback
        call.registerCallback(callback)

        handleInCallStateChanged(call)
    }

    internal fun onInCallServiceCallRemoved(call: Call) {
        val callId = call.hashCode().toString()
        stopCallDurationTicker(callId)
        callStartTimes.remove(callId)
        callCallbacks.remove(call)?.let { cb ->
            try { call.unregisterCallback(cb) } catch (e: Exception) {}
        }

        val event = SystemIngestionEvent.Telecom.CallStateChanged(
            callId = callId,
            status = SystemIngestionEvent.Telecom.CallStatus.DISCONNECTED,
            callerName = null,
            callerNumber = null,
            avatarUri = null,
            avatarBitmap = null,
            durationSeconds = 0L,
            isControllable = true
        )
        if (_currentActiveCall.value?.callId == callId) {
            _currentActiveCall.value = null
        }
        eventBus.emitEvent(event)
    }

    private fun handleInCallStateChanged(call: Call) {
        val callId = call.hashCode().toString()
        val details = call.details

        val callerNumber = details?.handle?.schemeSpecificPart
        val contactName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Call.Details.getContactDisplayName() is API 30; calling it on 28/29
            // is a NoSuchMethodError, so it must be gated.
            details?.contactDisplayName
        } else {
            null
        }
        val callerName = details?.callerDisplayName?.takeIf { it.isNotBlank() }
            ?: contactName?.takeIf { it.isNotBlank() }
            ?: callerNumber?.takeIf { it.isNotBlank() }
            ?: "Unknown Caller"

        // Call.Details.state was added in API 31; Android 9–11 must use Call.state.
        @Suppress("DEPRECATION")
        val rawState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && details != null) {
            details.state
        } else {
            call.state
        }
        val status = when (rawState) {
            Call.STATE_RINGING, 13 /* Call.STATE_SIMULATED_RINGING */ ->
                SystemIngestionEvent.Telecom.CallStatus.INCOMING_RINGING
            Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING ->
                SystemIngestionEvent.Telecom.CallStatus.ACTIVE_CONNECTED
            Call.STATE_HOLDING -> SystemIngestionEvent.Telecom.CallStatus.ON_HOLD
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING ->
                SystemIngestionEvent.Telecom.CallStatus.DISCONNECTED
            else -> return
        }

        if (status == SystemIngestionEvent.Telecom.CallStatus.DISCONNECTED) {
            stopCallDurationTicker(callId)
            callStartTimes.remove(callId)
            if (_currentActiveCall.value?.callId == callId) {
                _currentActiveCall.value = null
            }
        } else if (rawState == Call.STATE_ACTIVE) {
            callStartTimes.putIfAbsent(callId, SystemClock.elapsedRealtime())
            startCallDurationTicker(callId, details)
        } else {
            // Pauses the ticker during hold/dialing/ringing without resetting the established start timestamp
            stopCallDurationTicker(callId)
        }

        val duration = getCallDurationSeconds(callId, details)
        val event = SystemIngestionEvent.Telecom.CallStateChanged(
            callId = callId,
            status = status,
            callerName = callerName,
            callerNumber = callerNumber,
            avatarUri = null,
            avatarBitmap = null,
            durationSeconds = duration,
            isMicrophoneMuted = isMicrophoneMuted(),
            isSpeakerphoneOn = isSpeakerphoneOn(),
            isControllable = false
        )

        if (status != SystemIngestionEvent.Telecom.CallStatus.DISCONNECTED) {
            _currentActiveCall.value = event
        }
        eventBus.emitEvent(event)
    }

    private fun getCallDurationSeconds(callId: String, details: Call.Details?): Long {
        val connectTime = details?.connectTimeMillis ?: 0L
        if (connectTime > 0L) {
            val now = System.currentTimeMillis()
            return ((now - connectTime) / 1000L).coerceAtLeast(0L)
        }
        val startTime = callStartTimes[callId]
        if (startTime != null) {
            val now = SystemClock.elapsedRealtime()
            return ((now - startTime) / 1000L).coerceAtLeast(0L)
        }
        return 0L
    }

    private fun startCallDurationTicker(callId: String, details: Call.Details? = null) {
        if (activeCallDurations.containsKey(callId)) return

        val tickerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000L)
                val elapsedSeconds = getCallDurationSeconds(callId, details)
                eventBus.emitEvent(
                    SystemIngestionEvent.Telecom.CallDurationTick(
                        callId = callId,
                        durationSeconds = elapsedSeconds
                    )
                )
            }
        }
        activeCallDurations[callId] = tickerJob
    }

    private fun stopCallDurationTicker(callId: String) {
        activeCallDurations.remove(callId)?.cancel()
    }

    // --- Audio route / mute ---

    /**
     * True when this process holds the permission the standalone AudioManager
     * setters need. Without it they throw, and the caller would crash.
     */
    private fun canModifyAudio(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.MODIFY_AUDIO_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isMicrophoneMuted(): Boolean =
        runCatching { audioManager?.isMicrophoneMute }.getOrNull() ?: false

    @Suppress("DEPRECATION")
    private fun isSpeakerphoneOn(): Boolean {
        val audio = audioManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        return runCatching { audio.isSpeakerphoneOn }.getOrNull() ?: false
    }

    // --- Actions ---

    @Suppress("UNUSED_PARAMETER")
    fun answerCall(callId: String) {
        // Answer is delegated to call notification PendingIntent
    }

    @Suppress("UNUSED_PARAMETER")
    fun hangupCall(callId: String) {
        // Hangup is delegated to call notification PendingIntent
    }

    fun toggleMute(): Boolean {
        if (!canModifyAudio()) {
            Log.w(TAG, "MODIFY_AUDIO_SETTINGS not granted; cannot toggle mute")
            return false
        }
        val audio = audioManager ?: return false
        val newMute = !audio.isMicrophoneMute
        return runCatching {
            audio.isMicrophoneMute = newMute
            updateAudioStateInCurrentCall(isMuted = newMute, isSpeaker = isSpeakerphoneOn())
            newMute
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker(): Boolean {
        if (!canModifyAudio()) {
            Log.w(TAG, "MODIFY_AUDIO_SETTINGS not granted; cannot toggle the speaker route")
            return false
        }
        val audio = audioManager ?: return false
        return runCatching {
            val isSpeaker = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val currentDevice = audio.communicationDevice
                if (currentDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    audio.clearCommunicationDevice()
                    false
                } else {
                    val speaker = audio.availableCommunicationDevices.find {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        audio.setCommunicationDevice(speaker)
                        true
                    } else {
                        false
                    }
                }
            } else {
                val newSpeaker = !audio.isSpeakerphoneOn
                audio.isSpeakerphoneOn = newSpeaker
                newSpeaker
            }
            updateAudioStateInCurrentCall(isMuted = isMicrophoneMuted(), isSpeaker = isSpeaker)
            isSpeaker
        }.getOrDefault(false)
    }

    private fun updateAudioStateInCurrentCall(isMuted: Boolean, isSpeaker: Boolean) {
        val current = _currentActiveCall.value ?: return
        if (current.isMicrophoneMuted != isMuted || current.isSpeakerphoneOn != isSpeaker) {
            val updated = current.copy(
                isMicrophoneMuted = isMuted,
                isSpeakerphoneOn = isSpeaker
            )
            _currentActiveCall.value = updated
            eventBus.emitEvent(updated)
        }
    }
}
