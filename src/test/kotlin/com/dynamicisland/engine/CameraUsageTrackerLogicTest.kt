package com.dynamicisland.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class CameraUsageTrackerLogicTest {

    private val monitoredCameraIds = ConcurrentHashMap.newKeySet<String>()
    private val unavailableCameraIds = ConcurrentHashMap.newKeySet<String>()
    private val activeTorchCameraIds = ConcurrentHashMap.newKeySet<String>()

    private fun recomputeCameraInUse(): Boolean {
        val activeSensors = unavailableCameraIds.filter { it !in activeTorchCameraIds }
        return activeSensors.isNotEmpty()
    }

    @Before
    fun setUp() {
        monitoredCameraIds.clear()
        unavailableCameraIds.clear()
        activeTorchCameraIds.clear()

        // Typical phone: "0" = back main, "1" = front, "2" = auxiliary depth (unmonitored)
        monitoredCameraIds.add("0")
        monitoredCameraIds.add("1")
    }

    @Test
    fun `single camera session triggers camera active`() {
        unavailableCameraIds.add("0")
        assertTrue(recomputeCameraInUse())

        unavailableCameraIds.remove("0")
        assertFalse(recomputeCameraInUse())
    }

    @Test
    fun `concurrent multi-camera keeps active state until both close`() {
        unavailableCameraIds.add("0")
        unavailableCameraIds.add("1")
        assertTrue(recomputeCameraInUse())

        unavailableCameraIds.remove("0")
        assertTrue(recomputeCameraInUse())

        unavailableCameraIds.remove("1")
        assertFalse(recomputeCameraInUse())
    }

    @Test
    fun `flashlight does not trigger camera in use`() {
        activeTorchCameraIds.add("0")
        unavailableCameraIds.add("0")

        assertFalse("Torch activation must not trigger camera active dot", recomputeCameraInUse())

        activeTorchCameraIds.remove("0")
        assertTrue("Camera recording with torch off must trigger camera active", recomputeCameraInUse())
    }

    @Test
    fun `auxiliary unmonitored camera unavailability is ignored`() {
        if (monitoredCameraIds.contains("2")) {
            unavailableCameraIds.add("2")
        }
        assertFalse("Unmonitored auxiliary sensor must not trigger camera active", recomputeCameraInUse())
    }

    @Test
    fun `stop lifecycle cleans up all state`() {
        unavailableCameraIds.add("0")
        activeTorchCameraIds.add("0")
        assertTrue(unavailableCameraIds.isNotEmpty())

        unavailableCameraIds.clear()
        activeTorchCameraIds.clear()
        monitoredCameraIds.clear()

        assertFalse(recomputeCameraInUse())
    }
}
