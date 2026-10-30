package com.dynamicisland.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.dynamicisland.engine.core.model.*
import com.dynamicisland.engine.core.bus.IslandUserCommand
import com.dynamicisland.engine.ui.components.MediaExpandedContent
import com.dynamicisland.engine.ui.components.NotificationInboxContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.dynamicisland.core.geometry.IslandPlacement
import com.dynamicisland.engine.ingestion.notifications.DynamicIslandNotificationListener
import com.dynamicisland.engine.service.DynamicIslandService
import com.dynamicisland.engine.settings.IslandSettings
import com.dynamicisland.engine.settings.BehaviorOptions
import com.dynamicisland.engine.settings.BehaviorPreferences
import kotlin.math.roundToInt

/**
 * Host app for the Dynamic Island overlay.
 *
 * This is not a demo: there is no preview island and no injected content. It only grants the
 * two permissions the engine needs and starts the foreground service, after which the island
 * is driven entirely by live device data (media sessions, notifications, calls, battery,
 * ringer state, Bluetooth accessories).
 */
class MainActivity : ComponentActivity() {

    private var overlayGranted by mutableStateOf(false)
    private var listenerGranted by mutableStateOf(false)
    private var serviceRunning by mutableStateOf(false)
    private var batteryOptimized by mutableStateOf(false)
    private lateinit var behavior: BehaviorPreferences

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refresh() }

    private lateinit var settings: IslandSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        behavior = BehaviorPreferences.get(this)
        requestMissingRuntimePermissions()
        // The shared instance, NOT a new one. Constructing a second IslandSettings
        // here gave this activity its own StateFlow, so slider writes went to a
        // flow the overlay service was not watching and the island never moved.
        settings = IslandSettings.get(this)

        setContent {
            // Recomposes whenever the stored offset changes, including when the
            // service reports a clamped value back.
            val offset by settings.offset.collectAsState()
            val widthScale by settings.widthScale.collectAsState()
            val heightScale by settings.heightScale.collectAsState()
            val envelope by DynamicIslandService.envelope.collectAsState()
            val placement by DynamicIslandService.placement.collectAsState()
            val options by behavior.options.collectAsState()
            val running by DynamicIslandService.running.collectAsState()

            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB7D5C3))) {
            IslandHostScreen(
                overlayGranted = overlayGranted,
                listenerGranted = listenerGranted,
                batteryOptimized = batteryOptimized,
                serviceRunning = running,
                options = options,
                onOptions = { next ->
                    val oldOptions = behavior.options.value
                    val wasNotificationsEnabled = oldOptions.notificationsEnabled
                    behavior.update { next }
                    if ((!wasNotificationsEnabled && next.notificationsEnabled) || oldOptions.blockedPackages != next.blockedPackages || oldOptions.hideNotificationText != next.hideNotificationText) {
                        DynamicIslandNotificationListener.refreshNotifications()
                    }
                },
                onRequestFeatures = ::requestFeaturePermissions,
                offset = offset,
                widthScale = widthScale,
                heightScale = heightScale,
                envelope = envelope,
                placement = placement,
                onGrantOverlay = ::requestOverlayPermission,
                onGrantListener = ::requestListenerPermission,
                onIgnoreBatteryOptimization = ::requestIgnoreBatteryOptimization,
                onOpenAppDetails = ::openAppDetailsSettings,
                onStart = {
                    startIslandSafely()
                    refreshSoon()
                },
                onStop = {
                    DynamicIslandService.stop(this)
                    refreshSoon()
                },
                onOffsetX = settings::setOffsetX,
                onOffsetY = settings::setOffsetY,
                onWidthScale = settings::setWidthScale,
                onHeightScale = settings::setHeightScale,
                onResetPlacement = settings::reset,
                onFlush = settings::flush
            )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Enabling the accessibility service or notification access does not
        // restart this activity, so onResume alone left the status rows stale
        // while the app stayed in front. Returning focus is the signal that
        // something outside the app changed.
        if (hasFocus) refresh()
    }

    override fun onResume() {
        super.onResume()
        DynamicIslandNotificationListener.ensureBound(this)
        refresh()
        // Overlay permission is enough to restore the capsule; listener access only
        // enables live content. Respect the user's explicit Stop preference.
        if (overlayGranted && !serviceRunning && behavior.options.value.enabled) {
            startIslandSafely()
            refreshSoon()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onPause() {
        settings.flush()
        super.onPause()
    }

    private fun startIslandSafely() {
        runCatching { DynamicIslandService.start(this) }.onFailure {
            Toast.makeText(this, "Unable to start. Check overlay permission and try again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestFeaturePermissions() {
        val permissions = buildList {
            add(android.Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), REQUEST_RUNTIME_PERMISSIONS)
        else Toast.makeText(this, "Call and accessory permissions are enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RUNTIME_PERMISSIONS && DynamicIslandService.isRunning) {
            startService(Intent(this, DynamicIslandService::class.java).setAction(DynamicIslandService.ACTION_REFRESH_PERMISSIONS))
        }
        refresh()
    }

    /**
     * Asks for every runtime permission the engine actually consumes.
     *
     * All three are declared in the engine's manifest, so the merge provides
     * them; without the runtime grant each one degrades a feature silently:
     *
     * - `POST_NOTIFICATIONS` (33+): the foreground-service notification and the
     *   "Stop" action in it are hidden.
     * - `BLUETOOTH_CONNECT` (31+): every `BluetoothDevice` read throws, so
     *   accessory name/battery alerts never appear.
     * - `READ_PHONE_STATE`: `TelephonyCallback` registration throws and incoming
     *   calls never reach the island on devices that are not the default dialer.
     *
     * Denials are handled: each consumer checks the permission before use, so the
     * island keeps working without the corresponding feature.
     */
    private fun requestMissingRuntimePermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = wanted.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_RUNTIME_PERMISSIONS)
    }

    /** The service flips [DynamicIslandService.isRunning] in onCreate/onDestroy. */
    private fun refreshSoon() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, 500L)
    }

    private fun refresh() {
        overlayGranted = Settings.canDrawOverlays(this)
        listenerGranted = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        serviceRunning = DynamicIslandService.isRunning

        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        batteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
            !pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            false
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                    )
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestOverlayPermission() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun requestListenerPermission() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun openAppDetailsSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private companion object {
        /** Arbitrary request code; the result is not inspected per-permission. */
        const val REQUEST_RUNTIME_PERMISSIONS = 101
    }
}

