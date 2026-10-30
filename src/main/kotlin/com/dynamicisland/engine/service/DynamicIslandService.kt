package com.dynamicisland.engine.service

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import com.dynamicisland.engine.ui.IslandExpandedPanel
import android.app.KeyguardManager
import android.app.AppOpsManager
import com.dynamicisland.core.geometry.*
import com.dynamicisland.engine.ui.IslandHaptics
import com.dynamicisland.engine.core.model.ActivityKind
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.savedstate.*
import com.dynamicisland.core.geometry.CapsuleAnchor
import com.dynamicisland.core.geometry.IslandPlacement
import com.dynamicisland.core.geometry.PixelRect
import com.dynamicisland.engine.core.bus.IslandUserCommand
import com.dynamicisland.engine.core.model.IslandActivity
import com.dynamicisland.engine.core.model.IslandState
import com.dynamicisland.engine.core.model.MediaPlaybackSnapshot
import com.dynamicisland.engine.repository.DynamicIslandRepository
import com.dynamicisland.engine.settings.BehaviorPreferences
import com.dynamicisland.engine.settings.IslandSettings
import com.dynamicisland.engine.ui.FixedIslandCapsule
import com.dynamicisland.engine.window.OverlayWindowPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns bounded compact/expanded windows using public Android window APIs only. */
class DynamicIslandService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    companion object {
        const val ACTION_STOP = "com.dynamicisland.engine.action.STOP"
        const val ACTION_REFRESH_PERMISSIONS = "com.dynamicisland.engine.action.REFRESH_PERMISSIONS"
        private const val TAG = "DynamicIslandService"
        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()
        val isRunning: Boolean get() = _running.value
        private val _error = MutableStateFlow<String?>(null)
        val error = _error.asStateFlow()
        private val _placement = MutableStateFlow<IslandPlacement.PlacementResult?>(null)
        val placement = _placement.asStateFlow()
        private val _envelope = MutableStateFlow<IslandPlacement.TravelEnvelope?>(null)
        val envelope = _envelope.asStateFlow()
        private val _state = MutableStateFlow<IslandState>(IslandState.Closed)
        val state = _state.asStateFlow()
        private val _media = MutableStateFlow<IslandActivity?>(null)
        val media = _media.asStateFlow()
        private val _playback = MutableStateFlow(MediaPlaybackSnapshot())
        val playback = _playback.asStateFlow()
        private val _actionStatus = MutableStateFlow<String?>(null)
        val actionStatus = _actionStatus.asStateFlow()
        fun cancelTimer(activityId: String) { command(IslandUserCommand.CancelTimer(activityId)) }
        private val _call = MutableStateFlow<com.dynamicisland.engine.core.bus.SystemIngestionEvent.Telecom.CallStateChanged?>(null)
        val call = _call.asStateFlow()
        private val _battery = MutableStateFlow<com.dynamicisland.engine.core.bus.SystemIngestionEvent.SystemState.Battery?>(null)
        val battery = _battery.asStateFlow()
        private var instance: DynamicIslandService? = null
        fun answer() { instance?.let { if (_call.value?.isControllable == true && it.keyguard?.isKeyguardLocked != true) _call.value?.callId?.let(it.repository::callAnswer) } }
        fun hangup() { instance?.let { if (_call.value?.isControllable == true && it.keyguard?.isKeyguardLocked != true) _call.value?.callId?.let(it.repository::callHangup) } }
        fun toggleMute() { instance?.let { if (it.keyguard?.isKeyguardLocked != true) it.repository.callToggleMute() } }
        fun toggleSpeaker() { instance?.let { if (it.keyguard?.isKeyguardLocked != true) it.repository.callToggleSpeaker() } }

