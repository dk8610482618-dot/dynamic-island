package com.dynamicisland.engine.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.dynamicisland.core.system.ServiceWatchdogPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Gap **G6** — foreground-service watchdog.
 *
 * Doc 2: overlay services on Android "are frequently terminated by aggressive
 * background battery management or crash during rapid transitions between calls,
 * charging, and music", so the architecture needs a watchdog. The base already
 * had the boot receiver and the battery-exemption screen; what it lacked was any
 * way to notice a killed or wedged service.
 *
 * Three failure modes, three different responses — restarting everything for each
 * would be wasteful and, on OEM ROMs, would look exactly like the runaway
 * background behaviour that gets an app killed harder:
 *
 *  * overlay window detached  → re-attach only
 *  * notification access lost → cannot be restored in code, so surface it
 *  * service gone / wedged    → restart with exponential backoff
 *
 * The schedule is coarse and comes from [ServiceWatchdogPolicy], which is pure and
 * unit-tested. That keeps doc 1's "zero recurring wakeups solely for static island
 * content" intact: one check a minute while enabled, nothing at all when the user
 * has the island off, and retries stop after the battery exemption has been
 * refused twice.
 */
class IslandServiceWatchdog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onRestartService: () -> Unit,
    private val onReattachOverlay: () -> Unit
) {

    private val _status = MutableStateFlow(ServiceWatchdogPolicy.Verdict.HEALTHY)
    /** Surfaced in the host UI so a blocked watchdog is explained, not silent. */
    val status: StateFlow<ServiceWatchdogPolicy.Verdict> = _status.asStateFlow()

    private val _requiresUserAction = MutableStateFlow(false)
    val requiresUserAction: StateFlow<Boolean> = _requiresUserAction.asStateFlow()

    private var job: Job? = null
    private var consecutiveFailures = 0

    @Volatile private var lastHeartbeatElapsed = SystemClock.elapsedRealtime()

    /**
     * Snapshot of what the service knows about itself, refreshed on each beat.
     * The service owns these facts; the watchdog only interprets them.
     */
    var healthProvider: () -> ServiceWatchdogPolicy.HealthSignal = {
        ServiceWatchdogPolicy.HealthSignal(
            isServiceRunning = false,
            isOverlayAttached = false,
            isListenerConnected = false,
            millisSinceLastHeartbeat = Long.MAX_VALUE,
            hasBatteryExemption = false
        )
    }

    /** Called by the service whenever it publishes new island state. */
    fun heartbeat() {
        lastHeartbeatElapsed = SystemClock.elapsedRealtime()
    }

    fun millisSinceLastHeartbeat(): Long = SystemClock.elapsedRealtime() - lastHeartbeatElapsed

    fun start() {
        if (job?.isActive == true) return
        heartbeat()
        job = scope.launch {
            while (isActive) {
                val signal = healthProvider()
                val decision = ServiceWatchdogPolicy.evaluate(signal, consecutiveFailures)
                consecutiveFailures = decision.consecutiveFailures
                _status.value = decision.verdict
                _requiresUserAction.value = decision.requiresUserAction

                when (decision.verdict) {
                    ServiceWatchdogPolicy.Verdict.REATTACH_OVERLAY -> onReattachOverlay()

                    ServiceWatchdogPolicy.Verdict.RESTART_SERVICE -> {
                        // Back off before retrying so a service the platform
                        // keeps killing is not restarted in a tight loop.
                        if (decision.delayMs > 0L) delay(decision.delayMs)
                        onRestartService()
                    }

                    ServiceWatchdogPolicy.Verdict.DISABLED -> {
                        // The user turned the island off. Stop watching entirely.
                        return@launch
                    }

                    else -> Unit
                }

                val wait = if (decision.delayMs > 0L) {
                    decision.delayMs
                } else {
                    ServiceWatchdogPolicy.nextHealthyDelayMs(System.currentTimeMillis())
                }
                delay(wait)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Whether the OS will let this app schedule an exact-ish restart alarm. Used
     * by the host UI to explain why recovery may be delayed on Android 12+.
     */
    fun canScheduleExactAlarms(): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /** True when the user has already granted the battery-optimisation exemption. */
    fun hasBatteryExemption(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    companion object {
        /**
         * Action used by the boot receiver and the watchdog alarm to bring the
         * service back. Kept as a constant so both paths agree.
         */
        const val ACTION_WATCHDOG_RESTART = "com.dynamicisland.action.WATCHDOG_RESTART"
    }
}

/**
 * Receives the watchdog restart action and the platform's `BOOT_COMPLETED`.
 *
 * Doc 2 asks for `BOOT_COMPLETED` recovery so the island survives a reboot; the
 * base already had a boot receiver, and this keeps the watchdog's restart path on
 * the same mechanism rather than inventing a second one.
 */
class IslandWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IslandServiceWatchdog.ACTION_WATCHDOG_RESTART) return
        val restart = Intent(context, DynamicIslandService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(restart)
            } else {
                context.startActivity(restart)
            }
        }
    }
}

/**
 * Schedules a watchdog restart alarm that survives process death, for the case
 * where the whole process is killed rather than just the service.
 *
 * Uses `setAndAllowWhileIdle` rather than `setExact`: exact alarms need
 * `SCHEDULE_EXACT_ALARM` on Android 12+ and are revoked by the platform under
 * battery pressure, which is precisely when a watchdog matters. An inexact
 * idle-window alarm still fires, just later, and needs no permission.
 */
fun scheduleWatchdogAlarm(context: Context, delayMs: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val intent = Intent(context, IslandWatchdogReceiver::class.java).apply {
        action = IslandServiceWatchdog.ACTION_WATCHDOG_RESTART
    }
    // IMMUTABLE is required from API 31; the receiver only reads the action, so
    // there is nothing to mutate.
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    val pending = runCatching {
        PendingIntent.getBroadcast(context, WATCHDOG_REQUEST_CODE, intent, flags)
    }.getOrNull() ?: return
    alarmManager.setAndAllowWhileIdle(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(0L),
        pending
    )
}

private const val WATCHDOG_REQUEST_CODE = 0x51A4
