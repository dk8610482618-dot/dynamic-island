package com.dynamicisland.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val benchmark=MacrobenchmarkRule()
    @Test fun coldStart() = benchmark.measureRepeated(
        packageName="com.dynamicisland.host",metrics=listOf(StartupTimingMetric(),FrameTimingMetric()),
        iterations=5,startupMode=StartupMode.COLD,compilationMode=CompilationMode.None(),
        setupBlock={
            if(android.os.Build.VERSION.SDK_INT>=33) device.executeShellCommand("pm grant com.dynamicisland.host android.permission.POST_NOTIFICATIONS")
            pressHome()
        }
    ) { startActivityAndWait();check(device.wait(Until.hasObject(By.text("Dynamic Island")),10_000)) }
}
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile=BaselineProfileRule()
    @Test fun generate() = profile.collect(packageName="com.dynamicisland.host",includeInStartupProfile=true) {
        if(android.os.Build.VERSION.SDK_INT>=33) device.executeShellCommand("pm grant com.dynamicisland.host android.permission.POST_NOTIFICATIONS")
        pressHome();startActivityAndWait()
        check(device.wait(Until.hasObject(By.text("Dynamic Island")),10_000))
        device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/4,12)
    }
}