@Composable
private fun IslandHostScreen(
    overlayGranted: Boolean,
    listenerGranted: Boolean,
    batteryOptimized: Boolean,
    serviceRunning: Boolean,
    options: BehaviorOptions,
    onOptions: (BehaviorOptions) -> Unit,
    onRequestFeatures: () -> Unit,
    offset: IslandPlacement.UserOffset,
    widthScale: Float,
    heightScale: Float,
    envelope: IslandPlacement.TravelEnvelope?,
    placement: IslandPlacement.PlacementResult?,
    onGrantOverlay: () -> Unit,
    onGrantListener: () -> Unit,
    onIgnoreBatteryOptimization: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOffsetX: (Float) -> Unit,
    onOffsetY: (Float) -> Unit,
    onWidthScale: (Float) -> Unit,
    onHeightScale: (Float) -> Unit,
    onResetPlacement: () -> Unit,
    onFlush: () -> Unit
) {
    val adaptiveCardHeight = 230.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    val ready = overlayGranted
    val media by DynamicIslandService.media.collectAsState()
    val playback by DynamicIslandService.playback.collectAsState()
    val liveState by DynamicIslandService.state.collectAsState()
    val call by DynamicIslandService.call.collectAsState()
    val serviceError by DynamicIslandService.error.collectAsState()
    val actionStatus by DynamicIslandService.actionStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101211))
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            text = "Dynamic Island",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Live system activity around the camera cutout",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 14.sp
        )

        Spacer(Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusRow(
                label = "Display over other apps",
                detail = "Required to show the island over other apps",
                granted = overlayGranted
            )
            StatusRow(
                label = "Notification access",
                detail = "Drives media sessions, timers and alerts",
                granted = listenerGranted
            )
            if (batteryOptimized) {
                StatusRow(
                    label = "Battery optimization",
                    detail = "Active — OEM battery killer may stop overlay",
                    granted = false
                )
            }
            StatusRow(
                label = "Island service",
                detail = if (serviceRunning) "Running" else "Stopped",
                granted = serviceRunning
            )
        }

        Spacer(Modifier.height(22.dp))

        if (!overlayGranted) {
            OutlinedButton(onClick = onGrantOverlay, modifier = Modifier.fillMaxWidth()) {
                Text("Allow display over other apps")
            }
            Spacer(Modifier.height(10.dp))
        }
        if (!listenerGranted) {
            OutlinedButton(onClick = onGrantListener, modifier = Modifier.fillMaxWidth()) {
                Text("Enable notification access")
            }
            Spacer(Modifier.height(10.dp))
        }
        if (batteryOptimized) {
            OutlinedButton(onClick = onIgnoreBatteryOptimization, modifier = Modifier.fillMaxWidth()) {
                Text("Disable Battery Optimization (Recommended)")
            }
            Spacer(Modifier.height(10.dp))
        }

        Button(
            onClick = if (serviceRunning) onStop else onStart,
            enabled = ready || serviceRunning,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (serviceRunning) Color(0xFF2C2C2E) else Color(0xFFB7D5C3),
                contentColor = if (serviceRunning) Color.White else Color.Black
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                text = if (serviceRunning) "Stop Dynamic Island" else "Start Dynamic Island",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        serviceError?.let { Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.padding(vertical = 12.dp)) }
        media?.let { track ->
            Spacer(Modifier.height(20.dp))
            Text("Now playing", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(track.ownerPackage, color = Color(0xFFB8C1BA))
            TextButton(enabled=track.deepLinkIntent!=null,onClick = DynamicIslandService::openMediaSource) { Text("Open player") }
            Box(Modifier.fillMaxWidth().height(adaptiveCardHeight).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
                MediaExpandedContent(track.title, track.subtitle,
                    (track.compactLeading as? IslandSlotContent.Icon)?.iconBitmap, playback,
                    onPlayPause = DynamicIslandService::playPause,
                    onSkipNext = DynamicIslandService::next,
                    onSkipPrevious = DynamicIslandService::previous,
                    onSeek = DynamicIslandService::seek)
            }
        }
        call?.let { current ->
            val incoming = current.status == com.dynamicisland.engine.core.bus.SystemIngestionEvent.Telecom.CallStatus.INCOMING_RINGING
            Box(Modifier.fillMaxWidth().height(adaptiveCardHeight).background(Color.Black)) {
                com.dynamicisland.engine.ui.components.CallExpandedContent(
                    callerName = current.callerName ?: "Phone call", callerNumber = current.callerNumber,
                    durationText = "${current.durationSeconds / 60}:${(current.durationSeconds % 60).toString().padStart(2,'0')}",
                    isIncoming = incoming, isMuted = current.isMicrophoneMuted,
                    isSpeaker = current.isSpeakerphoneOn, isControllable = current.isControllable,
                    isOnHold = current.status == com.dynamicisland.engine.core.bus.SystemIngestionEvent.Telecom.CallStatus.ON_HOLD,
                    onAnswer = DynamicIslandService::answer, onHangup = DynamicIslandService::hangup,
                    onToggleMute = DynamicIslandService::toggleMute, onToggleSpeaker = DynamicIslandService::toggleSpeaker)
            }
        }
        actionStatus?.let { Text(it,color=Color(0xFFB8C1BA),modifier=Modifier.padding(vertical=10.dp)) }
        val activity = when (val current = liveState) {
            is IslandState.Compact -> current.activity
            is IslandState.Minimal -> current.primary
            is IslandState.Expanded -> current.activity
            else -> null
        }
        activity?.takeIf { it.kind != ActivityKind.MEDIA_SESSION }?.let {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Text(it.title, color = Color.White, fontSize = 18.sp)
                it.subtitle?.let { subtitle -> Text(subtitle, color = Color(0xFFB8C1BA)) }
                if(it.kind == ActivityKind.LIVE_TIMER) {
                    OutlinedButton(enabled=TimerActions.cancellation(it)!=null,onClick={DynamicIslandService.cancelTimer(it.id)}) {Text("Cancel timer")}
                    if(TimerActions.cancellation(it)==null) Text("Cancel in the timer app. No supported cancel action was provided.",color=Color(0xFFB8C1BA))
                }
                OutlinedButton(onClick = { DynamicIslandService.command(IslandUserCommand.LongPressPrimary) }) { Text("Open source app") }
            }
        }
        OutlinedButton(onClick = { DynamicIslandService.command(IslandUserCommand.OpenInbox) }, enabled = serviceRunning && listenerGranted) { Text("Open notification inbox") }
        (liveState as? IslandState.NotificationInbox)?.let { inbox ->
            Box(Modifier.fillMaxWidth().height(280.dp).background(Color.Black)) {
                NotificationInboxContent(inbox.notifications,
                    onOpenNotification = { DynamicIslandService.command(IslandUserCommand.OpenNotification(it)) },
                    onClearAll = { DynamicIslandService.command(IslandUserCommand.ClearInbox) },
                    onDismissNotification = { DynamicIslandService.command(IslandUserCommand.DismissNotification(it)) })
            }
        }
        Spacer(Modifier.height(26.dp))

        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.06f)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Compatibility", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text("ColorOS / realme UI: in App info → Battery usage, allow background activity if the island stops. Labels vary by firmware. Accessibility access is not used.", color = Color(0xFFB8C1BA), fontSize = 13.sp)
            OutlinedButton(onClick = onOpenAppDetails, modifier = Modifier.fillMaxWidth()) {
                Text("Open app battery & background settings")
            }
            Text("The island stays above ordinary apps while running. Android can hide overlays on protected screens and apps that block overlays.", color = Color(0xFFB8C1BA), fontSize = 13.sp)
            BehaviorToggle("System-bar touch clearance", "Keeps the island below protected system touch areas, including when using position offsets. Disable to try exact camera overlap; touches may then go to the system.", options.clearSystemBars) { onOptions(options.copy(clearSystemBars = it)) }
            OutlinedButton(onClick = {
                onResetPlacement()
                onOptions(options.copy(clearSystemBars = true))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Restore touch-safe position")
            }
            BehaviorToggle("Show on lock screen", "Generic status only. Android or ColorOS may hide overlays on secure screens.", options.showOnLockScreen) { onOptions(options.copy(showOnLockScreen = it)) }
            BehaviorToggle("Prefer 120 Hz", "A display hint while visible; actual refresh rate is controlled by the phone.", options.highRefreshRate) { onOptions(options.copy(highRefreshRate = it)) }
            Text("Behaviour", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            BehaviorToggle("Start after reboot", "Resumes only if you left the island running", options.startOnBoot) {
                onOptions(options.copy(startOnBoot = it))
            }
            BehaviorToggle("Notification inbox & alerts", "Read messages and dismiss them from the island", options.notificationsEnabled) {
                onOptions(options.copy(notificationsEnabled = it))
            }
            BehaviorToggle("System alerts", "Charging, sound mode and accessory updates", options.systemAlertsEnabled) {
                onOptions(options.copy(systemAlertsEnabled = it))
            }
            BehaviorToggle("Hide in landscape", "Keeps games and videos clear", options.hideInLandscape) {
                onOptions(options.copy(hideInLandscape = it))
            }
            Text("Notification alert duration: ${"%.1f".format(options.peekDurationMillis / 1000f)} s", color = Color.White)
            Slider(
                value = options.peekDurationMillis.toFloat(),
                onValueChange = { onOptions(options.copy(peekDurationMillis = it.toLong())) },
                valueRange = 1500f..8000f,
                steps = 12
            )
            OutlinedButton(onClick = onRequestFeatures, modifier = Modifier.fillMaxWidth()) {
                Text("Enable call & accessory alerts")
            }
        }
        Spacer(Modifier.height(26.dp))

        FeatureSettingsPanel(options, onOptions)

        PlacementSection(
            offset = offset,
            widthScale = widthScale,
            heightScale = heightScale,
            envelope = envelope,
            placement = placement,
            onOffsetX = onOffsetX,
            onOffsetY = onOffsetY,
            onWidthScale = onWidthScale,
            onHeightScale = onHeightScale,
            onReset = onResetPlacement,
            onFlush = onFlush
        )

        Spacer(Modifier.height(22.dp))

        Text(
            text = "Tap the capsule to expand controls in place. Tap the chevron, swipe up on its header, or tap outside to close. Music and notifications keep the compact size until you open it. System icons, secure screens and the notification shade keep Android’s priority.",
            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "The island only displays live data from this device: now-playing " +
                "sessions, notification timers, incoming calls, battery and connected " +
                "accessories. Nothing is simulated.",
            color = Color.White.copy(alpha = 0.38f),
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun BehaviorToggle(title: String, detail: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(detail, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

/** Signed one-decimal formatting for the dp readouts. */
private fun formatDp(value: Float): String =
    (if (value > 0f) "+" else "") + "%.0f".format(value)

/**
 * Island placement controls.
 *
 * Both sliders move the island as a **draw-time translation inside the
 * overlay's Compose layer**, not by moving the overlay window. That is what
 * makes dragging feel instant: no window relayout, no relayout pass, no IPC.
 *
 * The sliders are bounded by the travel envelope the service reports, so the
 * user cannot drag the island somewhere it would be clamped away — the control
 * and the constraint agree by construction rather than by coincidence.
 */
@Composable
private fun PlacementSection(
    offset: IslandPlacement.UserOffset,
    widthScale: Float,
    heightScale: Float,
    envelope: IslandPlacement.TravelEnvelope?,
    placement: IslandPlacement.PlacementResult?,
    onOffsetX: (Float) -> Unit,
    onOffsetY: (Float) -> Unit,
    onWidthScale: (Float) -> Unit,
    onHeightScale: (Float) -> Unit,
    onReset: () -> Unit,
    onFlush: () -> Unit
) {
    // Exact travel envelope when service reports; sensible defaults otherwise
    val maxX = envelope?.horizontalDp ?: 220f
    val maxUp = envelope?.upDp ?: IslandPlacement.TravelEnvelope.UP_TRAVEL_DP
    val maxDown = envelope?.downDp ?: 300f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Position & Size",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (!offset.isDefault || widthScale != 1f || heightScale != 1f) {
                Text(
                    text = "Reset",
                    color = Color(0xFF0A84FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onReset() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = if (offset.isDefault) {
                "Sitting at its default position, aligned to the camera cutout."
            } else {
                "Moved from the default position."
            },
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 12.sp,
            lineHeight = 16.sp
        )

        Spacer(Modifier.height(14.dp))

        // --- Horizontal ---
        PlacementAxis(
            label = "Horizontal (X)",
            valueLabel = formatDp(offset.offsetXDp) + " dp",
            value = offset.offsetXDp,
            range = -maxX..maxX,
            enabled = true,
            onValueChange = onOffsetX,
            onValueChangeFinished = onFlush
        )

        Spacer(Modifier.height(10.dp))

        // --- Vertical ---
        PlacementAxis(
            label = "Vertical (Y)",
            valueLabel = formatDp(offset.offsetYDp) + " dp",
            value = offset.offsetYDp,
            range = -maxUp..maxDown,
            enabled = true,
            onValueChange = onOffsetY,
            onValueChangeFinished = onFlush
        )

        // --- Width ---
        Spacer(Modifier.height(16.dp))
        PlacementAxis(
            label = "Width",
            valueLabel = "${(widthScale * 100).roundToInt()}%",
            value = widthScale,
            range = IslandSettings.MIN_SCALE..IslandSettings.MAX_SCALE,
            enabled = true,
            onValueChange = onWidthScale,
            onValueChangeFinished = onFlush
        )

        // --- Height / Length ---
        Spacer(Modifier.height(10.dp))
        PlacementAxis(
            label = "Length (Height)",
            valueLabel = "${(heightScale * 100).roundToInt()}%",
            value = heightScale,
            range = IslandSettings.MIN_SCALE..IslandSettings.MAX_SCALE,
            enabled = true,
            onValueChange = onHeightScale,
            onValueChangeFinished = onFlush
        )

        // --- Fine adjustment for Position ---
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Fine Tune Position (1 dp):",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val nudgeModifier = Modifier.weight(1f)
            NudgeButton("← 1", true, nudgeModifier) { onOffsetX(offset.offsetXDp - 1f) }
            NudgeButton("→ 1", true, nudgeModifier) { onOffsetX(offset.offsetXDp + 1f) }
            NudgeButton("↑ 1", true, nudgeModifier) { onOffsetY(offset.offsetYDp - 1f) }
            NudgeButton("↓ 1", true, nudgeModifier) { onOffsetY(offset.offsetYDp + 1f) }
        }

        // --- Fine adjustment for Size ---
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Fine Tune Dimensions (5%):",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val nudgeModifier = Modifier.weight(1f)
            NudgeButton("W -5%", true, nudgeModifier) {
                onWidthScale(((widthScale - 0.05f) * 100).roundToInt() / 100f)
            }
            NudgeButton("W +5%", true, nudgeModifier) {
                onWidthScale(((widthScale + 0.05f) * 100).roundToInt() / 100f)
            }
            NudgeButton("L -5%", true, nudgeModifier) {
                onHeightScale(((heightScale - 0.05f) * 100).roundToInt() / 100f)
            }
            NudgeButton("L +5%", true, nudgeModifier) {
                onHeightScale(((heightScale + 0.05f) * 100).roundToInt() / 100f)
            }
        }

        // --- Why the island is where it is ---
        placement?.let { p ->
            Spacer(Modifier.height(14.dp))
            Text(
                text = placementExplanation(p),
                color = Color.White.copy(alpha = 0.38f),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun PlacementAxis(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Text(
                text = valueLabel,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }
        val safeRange = if (range.start >= range.endInclusive) {
            (range.start - 0.5f)..(range.endInclusive + 0.5f)
        } else {
            range
        }
        val clampedValue = value.coerceIn(safeRange.start, safeRange.endInclusive)

        Slider(
            value = clampedValue,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = safeRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFB7D5C3),
                activeTrackColor = Color(0xFFB7D5C3),
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            )
        )
    }
}

@Composable
private fun NudgeButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.height(40.dp)
    ) {
        Text(text = label, fontSize = 13.sp)
    }
}

/**
 * Plain-language reason for the island's current position.
 *
 * A user who cannot understand why the island is off-centre deserves an answer
 * rather than a shrug — particularly on a device with a corner cutout, where
 * the island legitimately cannot be centred.
 */
private fun placementExplanation(p: IslandPlacement.PlacementResult): String {
    val anchor = when (p.fallbackReason) {
        IslandPlacement.FallbackReason.NONE ->
            "Aligned to the camera cutout."
        IslandPlacement.FallbackReason.OFF_CENTRE_CUTOUT ->
            "This device's camera cutout is off-centre, so the island follows the " +
                "cutout rather than the screen."
        IslandPlacement.FallbackReason.CORNER_CUTOUT ->
            "This device has a corner camera cutout, so the island is anchored to " +
                "the corner rather than centred."
        IslandPlacement.FallbackReason.NO_CUTOUT ->
            "No camera cutout detected, so the island is centred using the " +
                "reference dimensions."
        IslandPlacement.FallbackReason.UNKNOWN_STATUS_BAR ->
            "Status bar height unavailable; a nominal offset is in use."
    }
    val clamped = if (p.wasClamped) {
        " The position was limited to the island's allowed travel range."
    } else ""
    return anchor + clamped
}

@Composable
private fun StatusRow(label: String, detail: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (granted) Color(0xFFB7D5C3) else Color(0xFFFF9F0A))
        )
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                text = label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = detail,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp
            )
        }
    }
}