        fun start(context: Context) {
            check(Settings.canDrawOverlays(context)) { "Allow display over other apps first" }
            val prefs = BehaviorPreferences.get(context)
            val previous = prefs.options.value.enabled
            prefs.update { it.copy(enabled = true) }
            try {
                ContextCompat.startForegroundService(context, Intent(context, DynamicIslandService::class.java))
            } catch (e: RuntimeException) {
                prefs.update { it.copy(enabled = previous) }
                _error.value = "Android refused background startup. Open Dynamic Island and press Start."
                throw e
            }
        }
        fun stop(context: Context) {
            BehaviorPreferences.get(context).update { it.copy(enabled = false) }
            context.stopService(Intent(context, DynamicIslandService::class.java))
        }
        fun command(command: IslandUserCommand) { instance?.repository?.sendUserCommand(command) }
        fun playPause() { instance?.let { if (it.keyguard?.isKeyguardLocked == true || !_playback.value.canPlayPause) return; if (_playback.value.isPlaying) it.repository.mediaPause() else it.repository.mediaPlay() } }
        fun next() { instance?.let { if (it.keyguard?.isKeyguardLocked != true && _playback.value.canNext) it.repository.mediaSkipNext() } }
        fun previous() { instance?.let { if (it.keyguard?.isKeyguardLocked != true && _playback.value.canPrevious) it.repository.mediaSkipPrevious() } }
        fun openMediaSource() { instance?.let { service ->
            if (service.keyguard?.isKeyguardLocked != true) com.dynamicisland.engine.util.PendingIntentCompat.send(service, _media.value?.deepLinkIntent)
        } }
        fun seek(position: Long) { instance?.let { if (it.keyguard?.isKeyguardLocked != true && _playback.value.canSeek) it.repository.mediaSeekTo(position) } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store
    private lateinit var repository: DynamicIslandRepository
    private lateinit var wm: WindowManager
    private lateinit var overlayContext: Context
    private var idleExpanded by mutableStateOf(false)
    private var panelVisible by mutableStateOf(false)
    private var windowAnimator: ValueAnimator? = null
    private var targetBounds: CapsuleAnchor.Window? = null
    private var targetExpanded = false
    private var windowCorner by mutableFloatStateOf(19f)
    private var stopping = false
    private val isExpanded: Boolean get() = !locked && (idleExpanded || repository.islandState.value is IslandState.Expanded || repository.islandState.value is IslandState.NotificationInbox)
    private lateinit var settings: IslandSettings
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var bubbleView: ComposeView? = null
    private var bubbleActivity by mutableStateOf<IslandActivity?>(null)
    private var bubbleRotation by mutableFloatStateOf(0f)
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var watchingOverlayPermission = false
    private val appOpsListener = AppOpsManager.OnOpChangedListener { op, packageId ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageId == packageName) {
            scope.launch { if (!Settings.canDrawOverlays(this@DynamicIslandService)) fail("Display permission was removed. Allow it again to resume.") }
        }
    }
    private var windowRotation by mutableFloatStateOf(0f)
    private var locked by mutableStateOf(true)
    private var receiverRegistered = false
    private val keyguard get() = getSystemService(KeyguardManager::class.java)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) collapsePanel()
            updateVisibility()
            updateWindow(animate = false)
            updateBubble()
        }
    }

    override fun onCreate() {
        super.onCreate()
        saved.performAttach()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        instance = this
        val display = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        overlayContext = if (Build.VERSION.SDK_INT >= 30 && display != null)
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else this
        wm = overlayContext.getSystemService(WindowManager::class.java)
        settings = IslandSettings.get(this)
        repository = DynamicIslandRepository(applicationContext)
        if (!BehaviorPreferences.get(this).options.value.enabled || !Settings.canDrawOverlays(this)) {
            fail("Allow display over other apps, then press Start.")
            return
        }
        try {
            startNotification()
            repository.start()
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            attachWindow()
            scope.launch {
                DynamicIslandAccessibilityService.isAccessibilityEnabled.collect { a11yActive ->
                    elevateWindowOverlay(a11yActive)
                }
            }
            scope.launch { repository.currentCall.collect { _call.value = it } }
            scope.launch { repository.batteryState.collect { _battery.value = it } }
            scope.launch { repository.islandState.collect {
                _state.value = it
                if (it !is IslandState.Closed) idleExpanded = false
                updateWindow(); updateBubble()
            } }
            scope.launch { repository.priorityArbiter.actionStatus.collect { _actionStatus.value = it } }
            scope.launch { repository.mediaPlayback.collect { _playback.value = it } }
            scope.launch { repository.priorityArbiter.mediaActivity.collect { _media.value = it } }
            scope.launch {
                combine(settings.offset, settings.widthScale, settings.heightScale,
                    BehaviorPreferences.get(this@DynamicIslandService).options) { _, _, _, _ -> Unit }
                    .collect { updateWindow(); updateVisibility(); updateBubble() }
            }
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, appOpsListener)
            watchingOverlayPermission = true
            _error.value = null
        } catch (e: RuntimeException) {
            Log.e(TAG, "Overlay startup failed", e)
            fail("The overlay could not start. Check display permission and ColorOS battery settings, then retry.")
        }
    }

    private fun attachWindow() {
        val v = ComposeView(overlayContext).apply {
            setViewTreeLifecycleOwner(this@DynamicIslandService)
            setViewTreeSavedStateRegistryOwner(this@DynamicIslandService)
            setViewTreeViewModelStoreOwner(this@DynamicIslandService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@DynamicIslandService))
            setContent {
                val state by repository.islandState.collectAsState()
                val playback by repository.mediaPlayback.collectAsState()
                val options by BehaviorPreferences.get(this@DynamicIslandService).options.collectAsState()
                val call by repository.currentCall.collectAsState()
                val count by repository.notificationCount.collectAsState()
                val status by repository.priorityArbiter.actionStatus.collectAsState()
                val microphoneActive by repository.isMicrophoneInUse.collectAsState()
                val cameraActive by repository.isCameraInUse.collectAsState()
                val animateContent = !options.reducedMotion && options.motion != MotionPreset.NONE && ValueAnimator.areAnimatorsEnabled()
                Box(Modifier.fillMaxSize()) {
                    if (!panelVisible || locked) FixedIslandCapsule(state, playback, locked, windowRotation,
                        onOpen = { openPanel(false) },
                        onPlayPause = { if (!locked && keyguard?.isKeyguardLocked != true) { IslandHaptics.perform(this@DynamicIslandService);playPause() } },
                        options = options, onGesture = { routeGesture(it,false) },
                        cornerRadius = androidx.compose.ui.unit.Dp(windowCorner),
                        microphoneActive = microphoneActive,
                        cameraActive = cameraActive,
                        isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                    // Removing this subtree immediately on lock avoids retaining private content
                    // during an exit animation. Re-targeting visibility cancels any entrance.
                    if (!locked) AnimatedVisibility(visible = panelVisible,
                        enter = if (animateContent) fadeIn(tween(120)) + scaleIn(initialScale = .98f, animationSpec = tween(160)) else EnterTransition.None,
                        exit = ExitTransition.None) {
                        IslandExpandedPanel(state, playback, call, count,
                            onClose = ::collapsePanel, onOpenApp = ::openDashboard,
                            onOpenSource = ::openSource,
                            onPlayPause = ::playPause, onNext = ::next, onPrevious = ::previous,
                            onSeek = ::seek, onCommand = ::command,
                            onAnswer = ::answer, onHangup = ::hangup, onMute = ::toggleMute,
                            onSpeaker = ::toggleSpeaker, actionStatus = status,
                            options = options)
                    }
                    if (panelVisible && !locked && (microphoneActive || cameraActive)) {
                        val privacyColor = when {
                            microphoneActive && cameraActive -> Color(0xFF0A84FF)
                            cameraActive -> Color(0xFF30D158)
                            else -> Color(0xFFFF9F0A)
                        }
                        val privacyDesc = when {
                            microphoneActive && cameraActive -> "Microphone and camera in use"
                            cameraActive -> "Camera in use"
                            else -> "Microphone in use"
                        }
                        Box(Modifier.align(Alignment.CenterEnd).padding(end = 3.dp).size(5.dp)
                            .background(privacyColor, CircleShape)
                            .semantics { contentDescription = privacyDesc })
                    }
                }
            }
        }
        view = v
        val bounds = resolveBounds()
                val isA11yActive = DynamicIslandAccessibilityService.activeServiceInstance != null
        val p = OverlayWindowPolicy.createLayoutParams(
            bounds.width, bounds.height,
            useAccessibilityOverlay = isA11yActive
        ).apply {
            x = bounds.left; y = bounds.top
        }
        params = p
        windowRotation = bounds.rotation
        v.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && isExpanded) collapsePanel()
            false
        }
        wm.addView(v, p)
        v.setOnApplyWindowInsetsListener { _, insets ->
            // API 28/29 cutout information becomes available only after attachment.
            v.post { if (v.isAttachedToWindow) updateWindow(animate = false) }
            insets
        }
        updateVisibility()
        _running.value = true
    }

    private fun elevateWindowOverlay(a11yActive: Boolean) {
        val v = view ?: return
        val p = params ?: return
        val targetType = if (a11yActive) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        if (p.type != targetType) {
            p.type = targetType
            runCatching {
                if (v.isAttachedToWindow) {
                    wm.removeViewImmediate(v)
                    wm.addView(v, p)
                    v.post { if (v.isAttachedToWindow) updateWindow(animate = false) }
                }
            }.onFailure {
                Log.w(TAG, "Dynamic window elevation to $targetType failed", it)
            }
        }
    }

    private fun routeGesture(action: GestureAction, secondary: Boolean) {
        val current = repository.islandState.value
        val activity = if (secondary) bubbleActivity else when(current) {
            is IslandState.Compact -> current.activity
            is IslandState.Minimal -> current.primary
            is IslandState.Expanded -> current.activity
            else -> null
        }
        val allowed = GesturePolicy.allowed(action, activity?.kind == ActivityKind.MEDIA_SESSION, keyguard?.isKeyguardLocked == true)
        if (allowed == GestureAction.NONE) return
        IslandHaptics.perform(this)
        when(allowed) {
            GestureAction.OPEN_DASHBOARD -> openPanel(secondary)
            GestureAction.OPEN_SOURCE -> if (activity != null) openSource(activity) else openDashboard()
            GestureAction.PLAY_PAUSE -> if(_playback.value.canPlayPause) playPause()
            GestureAction.NEXT_TRACK -> if(_playback.value.canNext) next()
            GestureAction.PREVIOUS_TRACK -> if(_playback.value.canPrevious) previous()
            GestureAction.CYCLE_ACTIVITY -> command(IslandUserCommand.CycleActivityFocus(1))
            GestureAction.DISMISS_ALERT -> command(IslandUserCommand.DismissAlertPeek)
            GestureAction.NONE -> Unit
        }
    }

    private fun removeBubble() {
        bubbleView?.let { if(it.isAttachedToWindow) runCatching { wm.removeViewImmediate(it) }; it.disposeComposition() }
        bubbleView=null; bubbleParams=null; bubbleActivity=null
    }

    private fun updateBubble() {
        val options=BehaviorPreferences.get(this).options.value
        val current=repository.islandState.value as? IslandState.Minimal
        if (isExpanded || !options.secondaryBubble || current == null || locked || view?.visibility != View.VISIBLE || !Settings.canDrawOverlays(this)) { removeBubble(); return }
        val primary=resolveBounds()
        val size=resources.displayMetrics
        val displayBounds=if(Build.VERSION.SDK_INT>=30) wm.maximumWindowMetrics.bounds else android.graphics.Rect(0,0,size.widthPixels,size.heightPixels)
        val bounds=SecondaryBubblePlacement.resolve(primary,displayBounds.width(),displayBounds.height(),(34*size.density).toInt(),(8*size.density).toInt(),resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        if(bounds==null) { removeBubble();return }
        bubbleActivity=current.secondary; bubbleRotation=bounds.rotation
        if(bubbleView==null) {
            val v=ComposeView(overlayContext).apply {
                setViewTreeLifecycleOwner(this@DynamicIslandService);setViewTreeSavedStateRegistryOwner(this@DynamicIslandService);setViewTreeViewModelStoreOwner(this@DynamicIslandService)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@DynamicIslandService))
                setContent {
                    val prefs by BehaviorPreferences.get(this@DynamicIslandService).options.collectAsState()
                    val playback by repository.mediaPlayback.collectAsState()
                    bubbleActivity?.let { activity -> FixedIslandCapsule(IslandState.Compact(activity),playback,locked,bubbleRotation,::openDashboard,{},prefs,{routeGesture(it,true)},secondary=true) }
                }
            }
            val isA11yActive = DynamicIslandAccessibilityService.activeServiceInstance != null
            val p=OverlayWindowPolicy.createLayoutParams(bounds.width,bounds.height,useAccessibilityOverlay = isA11yActive).apply{x=bounds.left;y=bounds.top}
            try { wm.addView(v,p);bubbleView=v;bubbleParams=p } catch(e:RuntimeException) { v.disposeComposition();_error.value="Secondary bubble could not attach. The primary island is still available.";Log.w(TAG,"Secondary window refused",e) }
        } else {
            val p=bubbleParams ?: return
            if(p.x==bounds.left && p.y==bounds.top && p.width==bounds.width && p.height==bounds.height) return
            p.x=bounds.left;p.y=bounds.top;p.width=bounds.width;p.height=bounds.height
            try { wm.updateViewLayout(bubbleView,p) } catch(e:RuntimeException) { removeBubble() }
        }
    }

    private fun resolveBounds(expanded: Boolean = false): CapsuleAnchor.Window {
        val metrics = overlayContext.resources.displayMetrics
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        var cutout = view?.rootWindowInsets?.displayCutout
        var top = 0
        var bottom = 0
        var safeLeft = 0
        var safeRight = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.maximumWindowMetrics
            width = m.bounds.width(); height = m.bounds.height()
            cutout = m.windowInsets.displayCutout
            val bars = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            top = bars.top; bottom = bars.bottom; safeLeft = bars.left; safeRight = bars.right
        } else {
            @Suppress("DEPRECATION")
            val size = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
            width = size.x; height = size.y
            @Suppress("DiscouragedApi")
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id != 0) top = resources.getDimensionPixelSize(id)
            @Suppress("DEPRECATION")
            view?.rootWindowInsets?.let { bottom = it.stableInsetBottom; safeLeft = it.stableInsetLeft; safeRight = it.stableInsetRight }
        }
        val hole = cutout?.boundingRects?.filter { !it.isEmpty }?.minByOrNull { it.width() * it.height() }
        val o = settings.offset.value
        val compact = CapsuleAnchor.resolve(width, height, metrics.density,
            hole?.let { PixelRect(it.left, it.top, it.right, it.bottom) },
            settings.widthScale.value, settings.heightScale.value, o.offsetXDp, o.offsetYDp,
            maxOf(top, cutout?.safeInsetTop ?: 0), BehaviorPreferences.get(this).options.value.clearSystemBars,
            maxOf(safeLeft, cutout?.safeInsetLeft ?: 0), maxOf(safeRight, cutout?.safeInsetRight ?: 0),
            maxOf(bottom, cutout?.safeInsetBottom ?: 0))
        val rawWindow = if (!expanded) compact else ExpandedAnchor.resolve(compact, width, height,
            metrics.density, overlayContext.resources.configuration.fontScale,
            maxOf(top, cutout?.safeInsetTop ?: 0), maxOf(bottom, cutout?.safeInsetBottom ?: 0),
            maxOf(safeLeft, cutout?.safeInsetLeft ?: 0), maxOf(safeRight, cutout?.safeInsetRight ?: 0))
        return ExpandedRegionLayoutSolver.resolvePostureWindow(
            candidate = rawWindow,
            screenWidthPx = width,
            screenHeightPx = height,
            posture = BehaviorPreferences.get(this).options.value.devicePosture,
            reservedCrease = null
        )
    }

    private fun updateWindow(animate: Boolean = true) {
        val v = view ?: return
        val p = params ?: return
        if (stopping || !v.isAttachedToWindow) return
        if (!Settings.canDrawOverlays(this)) { fail("Display permission was removed. Allow it again to resume."); return }
        val expanded = isExpanded
        val b = resolveBounds(expanded)
        val options = BehaviorPreferences.get(this).options.value
        val refresh = if (options.highRefreshRate) 120f else 0f
        if (targetBounds == b && targetExpanded == expanded && p.preferredRefreshRate == refresh) return
        windowAnimator?.cancel()
        windowAnimator = null
        targetBounds = b
        targetExpanded = expanded
        val fromCorner = windowCorner
        val toCorner = if (expanded) 28f else 19f
        p.preferredRefreshRate = refresh
        // Android may choose a different mode for thermal, power or policy reasons.
        if (Build.VERSION.SDK_INT >= 35) v.requestedFrameRate = refresh
        windowRotation = if (expanded) 0f else b.rotation
        panelVisible = false
        // Outside touches are observed without covering or consuming another app's window.
        p.flags = OverlayWindowPolicy.STANDARD_FLAGS or if (expanded) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0
        val from = CapsuleAnchor.Window(p.x, p.y, p.width, p.height, 0f)
        fun applyBounds(fraction: Float) {
            windowCorner = fromCorner + (toCorner - fromCorner) * fraction
            fun lerp(a: Int, end: Int) = (a + (end - a) * fraction).toInt()
            val x = lerp(from.left, b.left); val y = lerp(from.top, b.top)
            val width = lerp(from.width, b.width); val height = lerp(from.height, b.height)
            // Avoid re-layout when consecutive animation samples round to the same pixel.
            val changed = p.x != x || p.y != y || p.width != width || p.height != height
            p.x = x; p.y = y; p.width = width; p.height = height
            if (!changed && fraction != 1f) return
            try { wm.updateViewLayout(v, p) } catch (e: RuntimeException) {
                Log.e(TAG, "Window update failed", e)
                fail("Android removed the overlay. Open Dynamic Island to restart it.")
            }
        }
        val animateSize = from.width != b.width || from.height != b.height
        if (!animate || !animateSize || options.reducedMotion || options.motion == MotionPreset.NONE || !ValueAnimator.areAnimatorsEnabled()) {
            applyBounds(1f); panelVisible = expanded && !stopping
        } else {
            windowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (options.motion == MotionPreset.RESPONSIVE) 180L else 260L
                interpolator = if (Build.VERSION.SDK_INT >= 21) {
                    android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)
                } else {
                    DecelerateInterpolator(1.5f)
                }
                addUpdateListener { applyBounds(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled && !stopping) panelVisible = expanded && isExpanded
                    }
                })
                start()
            }
        }
    }

    private fun openPanel(secondary: Boolean) {
        if (locked || keyguard?.isKeyguardLocked == true) return
        if (isExpanded) { collapsePanel(); return }
        if (repository.islandState.value is IslandState.Closed && repository.notificationCount.value == 0) {
            idleExpanded = true
            updateWindow(); updateBubble()
        } else repository.sendUserCommand(if (secondary) IslandUserCommand.TapSecondary else IslandUserCommand.TapPrimary)
    }

    private fun collapsePanel() {
        idleExpanded = false
        panelVisible = false
        repository.sendUserCommand(IslandUserCommand.CollapseExpanded)
        repository.sendUserCommand(IslandUserCommand.CloseInbox)
        // For an idle panel no arbiter state changes, so update immediately too.
        updateWindow()
        updateBubble()
    }

    private fun openSource(activity: IslandActivity) {
        if (keyguard?.isKeyguardLocked == true) return
        collapsePanel()
        if (!com.dynamicisland.engine.util.PendingIntentCompat.send(this, activity.deepLinkIntent))
            Toast.makeText(this, "Open the player from your app drawer", Toast.LENGTH_SHORT).show()
    }

    private fun updateVisibility() {
        locked = keyguard?.isKeyguardLocked == true
        val o = BehaviorPreferences.get(this).options.value
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
        repository.eventBus.emitEvent(com.dynamicisland.engine.core.bus.SystemIngestionEvent.SystemState.KeyguardLocked(locked))
        if (!interactive || locked) {
            idleExpanded = false; panelVisible = false
            windowAnimator?.cancel(); windowAnimator = null; targetBounds = null
            repository.sendUserCommand(IslandUserCommand.CollapseExpanded)
            repository.sendUserCommand(IslandUserCommand.CloseInbox)
        }
        view?.visibility = if (!interactive || (locked && !o.showOnLockScreen) || (landscape && o.hideInLandscape)) View.GONE else View.VISIBLE
    }

    private fun openDashboard() {
        if (keyguard?.isKeyguardLocked == true) {
            Toast.makeText(this, "Unlock your phone to use island controls", Toast.LENGTH_SHORT).show()
            return
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            try { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            catch (e: RuntimeException) {
                Log.w(TAG, "Dashboard launch was refused", e)
                Toast.makeText(this, "Open Dynamic Island from the app drawer", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fail(message: String) {
        stopping = true
        windowAnimator?.cancel(); windowAnimator = null
        _error.value = message
        removeBubble()
        _running.value = false
        view?.let { if (it.isAttachedToWindow) runCatching { wm.removeViewImmediate(it) } }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !BehaviorPreferences.get(this).options.value.enabled) {
            BehaviorPreferences.get(this).update { it.copy(enabled = false) }
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH_PERMISSIONS && ::repository.isInitialized) repository.refreshPermissions()
        if (!stopping) { updateVisibility(); updateWindow(animate = false); updateBubble() }
        // User-enabled sticky restoration is still subject to Android/OEM background rules.
        return START_STICKY
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); targetBounds = null; updateVisibility(); updateWindow(animate = false); updateBubble() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (::repository.isInitialized) repository.onTrimMemory() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun startNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("island_service", "Island status", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val n = NotificationCompat.Builder(this, "island_service")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Dynamic Island is running")
            .setContentText("Tap to open controls · Stop to remove the island").setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .apply { if (launch != null) setContentIntent(PendingIntent.getActivity(this@DynamicIslandService, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            .build()
        ServiceCompat.startForeground(this, 1001, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onDestroy() {
        stopping = true
        windowAnimator?.cancel(); windowAnimator = null
        _running.value = false
        instance = null
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        if (watchingOverlayPermission) getSystemService(AppOpsManager::class.java).stopWatchingMode(appOpsListener)
        removeBubble()
        view?.let { if (it.isAttachedToWindow) runCatching { wm.removeViewImmediate(it) }; it.disposeComposition() }
        view = null
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (::repository.isInitialized) repository.stop()
        scope.cancel(); store.clear()
        _actionStatus.value = null
        _call.value = null; _battery.value = null
        _state.value = IslandState.Closed; _media.value = null; _playback.value = MediaPlaybackSnapshot()
        super.onDestroy()
    }
}
