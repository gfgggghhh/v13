package com.veinfinder.app

import com.veinfinder.app.camera.CameraCapabilities
import com.veinfinder.app.camera.CameraControls
import com.veinfinder.app.camera.CameraMath
import com.veinfinder.app.camera.Res
import org.junit.Assert.*
import org.junit.Test

class CameraMathTest {
    private val full = CameraCapabilities(
        cameraId = "0", resolutions = listOf(Res(640, 480), Res(1280, 720), Res(1920, 1080)),
        evMin = -12, evMax = 12, evStep = 1f / 6f, aeLockSupported = true, awbLockSupported = true,
        manualSensor = true, exposureTimeMinNs = 50_000, exposureTimeMaxNs = 100_000_000, isoMin = 100, isoMax = 3200,
        minFocusDistance = 10f, afOffSupported = true, fpsRanges = listOf(15 to 30, 30 to 30, 24 to 60)
    )
    private val bare = CameraCapabilities(cameraId = "1")

    @Test fun parseResolution() {
        assertEquals(Res(640, 480), Res.parse("640x480")); assertEquals(Res(1280, 720), Res.parse(" 1280 X 720 "))
        assertNull(Res.parse("garbage")); assertNull(Res.parse("0x480")); assertNull(Res.parse(null))
    }

    @Test fun pickResolutionExactNearestAndAspect() {
        val l = listOf(Res(640, 480), Res(1280, 720), Res(1920, 1080), Res(1600, 1200))
        assertEquals(Res(1280, 720), CameraMath.pickResolution(l, Res(1280, 720)))
        assertEquals(Res(1600, 1200), CameraMath.pickResolution(l, Res(1500, 1125)))     // same 4:3 ratio wins over closer 16:9
        assertEquals(Res(640, 480), CameraMath.pickResolution(l, null))
        assertNull(CameraMath.pickResolution(emptyList(), Res(640, 480)))
    }

    @Test fun offerableFiltersAndSorts() {
        val o = CameraMath.offerable(listOf(Res(4000, 3000), Res(1920, 1080), Res(640, 480), Res(640, 480), Res(160, 120)))
        assertEquals(listOf(Res(640, 480), Res(1920, 1080)), o)
    }

    @Test fun fpsSelection() {
        assertEquals(listOf(15, 24, 30, 60), full.selectableFps)
        assertEquals(30 to 30, CameraMath.pickFpsRange(full.fpsRanges, 30))                // fixed range preferred
        assertEquals(24 to 60, CameraMath.pickFpsRange(full.fpsRanges, 60))
        assertNull(CameraMath.pickFpsRange(full.fpsRanges, 0)); assertNull(CameraMath.pickFpsRange(emptyList(), 30))
    }

    @Test fun clampingRespectsCapabilities() {
        assertEquals(12, CameraMath.clampEv(full, 99)); assertEquals(0, CameraMath.clampEv(bare, 5))
        assertEquals(100_000_000L, CameraMath.clampExposureNs(full, 5_000_000_000L)); assertEquals(100, CameraMath.clampIso(full, 5))
        assertEquals(10f, CameraMath.clampFocus(full, 99f)); assertEquals(0f, CameraMath.clampFocus(bare, 3f))
    }

    @Test fun frameDurationNeverShorterThanExposure() {
        assertEquals(33_333_333L, CameraMath.frameDurationNs(8_000_000, 0))
        assertEquals(50_000_000L, CameraMath.frameDurationNs(50_000_000, 30))
        assertEquals(1_000_000_000L / 60, CameraMath.frameDurationNs(1_000_000, 60))
    }

    @Test fun unsupportedControlsAreDropped() {
        val want = CameraControls(evIndex = 5, aeLock = true, awbLock = true, focusLock = true, manualExposure = true, manualFocus = true, targetFps = 30)
        val e = CameraMath.effective(bare, want)
        assertFalse(e.aeLock); assertFalse(e.awbLock); assertFalse(e.focusLock); assertFalse(e.manualExposure); assertFalse(e.manualFocus)
        assertEquals(0, e.evIndex); assertEquals(0, e.targetFps)
    }

    @Test fun manualExposureDisablesAutoExposureControls() {
        val e = CameraMath.effective(full, CameraControls(evIndex = 4, aeLock = true, manualExposure = true))
        assertTrue(e.manualExposure); assertFalse(e.aeLock); assertEquals(0, e.evIndex)
    }

    @Test fun manualFocusWinsOverFocusLock() {
        val e = CameraMath.effective(full, CameraControls(focusLock = true, manualFocus = true, focusDiopters = 4f))
        assertTrue(e.manualFocus); assertFalse(e.focusLock); assertEquals(4f, e.focusDiopters)
        val l = CameraMath.effective(full, CameraControls(focusLock = true))
        assertTrue(l.focusLock)
    }
}
