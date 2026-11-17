package com.veinfinder.app.settings

import android.content.SharedPreferences
import com.veinfinder.app.camera.CameraControls
import com.veinfinder.app.processing.DisplayMode
import com.veinfinder.app.processing.EnhanceLevel
import com.veinfinder.app.processing.ProcessingConfig
import com.veinfinder.app.processing.ViewMode

/** Everything the user can change. Saved to SharedPreferences and restored on the next start. */
data class AppSettings(
    val resolution: String = "640x480",
    val display: DisplayMode = DisplayMode.ENHANCED,
    val view: ViewMode = ViewMode.GRAY,
    val level: EnhanceLevel = EnhanceLevel.MEDIUM,
    val contrast: Int = 50,
    val noiseReduction: Int = 50,
    val stabilizeEnabled: Boolean = true,
    val stabilizeStrength: Int = 60,
    /** Legacy algorithm constants (only used when level == LEGACY). */
    val k: Double = 0.9,
    val b: Double = 10.0,
    val camera: CameraControls = CameraControls()
) {
    fun toProcessing() = ProcessingConfig(
        level = level, contrast = contrast, noiseReduction = noiseReduction, k = k, b = b,
        stabilizeStrength = if (stabilizeEnabled) stabilizeStrength else 0
    )

    /** Clamps every value to its valid range (protects against corrupted / older preference files). */
    fun sanitized() = copy(
        contrast = contrast.coerceIn(0, 100), noiseReduction = noiseReduction.coerceIn(0, 100),
        stabilizeStrength = stabilizeStrength.coerceIn(0, 100),
        k = k.coerceIn(0.0, 1.0), b = b.coerceIn(0.0, 10.0),
        camera = camera.copy(
            evIndex = camera.evIndex.coerceIn(-64, 64),
            exposureTimeNs = camera.exposureTimeNs.coerceIn(1_000L, 1_000_000_000L),
            iso = camera.iso.coerceIn(50, 25_600),
            focusDiopters = camera.focusDiopters.coerceIn(0f, 50f),
            targetFps = if (camera.targetFps in listOf(0, 15, 24, 30, 60)) camera.targetFps else 0
        )
    )

    companion object {
        private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, def: T): T =
            runCatching { enumValueOf<T>(getString(key, def.name) ?: def.name) }.getOrDefault(def)

        fun load(p: SharedPreferences): AppSettings {
            val d = AppSettings()
            return AppSettings(
                resolution = p.getString("resolution", d.resolution) ?: d.resolution,
                display = p.enum("display", d.display), view = p.enum("view", d.view), level = p.enum("level", d.level),
                contrast = p.getInt("contrast", d.contrast), noiseReduction = p.getInt("nr", d.noiseReduction),
                stabilizeEnabled = p.getBoolean("stab_on", d.stabilizeEnabled), stabilizeStrength = p.getInt("stab_strength", d.stabilizeStrength),
                k = p.getFloat("k", d.k.toFloat()).toDouble(), b = p.getFloat("b", d.b.toFloat()).toDouble(),
                camera = CameraControls(
                    evIndex = p.getInt("ev", 0), aeLock = p.getBoolean("ae_lock", false), awbLock = p.getBoolean("awb_lock", false),
                    focusLock = p.getBoolean("focus_lock", false), manualExposure = p.getBoolean("man_exp", false),
                    exposureTimeNs = p.getLong("exp_ns", d.camera.exposureTimeNs), iso = p.getInt("iso", d.camera.iso),
                    manualFocus = p.getBoolean("man_focus", false), focusDiopters = p.getFloat("focus_d", 0f),
                    targetFps = p.getInt("fps", 0)
                )
            ).sanitized()
        }

        fun save(p: SharedPreferences, s: AppSettings) {
            val c = s.camera
            p.edit()
                .putString("resolution", s.resolution).putString("display", s.display.name).putString("view", s.view.name)
                .putString("level", s.level.name).putInt("contrast", s.contrast).putInt("nr", s.noiseReduction)
                .putBoolean("stab_on", s.stabilizeEnabled).putInt("stab_strength", s.stabilizeStrength)
                .putFloat("k", s.k.toFloat()).putFloat("b", s.b.toFloat())
                .putInt("ev", c.evIndex).putBoolean("ae_lock", c.aeLock).putBoolean("awb_lock", c.awbLock)
                .putBoolean("focus_lock", c.focusLock).putBoolean("man_exp", c.manualExposure)
                .putLong("exp_ns", c.exposureTimeNs).putInt("iso", c.iso)
                .putBoolean("man_focus", c.manualFocus).putFloat("focus_d", c.focusDiopters).putInt("fps", c.targetFps)
                .apply()
        }
    }
}
