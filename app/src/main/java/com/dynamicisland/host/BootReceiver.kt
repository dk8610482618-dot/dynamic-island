package com.dynamicisland.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.dynamicisland.engine.ingestion.notifications.DynamicIslandNotificationListener
import com.dynamicisland.engine.service.DynamicIslandService
import com.dynamicisland.engine.settings.BehaviorPreferences

/**
 * BroadcastReceiver listening for device reboot and app package update events.
 * Ensures the Dynamic Island background service and NotificationListenerService
 * bindings are immediately restored without requiring the user to manually reopen the app.
 *
 * Security & Integrity (Strix / Elite Android Engineer):
 * - Accepts only system-protected broadcasts (BOOT_COMPLETED, MY_PACKAGE_REPLACED).
 * - Excludes vendor-specific actions (e.g. QUICKBOOT_POWERON) which are not system-protected
 *   and vulnerable to intent spoofing by unprivileged third-party applications.
 * - Rejects sticky broadcasts to prevent replayed or mocked intents.
 * - Safely handles potential platform ForegroundServiceStartNotAllowedException on API 31+.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "IslandBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        // Reject sticky broadcasts: genuine BOOT_COMPLETED and MY_PACKAGE_REPLACED are never sticky.
        if (isInitialStickyBroadcast) {
            Log.w(TAG, "Rejected unexpected sticky broadcast for action: $action")
            return
        }

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Device boot / update event received: $action")

                // Re-establish notification listener binding if disconnected or stale
                runCatching {
                    DynamicIslandNotificationListener.ensureBound(context)
                }.onFailure { e ->
                    Log.w(TAG, "Failed to re-establish notification listener binding on boot/update", e)
                }

                // Auto-start the overlay service if overlay permission is granted
                val options = BehaviorPreferences.get(context).options.value
                if (options.enabled && (action == Intent.ACTION_MY_PACKAGE_REPLACED || options.startOnBoot) && Settings.canDrawOverlays(context)) {
                    runCatching {
                        DynamicIslandService.start(context)
                        Log.i(TAG, "DynamicIslandService restored on boot/update.")
                    }.onFailure { e ->
                        Log.e(TAG, "Unable to restore DynamicIslandService on boot: ${e.message}", e)
                    }
                }
            }
            else -> {
                Log.w(TAG, "Ignoring untrusted or unhandled broadcast action: $action")
            }
        }
    }
}
