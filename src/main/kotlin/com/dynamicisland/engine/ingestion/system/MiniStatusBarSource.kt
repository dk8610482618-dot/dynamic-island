package com.dynamicisland.engine.ingestion.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import com.dynamicisland.core.system.MiniStatusBarPolicy
import com.dynamicisland.engine.core.model.MiniStatusBarSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gap **G4** — real data for the immersive mini status bar.
 *
 * Doc 2 asks the idle pill to render "the live clock and cellular/Wi-Fi signal
 * bars" when the user hides the system status bar in immersive mode. The base had
 * the setting and a renderer, but the renderer drew a constant green battery glyph
 * and a clock captured once per composition, so it showed a frozen time and a
 * meaningless battery.
 *
 * This source is **event-driven, not polled**: battery, connectivity and signal
 * strength all arrive as broadcasts or listener callbacks, and the clock itself is
 * scheduled per minute boundary by `rememberMiniBarClock`. Doc 1 requires "no
 * polling loop for media position, battery or notifications when static" and "zero
 * recurring wakeups solely for static island content", so nothing here runs a
 * timer.
 *
 * Anything not observed stays null or `NONE` and renders as unknown rather than
 * being fabricated.
 */
class MiniStatusBarSource(private val context: Context) {

    private val _snapshot = MutableStateFlow(MiniStatusBarSnapshot())
    val snapshot: StateFlow<MiniStatusBarSnapshot> = _snapshot.asStateFlow()

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    /** Last observed signal level, kept so a battery change does not erase it. */
    @Volatile private var signalLevel = MiniStatusBarPolicy.SignalLevel.NONE
    @Volatile private var signalIsWifi = false
    @Volatile private var hasSignal = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    // Sticky broadcast: the Intent itself carries the levels, so
                    // there is nothing to register for and nothing to poll.
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val percent = if (level >= 0 && scale > 0) (level * 100) / scale else null
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    publish(
                        batteryPercent = percent,
                        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL,
                        isPowerSaveMode = powerManager?.isPowerSaveMode == true
                    )
                }

                ConnectivityManager.CONNECTIVITY_ACTION,
                WifiManager.RSSI_CHANGED_ACTION,
                WifiManager.WIFI_STATE_CHANGED_ACTION,
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> refreshSignal()
            }
        }
    }

    /** Last published battery fields, so a signal change preserves them. */
    @Volatile private var batteryPercent: Int? = null
    @Volatile private var isCharging = false
    @Volatile private var isPowerSaveMode = false

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }

        // Signal strength has no broadcast; a listener is the only event-driven
        // source, and it is API 29+. Below that we keep the level derived from
        // connectivity alone rather than polling the telephony manager.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                telephony?.registerTelephonyCallback(
                    context.mainExecutor,
                    signalCallback
                )
            }
        }

        refreshSignal()
        publish(batteryPercent, isCharging, powerManager?.isPowerSaveMode == true)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { telephony?.unregisterTelephonyCallback(signalCallback) }
        }
    }

    private val signalCallback = object :
        android.telephony.TelephonyCallback(),
        android.telephony.TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            // Only refresh when cellular is the active transport; otherwise this
            // would overwrite the Wi-Fi level.
            if (!signalIsWifi) {
                signalLevel = MiniStatusBarPolicy.cellularLevel(signalStrength.cellSignalStrengths
                    .firstOrNull()?.rsrp ?: return)
                hasSignal = signalLevel != MiniStatusBarPolicy.SignalLevel.NONE
                publish(batteryPercent, isCharging, isPowerSaveMode)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshSignal() {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiConnected = wifiManager?.isWifiEnabled == true &&
            connectivity?.let { cm ->
                val network = cm.activeNetwork ?: return@let false
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } == true

        if (wifiConnected && wifiManager != null) {
            val rssi = runCatching { wifiManager.connectionInfo?.rssi ?: Int.MIN_VALUE }
                .getOrDefault(Int.MIN_VALUE)
            signalIsWifi = true
            hasSignal = rssi != Int.MIN_VALUE
            signalLevel = if (hasSignal) MiniStatusBarPolicy.wifiLevel(rssi) else MiniStatusBarPolicy.SignalLevel.NONE
        } else {
            signalIsWifi = false
            val bars = runCatching { telephony?.signalStrength?.level ?: -1 }.getOrDefault(-1)
            // `SignalStrength.getLevel` is 0..4, the same scale the policy emits.
            signalLevel = when (bars) {
                4 -> MiniStatusBarPolicy.SignalLevel.EXCELLENT
                3 -> MiniStatusBarPolicy.SignalLevel.GOOD
                2 -> MiniStatusBarPolicy.SignalLevel.MODERATE
                1 -> MiniStatusBarPolicy.SignalLevel.POOR
                else -> MiniStatusBarPolicy.SignalLevel.NONE
            }
            hasSignal = signalLevel != MiniStatusBarPolicy.SignalLevel.NONE
        }
        publish(batteryPercent, isCharging, isPowerSaveMode)
    }

    private fun publish(percent: Int?, charging: Boolean, powerSave: Boolean) {
        batteryPercent = percent
        isCharging = charging
        isPowerSaveMode = powerSave
        _snapshot.value = MiniStatusBarSnapshot(
            batteryPercent = percent,
            isCharging = charging,
            isPowerSaveMode = powerSave,
            signalLevel = signalLevel,
            isWifi = signalIsWifi,
            hasSignal = hasSignal,
            is24HourClock = android.text.format.DateFormat.is24HourFormat(context),
            // A percentage only fits when the pill has room; the glyph alone is
            // used otherwise, per doc 1's "truncate before geometry becomes
            // unstable".
            showBatteryPercent = false
        )
    }
}
