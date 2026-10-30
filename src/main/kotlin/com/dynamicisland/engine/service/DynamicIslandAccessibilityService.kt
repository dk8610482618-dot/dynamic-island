package com.dynamicisland.engine.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provides TYPE_ACCESSIBILITY_OVERLAY elevation for the Dynamic Island.
 *
 * Configured with eventTypes = 0 and default flags for:
 * 1. True zero-overhead / zero-battery passive elevation.
 * 2. Elevation above system status bar icons (TYPE_STATUS_BAR).
 * 3. Non-invasive operation (no window content scraping, no gesture injection).
 */
class DynamicIslandAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Opt-out of receiving accessibility events to achieve zero IPC/battery overhead
        serviceInfo = serviceInfo.apply {
            eventTypes = 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
        }
        _isAccessibilityEnabled.value = true
        activeServiceInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Zero-op: No events received due to eventTypes = 0
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _isAccessibilityEnabled.value = false
        if (activeServiceInstance == this) {
            activeServiceInstance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        _isAccessibilityEnabled.value = false
        if (activeServiceInstance == this) {
            activeServiceInstance = null
        }
    }

    companion object {
        private val _isAccessibilityEnabled = MutableStateFlow(false)
        val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

        @Volatile
        var activeServiceInstance: DynamicIslandAccessibilityService? = null
            private set
    }
}
