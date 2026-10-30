package com.dynamicisland.host

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.dynamicisland.engine.service.DynamicIslandService
import com.dynamicisland.engine.settings.BehaviorPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IslandUiTest {
    @get:Rule val compose=createAndroidComposeRule<VisualTestActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun shell(command:String):String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {it.readText()}
    }
    @Before fun unlockTestDevice() {
        UiDevice.getInstance(instrumentation).wakeUp()
        shell("wm dismiss-keyguard")
    }
    @Test fun capturesAllCapsuleStatesWithoutChangingFootprint() {
        val cases=listOf("Idle","Music","Incoming call","Active call","Timer","Stopwatch","Navigation","Charging","Low battery","Bluetooth","Notification","Two activities")
        val folder=File(compose.activity.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
        var expectedWidth=0f;var expectedHeight=0f
        cases.forEach { name ->
            compose.runOnUiThread {compose.activity.scenario=name}
            compose.waitForIdle()
            val node=compose.onNodeWithContentDescription("Primary island. Open controls",useUnmergedTree=true)
            val bounds=node.fetchSemanticsNode().boundsInRoot
            if(expectedWidth==0f){expectedWidth=bounds.width;expectedHeight=bounds.height}
            assertEquals(expectedWidth,bounds.width,0.5f);assertEquals(expectedHeight,bounds.height,0.5f)
            val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
            assertEquals(Color.BLACK,bitmap.getPixel(bounds.center.x.toInt(),bounds.center.y.toInt()))
            File(folder,name.replace(' ','_')+".png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun entireCompactCapsuleOpensControlsIncludingTheMusicIndicator() {
        compose.runOnUiThread {compose.activity.scenario="Music"}
        compose.onNodeWithContentDescription("Primary island. Open controls", useUnmergedTree=true)
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 12f, height / 2f)) }
        compose.runOnIdle {assertEquals(0,compose.activity.plays);assertEquals(1,compose.activity.taps)}
    }
    @Test fun boundedWindowPassesOutsideTouchesAndStopsOnRevocation() {
        val context=compose.activity
        try {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            compose.runOnUiThread {DynamicIslandService.start(context)}
            compose.waitUntil(15000) {DynamicIslandService.isRunning}
            // The overlay sits near the top; the debug target remains touchable below it.
            val device=UiDevice.getInstance(instrumentation)
            val target = device.findObject(androidx.test.uiautomator.By.text("Underlying touch target"))
            assertNotNull(target);target.click()
            compose.runOnIdle { assertEquals(1,compose.activity.outsideTaps) }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW deny")
            compose.waitUntil(10000) {!DynamicIslandService.isRunning}
            assertFalse(BehaviorPreferences.get(context).options.value.enabled && DynamicIslandService.isRunning)
        } finally {
            compose.runOnUiThread {DynamicIslandService.stop(context)}
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }
    @Test fun realOverlayExpandsControlsMediaAndCollapsesOutside() {
        val context = compose.activity
        val device = UiDevice.getInstance(instrumentation)
        val listener = "${context.packageName}/com.dynamicisland.engine.ingestion.notifications.DynamicIslandNotificationListener"
        fun waitForDescription(description: String): androidx.test.uiautomator.UiObject2 {
            return device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.desc(description)), 20000)
                ?: error("Missing overlay control: $description")
        }
        try {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            shell("cmd notification allow_listener $listener")
            if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            compose.runOnUiThread {
                BehaviorPreferences.get(context).update { it.copy(reducedMotion = true) }
                context.startTestMedia()
                DynamicIslandService.start(context)
            }
            compose.waitUntil(30000) { DynamicIslandService.media.value?.title?.startsWith("Test track") == true }
            // Leave the host: controls must be part of the overlay above another app.
            device.pressHome()
            val compact = waitForDescription("Primary island. Open controls")
            val original = compact.visibleBounds
            device.click(original.centerX(), original.centerY())
            waitForDescription("Next track").click()
            compose.waitUntil(10000) { context.nextTracks == 1 }
            waitForDescription("Previous track").click()
            compose.waitUntil(10000) { context.previousTracks == 1 }
            waitForDescription("Pause").click()
            compose.waitUntil(10000) { context.transportPauses == 1 }
            waitForDescription("Play").click()
            compose.waitUntil(10000) { context.transportPlays == 1 }
            val folder=File(context.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
            device.takeScreenshot(File(folder,"Expanded_media_overlay.png"))
            waitForDescription("Collapse island").click()
            val closed = waitForDescription("Primary island. Open controls").visibleBounds
            assertEquals(original, closed)
            device.click(closed.centerX(), closed.centerY())
            waitForDescription("Collapse island")
            device.click(device.displayWidth/2, device.displayHeight*3/4)
            waitForDescription("Primary island. Open controls")
            assertNull(device.findObject(androidx.test.uiautomator.By.desc("Collapse island")))
            // Stop must remove the real window even after multiple open/close cycles.
            compose.runOnUiThread { DynamicIslandService.stop(context) }
            compose.waitUntil(10000) { !DynamicIslandService.isRunning }
        } finally {
            compose.runOnUiThread { DynamicIslandService.stop(context) }
            shell("cmd notification disallow_listener $listener")
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            device.pressHome()
        }
    }
    @Test fun rotationAndLargeFontsKeepCapsuleOnScreen() {
        val device=UiDevice.getInstance(instrumentation)
        try {
            device.setOrientationLeft();device.waitForIdle()
            compose.onNodeWithContentDescription("Primary island. Open controls",useUnmergedTree=true).assertIsDisplayed()
            shell("settings put system font_scale 1.5")
            device.waitForIdle()
            compose.onNodeWithContentDescription("Primary island. Open controls",useUnmergedTree=true).assertIsDisplayed()
        } finally {shell("settings put system font_scale 1.0");device.setOrientationNatural();device.unfreezeRotation()}
    }
}
