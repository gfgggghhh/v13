package com.veinfinder.app

import com.veinfinder.app.camera.CameraControls
import com.veinfinder.app.processing.DisplayMode
import com.veinfinder.app.processing.EnhanceLevel
import com.veinfinder.app.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {
    @Test fun sanitizeClampsEverything() {
        val s = AppSettings(contrast = 500, noiseReduction = -3, stabilizeStrength = 101, k = 7.0, b = -1.0,
            camera = CameraControls(iso = 1, exposureTimeNs = 0, focusDiopters = -4f, targetFps = 77, evIndex = 999)).sanitized()
        assertEquals(100, s.contrast); assertEquals(0, s.noiseReduction); assertEquals(100, s.stabilizeStrength)
        assertEquals(1.0, s.k, 0.0); assertEquals(0.0, s.b, 0.0)
        assertEquals(50, s.camera.iso); assertEquals(1_000L, s.camera.exposureTimeNs); assertEquals(0f, s.camera.focusDiopters)
        assertEquals(0, s.camera.targetFps); assertEquals(64, s.camera.evIndex)
    }

    @Test fun disabledStabilizationMeansStrengthZero() {
        assertEquals(0, AppSettings(stabilizeEnabled = false, stabilizeStrength = 80).toProcessing().stabilizeStrength)
        assertEquals(80, AppSettings(stabilizeEnabled = true, stabilizeStrength = 80).toProcessing().stabilizeStrength)
    }

    @Test fun defaultsAreConservative() {
        val d = AppSettings()
        assertEquals("640x480", d.resolution); assertEquals(DisplayMode.ENHANCED, d.display); assertEquals(EnhanceLevel.MEDIUM, d.level)
        assertFalse(d.camera.aeLock); assertFalse(d.camera.focusLock); assertFalse(d.camera.manualExposure)
        assertEquals(0.9, d.k, 0.0); assertEquals(10.0, d.b, 0.0)                 // the original app's constants
    }
}
