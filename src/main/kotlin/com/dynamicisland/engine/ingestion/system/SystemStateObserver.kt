package com.dynamicisland.engine.ingestion.system

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dynamicisland.engine.core.bus.IslandEventBus
import com.dynamicisland.engine.core.bus.SystemIngestionEvent
import com.dynamicisland.engine.core.model.IslandSlotContent
import com.dynamicisland.engine.core.model.IslandSymbol
import com.dynamicisland.engine.core.model.PriorityRank
import com.dynamicisland.engine.core.model.TransientAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Observes core Android system states:
 * - Battery percentage, charging state and true fast-charge detection
 * - Ringer (Normal/Vibrate/Silent) and Do Not Disturb (DND) modes
 * - Hotspot / tethering status
 * - Bluetooth accessory connections and reported battery level
 */
class SystemStateObserver(
    private val context: Context,
    private val eventBus: IslandEventBus
) {
    companion object {
        private const val TAG = "SystemStateObserver"

        /**
         * Charge current above which charging is reported as fast.
         *
         * `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` is in microamperes, so this
         * is 1.5 A. The previous heuristic called *any* AC charge below 80% "fast
         * charging", which is simply wrong — a 5 W charger was announced as fast.
         */
        private const val FAST_CHARGE_CURRENT_UA = 1_500_000

        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_WIFI_AP_STATE = "wifi_state"
        private const val WIFI_AP_STATE_ENABLED = 13

        /**
         * Bluetooth battery broadcast and its extra.
         *
         * Written as literals: `BluetoothDevice.ACTION_BATTERY_CHANGED` is not part
         * of the public SDK, and the previous code registered the *extra* name
         * ("...extra.BATTERY_LEVEL") as a filter action, which never matches.
         */
        private const val ACTION_BLUETOOTH_BATTERY_CHANGED =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BLUETOOTH_BATTERY_LEVEL =
            "android.bluetooth.device.extra.BATTERY_LEVEL"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    private var bluetoothHeadsetProfile: BluetoothHeadset? = null

    // State tracking
    private var lastChargingState = false
    // ACTION_BATTERY_CHANGED is sticky: the system replays the current value immediately on
    // register. The first delivery is a baseline, not a "charger connected" transition.
    private var batteryBaselineCaptured = false
    private var lastRingerMode = AudioManager.RINGER_MODE_NORMAL
    private val _batteryState = MutableStateFlow<SystemIngestionEvent.SystemState.Battery?>(null)
    val batteryState: StateFlow<SystemIngestionEvent.SystemState.Battery?> = _batteryState.asStateFlow()

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val _isCameraInUse = MutableStateFlow(false)
    val isCameraInUse: StateFlow<Boolean> = _isCameraInUse.asStateFlow()
    private val monitoredCameraIds = mutableSetOf<String>()
    private val unavailableCameras = mutableSetOf<String>()
    private val activeTorchCameras = mutableSetOf<String>()
    private var cameraAvailabilityCallback: CameraManager.AvailabilityCallback? = null
    private var cameraTorchCallback: CameraManager.TorchCallback? = null
    private val _isMicInUse = MutableStateFlow(false)
    val isMicInUse: StateFlow<Boolean> = _isMicInUse.asStateFlow()

    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> handleBatteryChanged(intent)
                AudioManager.RINGER_MODE_CHANGED_ACTION -> handleRingerModeChanged()
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> handleDndChanged()
                ACTION_WIFI_AP_STATE_CHANGED -> handleHotspotStateChanged(intent)
                BluetoothDevice.ACTION_ACL_CONNECTED -> handleBluetoothConnected(intent)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> handleBluetoothDisconnected(intent)
                ACTION_BLUETOOTH_BATTERY_CHANGED -> handleBluetoothBatteryChanged(intent)
                Intent.ACTION_SCREEN_OFF -> emitKeyguardLocked(true)
                Intent.ACTION_USER_PRESENT -> emitKeyguardLocked(false)
                Intent.ACTION_SCREEN_ON -> emitKeyguardLocked(keyguardManager?.isKeyguardLocked == true)
            }
        }
    }

    private fun updateCameraInUseLocked() {
        val activeSensors = unavailableCameras.filter { it !in activeTorchCameras }
        _isCameraInUse.value = activeSensors.isNotEmpty()
    }

    private fun emitKeyguardLocked(isLocked: Boolean) {
        eventBus.emitEvent(SystemIngestionEvent.SystemState.KeyguardLocked(isLocked))
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadsetProfile = proxy as? BluetoothHeadset
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadsetProfile = null
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(ACTION_WIFI_AP_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BLUETOOTH_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        // targetSdk 34 requires an explicit export flag. These are all protected system
        // broadcasts (battery, ringer/DND, Bluetooth) sent by system UIDs, so EXPORTED is
        // the correct choice; without a flag registerReceiver throws SecurityException and
        // the foreground service dies during onCreate.
        ContextCompat.registerReceiver(
            context,
            systemReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        if (hasBluetoothConnect()) {
            bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
        }
        lastRingerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        emitKeyguardLocked(keyguardManager?.isKeyguardLocked == true)
        cameraManager?.let { cm ->
            runCatching {
                monitoredCameraIds.clear()
                for (id in cm.cameraIdList) {
                    val characteristics = cm.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK ||
                        facing == CameraCharacteristics.LENS_FACING_FRONT ||
                        facing == CameraCharacteristics.LENS_FACING_EXTERNAL
                    ) {
                        monitoredCameraIds.add(id)
                    }
                }
            }

            val cb = object : CameraManager.AvailabilityCallback() {
                override fun onCameraUnavailable(cameraId: String) {
                    if (monitoredCameraIds.isEmpty() || monitoredCameraIds.contains(cameraId)) {
                        synchronized(unavailableCameras) {
                            unavailableCameras.add(cameraId)
                            updateCameraInUseLocked()
                        }
                    }
                }
                override fun onCameraAvailable(cameraId: String) {
                    synchronized(unavailableCameras) {
                        unavailableCameras.remove(cameraId)
                        updateCameraInUseLocked()
                    }
                }
            }
            cameraAvailabilityCallback = cb

            val torchCb = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    synchronized(unavailableCameras) {
                        if (enabled) {
                            activeTorchCameras.add(cameraId)
                        } else {
                            activeTorchCameras.remove(cameraId)
                        }
                        updateCameraInUseLocked()
                    }
                }
                override fun onTorchModeUnavailable(cameraId: String) {
                    synchronized(unavailableCameras) {
                        activeTorchCameras.remove(cameraId)
                        updateCameraInUseLocked()
                    }
                }
            }
            cameraTorchCallback = torchCb

            runCatching {
                cm.registerAvailabilityCallback(cb, null)
                cm.registerTorchCallback(torchCb, null)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<android.media.AudioRecordingConfiguration>?) {
                    val inUse = configs?.any { MicrophoneRecordingPolicy.isMicrophoneActive(it) } == true
                    _isMicInUse.value = inUse
                    eventBus.emitEvent(SystemIngestionEvent.SystemState.Microphone(inUse))
                }
            }
            audioRecordingCallback = cb
            audioManager?.registerAudioRecordingCallback(cb, null)
            runCatching {
                val currentConfigs = audioManager?.activeRecordingConfigurations
                val inUse = currentConfigs?.any { MicrophoneRecordingPolicy.isMicrophoneActive(it) } == true
                _isMicInUse.value = inUse
                eventBus.emitEvent(SystemIngestionEvent.SystemState.Microphone(inUse))
            }
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioRecordingCallback?.let { cb ->
                runCatching { audioManager?.unregisterAudioRecordingCallback(cb) }
                audioRecordingCallback = null
            }
        }
        cameraAvailabilityCallback?.let { cb ->
            runCatching { cameraManager?.unregisterAvailabilityCallback(cb) }
            cameraAvailabilityCallback = null
        }
        cameraTorchCallback?.let { cb ->
            runCatching { cameraManager?.unregisterTorchCallback(cb) }
            cameraTorchCallback = null
        }
        synchronized(unavailableCameras) {
            unavailableCameras.clear()
            activeTorchCameras.clear()
            monitoredCameraIds.clear()
        }
        _isCameraInUse.value = false
        _isMicInUse.value = false
        batteryBaselineCaptured = false
        lastChargingState = false
        try {
            context.unregisterReceiver(systemReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering system receiver", e)
        }

        bluetoothHeadsetProfile?.let { proxy ->
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
            bluetoothHeadsetProfile = null
        }
    }

    /**
     * `BLUETOOTH_CONNECT` is a runtime permission on API 31+. Every call that reads
     * a device's name or address throws without it, so it gates those reads rather
     * than being assumed.
     */
    private fun hasBluetoothConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    // --- Battery Handling ---

    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isPlugged = plugged > 0
        val plugSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> SystemIngestionEvent.SystemState.Battery.PlugSource.AC
            BatteryManager.BATTERY_PLUGGED_USB -> SystemIngestionEvent.SystemState.Battery.PlugSource.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> SystemIngestionEvent.SystemState.Battery.PlugSource.WIRELESS
            else -> SystemIngestionEvent.SystemState.Battery.PlugSource.NONE
        }

        // Real fast-charge detection: the charge current reported by the fuel gauge.
        // Devices that do not expose it report 0, which correctly reads as "not fast".
        val rawCurrent = runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        }.getOrDefault(0)
        val currentUa = if (rawCurrent == Int.MIN_VALUE) 0 else abs(rawCurrent)
        val normalizedUa = if (currentUa in 1..9999) currentUa * 1000 else currentUa
        val isFastCharging = isCharging && normalizedUa >= FAST_CHARGE_CURRENT_UA

        val batteryEvent = SystemIngestionEvent.SystemState.Battery(
            percentage = percentage,
            isCharging = isCharging,
            isPlugged = isPlugged,
            plugSource = plugSource,
            isFastCharging = isFastCharging
        )
        _batteryState.value = batteryEvent
        eventBus.emitEvent(batteryEvent)

        // Trigger iOS-style Transient Alert only on a real charging transition.
        if (batteryBaselineCaptured && !lastChargingState && isCharging) {
            val alert = TransientAlert(
                id = "battery_charging_${System.currentTimeMillis()}",
                title = if (isFastCharging) "Fast Charging" else "Charging",
                // Percentage lives only in the trailing slot; duplicating it in the
                // message rendered "68% / 68%" in the peek.
                message = null,
                leadingSlot = IslandSlotContent.Symbol(IslandSymbol.BOLT, tintHex = "#32D74B"),
                trailingSlot = IslandSlotContent.Text("$percentage%", colorHex = "#34C759"),
                durationMillis = 2800L,
                rank = PriorityRank.TRANSIENT_SYSTEM_ALERT
            )
            eventBus.emitEvent(SystemIngestionEvent.TransientAlertPost(alert))
        }

        lastChargingState = isCharging
        batteryBaselineCaptured = true
    }

    // --- Ringer & DND Handling ---

    private fun handleRingerModeChanged() {
        val currentMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val modeEnum = when (currentMode) {
            AudioManager.RINGER_MODE_SILENT -> SystemIngestionEvent.SystemState.RingerDnd.RingerMode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> SystemIngestionEvent.SystemState.RingerDnd.RingerMode.VIBRATE
            else -> SystemIngestionEvent.SystemState.RingerDnd.RingerMode.NORMAL
        }

        val filter = notificationManager?.currentInterruptionFilter
            ?: NotificationManager.INTERRUPTION_FILTER_ALL
        val isDnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL

        eventBus.emitEvent(
            SystemIngestionEvent.SystemState.RingerDnd(
                ringerMode = modeEnum,
                isDndActive = isDnd,
                interruptionFilter = filter
            )
        )

        // Trigger iOS-style Silent Switch alert peek if ringer mode physically changed
        if (currentMode != lastRingerMode) {
            val isSilent = currentMode == AudioManager.RINGER_MODE_SILENT ||
                currentMode == AudioManager.RINGER_MODE_VIBRATE
            val alert = TransientAlert(
                id = "ringer_switch_${System.currentTimeMillis()}",
                title = if (isSilent) "Silent" else "Ring",
                message = if (currentMode == AudioManager.RINGER_MODE_VIBRATE) "Vibrate" else null,
                leadingSlot = IslandSlotContent.Symbol(
                    if (isSilent) IslandSymbol.BELL_SLASH else IslandSymbol.BELL,
                    tintHex = if (isSilent) "#FF453A" else "#FFFFFF"
                ),
                trailingSlot = IslandSlotContent.Text(
                    if (isSilent) "Silent" else "Ring",
                    colorHex = if (isSilent) "#FF453A" else "#FFFFFF"
                ),
                durationMillis = 2600L,
                rank = PriorityRank.TRANSIENT_SYSTEM_ALERT,
                haptic = true
            )
            eventBus.emitEvent(SystemIngestionEvent.TransientAlertPost(alert))
            lastRingerMode = currentMode
        }
    }

    /**
     * DND changed without the ringer mode changing.
     *
     * Deliberately does NOT reuse [handleRingerModeChanged]: that would be harmless
     * today only because the mode is unchanged and the alert branch is skipped, but
     * it makes the alert dependent on a side condition instead of on intent.
     */
    private fun handleDndChanged() {
        val filter = notificationManager?.currentInterruptionFilter
            ?: NotificationManager.INTERRUPTION_FILTER_ALL
        val currentMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val modeEnum = when (currentMode) {
            AudioManager.RINGER_MODE_SILENT -> SystemIngestionEvent.SystemState.RingerDnd.RingerMode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> SystemIngestionEvent.SystemState.RingerDnd.RingerMode.VIBRATE
            else -> SystemIngestionEvent.SystemState.RingerDnd.RingerMode.NORMAL
        }
        eventBus.emitEvent(
            SystemIngestionEvent.SystemState.RingerDnd(
                ringerMode = modeEnum,
                isDndActive = filter != NotificationManager.INTERRUPTION_FILTER_ALL,
                interruptionFilter = filter
            )
        )
    }

    // --- Hotspot / Tethering Handling ---

    private fun handleHotspotStateChanged(intent: Intent) {
        val state = intent.getIntExtra(EXTRA_WIFI_AP_STATE, 0)
        val isEnabled = state == WIFI_AP_STATE_ENABLED

        // The connected-client count is NOT available from this broadcast. Reporting
        // a hard-coded 1 was fabricated data, so it is reported as unknown (0) and
        // the peek no longer claims a connection count.
        eventBus.emitEvent(
            SystemIngestionEvent.SystemState.Hotspot(
                isEnabled = isEnabled,
                connectedClientsCount = 0
            )
        )

        if (isEnabled) {
            val alert = TransientAlert(
                id = "hotspot_${System.currentTimeMillis()}",
                title = "Personal Hotspot",
                message = null,
                leadingSlot = IslandSlotContent.Symbol(IslandSymbol.WIFI, tintHex = "#32D74B"),
                trailingSlot = IslandSlotContent.Text("On", colorHex = "#32D74B"),
                durationMillis = 2500L,
                rank = PriorityRank.TRANSIENT_SYSTEM_ALERT
            )
            eventBus.emitEvent(SystemIngestionEvent.TransientAlertPost(alert))
        }
    }

    // --- Bluetooth Accessory ---

    private fun handleBluetoothConnected(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            ?: return
        if (!hasBluetoothConnect()) {
            // Without BLUETOOTH_CONNECT every device read throws. Announce the
            // connection with no identity rather than crashing the service.
            Log.i(TAG, "Bluetooth device connected but BLUETOOTH_CONNECT is not granted")
            return
        }
        val identity = readIdentity(device)
        val deviceName = identity?.first ?: "Bluetooth Device"
        val batteryLevel = extractDeviceBatteryLevel(intent, device)

        eventBus.emitEvent(
            SystemIngestionEvent.SystemState.BluetoothAccessory(
                deviceAddress = identity?.second ?: "",
                deviceName = deviceName,
                isConnected = true,
                isAudioOutput = true,
                genericBatteryPercent = batteryLevel
            )
        )

        val alert = TransientAlert(
            id = "bt_connect_${identity?.second ?: deviceName}",
            title = deviceName,
            message = batteryLevel?.let { "$it%" },
            leadingSlot = IslandSlotContent.Symbol(IslandSymbol.HEADSET, tintHex = "#0A84FF"),
            trailingSlot = if (batteryLevel == null) null else {
                IslandSlotContent.Symbol(IslandSymbol.BATTERY, tintHex = "#FFFFFF")
            },
            durationMillis = 3400L,
            rank = PriorityRank.TRANSIENT_SYSTEM_ALERT
        )
        eventBus.emitEvent(SystemIngestionEvent.TransientAlertPost(alert))
    }

    private fun handleBluetoothDisconnected(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            ?: return
        if (!hasBluetoothConnect()) return
        val identity = readIdentity(device)
        eventBus.emitEvent(
            SystemIngestionEvent.SystemState.BluetoothAccessory(
                deviceAddress = identity?.second ?: "",
                deviceName = identity?.first ?: "Bluetooth Device",
                isConnected = false,
                isAudioOutput = false
            )
        )
    }

    private fun handleBluetoothBatteryChanged(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            ?: return
        if (!hasBluetoothConnect()) return
        val battery = extractDeviceBatteryLevel(intent, device) ?: return
        val identity = readIdentity(device)
        eventBus.emitEvent(
            SystemIngestionEvent.SystemState.BluetoothAccessory(
                deviceAddress = identity?.second ?: "",
                deviceName = identity?.first ?: "Bluetooth Device",
                isConnected = true,
                isAudioOutput = true,
                genericBatteryPercent = battery
            )
        )
    }

    /**
     * Display name + MAC address of a bonded/connected device.
     *
     * Both reads require the runtime `BLUETOOTH_CONNECT` permission from API 31
     * and throw `SecurityException` without it. Every caller checks
     * [hasBluetoothConnect] first; the annotation documents that precondition for
     * lint, which cannot see through `runCatching`, and the try/catch keeps a
     * revoked permission from taking down the service process.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun readIdentity(device: BluetoothDevice): Pair<String, String>? = runCatching {
        val name = device.name ?: return@runCatching null
        name to device.address
    }.getOrNull()

    /**
     * The accessory's own reported battery level, when one is present.
     *
     * `ACTION_BATTERY_CHANGED` carries it as an extra on devices that implement it;
     * the hidden `BluetoothDevice.getBatteryLevel()` reflection fallback covers the
     * rest. Both are best-effort — returning `null` simply means "unknown".
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun extractDeviceBatteryLevel(intent: Intent, device: BluetoothDevice? = null): Int? {
        val extraBattery = intent.getIntExtra(EXTRA_BLUETOOTH_BATTERY_LEVEL, -1)
        if (extraBattery in 0..100) return extraBattery

        if (device != null) {
            val reflected = runCatching {
                val method = device.javaClass.getMethod("getBatteryLevel")
                method.invoke(device) as? Int
            }.getOrNull()
            if (reflected != null && reflected in 0..100) return reflected
        }
        return null
    }
}
