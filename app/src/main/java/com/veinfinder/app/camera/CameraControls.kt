package com.veinfinder.app.camera

/** Plain size (no android.util.Size) so the selection logic can be unit-tested on the JVM. */
data class Res(val w: Int, val h: Int) {
    override fun toString() = "${w}×${h}"
    val key get() = "${w}x${h}"
    companion object {
        fun parse(s: String?): Res? {
            val p = s?.lowercase()?.split("x") ?: return null
            if (p.size != 2) return null
            val w = p[0].trim().toIntOrNull() ?: return null
            val h = p[1].trim().toIntOrNull() ?: return null
            return if (w > 0 && h > 0) Res(w, h) else null
        }
    }
}

/** What the user asked for. Only the parts the selected camera supports are applied. */
data class CameraControls(
    /** Exposure compensation index (steps of [CameraCapabilities.evStep]). Ignored in manual exposure. */
    val evIndex: Int = 0,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
    /** Lock the focus where the autofocus currently is. */
    val focusLock: Boolean = false,
    val manualExposure: Boolean = false,
    val exposureTimeNs: Long = 8_000_000L,
    val iso: Int = 200,
    val manualFocus: Boolean = false,
    /** Focus distance in diopters (0 = infinity, [CameraCapabilities.minFocusDistance] = closest). */
    val focusDiopters: Float = 0f,
    /** 0 = automatic. */
    val targetFps: Int = 0
)

/** Capabilities of one camera as reported by Camera2 (all optional features default to "unsupported"). */
data class CameraCapabilities(
    val cameraId: String = "",
    val hardwareLevel: String = "",
    val resolutions: List<Res> = emptyList(),
    val evMin: Int = 0, val evMax: Int = 0, val evStep: Float = 0f,
    val aeLockSupported: Boolean = false,
    val awbLockSupported: Boolean = false,
    val manualSensor: Boolean = false,
    val exposureTimeMinNs: Long = 0, val exposureTimeMaxNs: Long = 0,
    val isoMin: Int = 0, val isoMax: Int = 0,
    val minFocusDistance: Float = 0f,
    val afOffSupported: Boolean = false,
    val fpsRanges: List<Pair<Int, Int>> = emptyList()
) {
    val evSupported get() = evMax > evMin && evStep > 0f
    val manualExposureSupported get() = manualSensor && exposureTimeMaxNs > exposureTimeMinNs && isoMax > isoMin
    val manualFocusSupported get() = afOffSupported && minFocusDistance > 0f
    /** Locking focus reads the current lens position, which needs AF to be switchable off. */
    val focusLockSupported get() = manualFocusSupported
    val selectableFps get() = CameraMath.selectableFps(fpsRanges)

    /** One-paragraph dump for the "camera report" (useful to check the POCO M6 Pro's real capabilities). */
    fun describe(): String = buildString {
        appendLine("Camera $cameraId  [$hardwareLevel]")
        appendLine("Resolutions: ${resolutions.joinToString { it.toString() }}")
        appendLine("Exposure compensation: ${if (evSupported) "$evMin..$evMax step $evStep" else "not supported"}")
        appendLine("AE lock: $aeLockSupported   AWB lock: $awbLockSupported")
        appendLine("Manual sensor: $manualSensor" + if (manualExposureSupported)
            "  (exposure ${exposureTimeMinNs / 1000}..${exposureTimeMaxNs / 1000} us, ISO $isoMin..$isoMax)" else "")
        appendLine("Manual focus: $manualFocusSupported" + if (manualFocusSupported) "  (0..$minFocusDistance diopters)" else "")
        appendLine("FPS ranges: ${fpsRanges.joinToString { "${it.first}-${it.second}" }}")
    }
}

/** Pure selection / clamping helpers (unit tested). */
object CameraMath {

    /** Nearest supported resolution to the wanted one (by pixel count, preferring the same aspect ratio). */
    fun pickResolution(available: List<Res>, wanted: Res?): Res? {
        if (available.isEmpty()) return null
        if (wanted == null) return available.minByOrNull { Math.abs(it.w * it.h - 640 * 480) }
        available.firstOrNull { it == wanted }?.let { return it }
        val ar = wanted.w.toDouble() / wanted.h
        return available.minWithOrNull(compareBy<Res>(
            { Math.abs(it.w.toDouble() / it.h - ar) > 0.02 },
            { Math.abs(it.w * it.h - wanted.w * wanted.h) }))
    }

    /** Resolutions worth offering for analysis: <= 1920 wide, sorted ascending, no duplicates. */
    fun offerable(all: List<Res>, maxW: Int = 1920): List<Res> =
        all.filter { it.w <= maxW && it.w >= 320 && it.h >= 240 }.distinct().sortedBy { it.w * it.h }

    /** Distinct target frame rates that at least one range can hold steady (upper == requested or contains it). */
    fun selectableFps(ranges: List<Pair<Int, Int>>): List<Int> =
        listOf(15, 24, 30, 60).filter { f -> ranges.any { it.first <= f && f <= it.second } }

    /** Best AE target range for a wanted fps: fixed [f,f] if offered, else the narrowest range containing f. */
    fun pickFpsRange(ranges: List<Pair<Int, Int>>, fps: Int): Pair<Int, Int>? {
        if (fps <= 0) return null
        ranges.firstOrNull { it.first == fps && it.second == fps }?.let { return it }
        return ranges.filter { it.first <= fps && fps <= it.second }.minByOrNull { it.second - it.first }
    }

    fun clampEv(c: CameraCapabilities, idx: Int) = if (c.evSupported) idx.coerceIn(c.evMin, c.evMax) else 0

    fun clampExposureNs(c: CameraCapabilities, ns: Long) =
        if (c.manualExposureSupported) ns.coerceIn(c.exposureTimeMinNs, c.exposureTimeMaxNs) else ns

    fun clampIso(c: CameraCapabilities, iso: Int) =
        if (c.manualExposureSupported) iso.coerceIn(c.isoMin, c.isoMax) else iso

    fun clampFocus(c: CameraCapabilities, d: Float) =
        if (c.manualFocusSupported) d.coerceIn(0f, c.minFocusDistance) else 0f

    /** Frame duration needed for a manual exposure time at an optional target fps (a frame cannot be shorter than its exposure). */
    fun frameDurationNs(exposureNs: Long, fps: Int): Long {
        val base = if (fps > 0) 1_000_000_000L / fps else 33_333_333L
        return maxOf(base, exposureNs)
    }

    /** Which controls actually get applied, given what the camera supports. */
    fun effective(c: CameraCapabilities, want: CameraControls): CameraControls {
        val manualExp = want.manualExposure && c.manualExposureSupported
        val manualFoc = want.manualFocus && c.manualFocusSupported
        return want.copy(
            evIndex = if (manualExp) 0 else clampEv(c, want.evIndex),
            aeLock = want.aeLock && c.aeLockSupported && !manualExp,
            awbLock = want.awbLock && c.awbLockSupported,
            manualExposure = manualExp,
            exposureTimeNs = clampExposureNs(c, want.exposureTimeNs),
            iso = clampIso(c, want.iso),
            manualFocus = manualFoc,
            focusLock = want.focusLock && c.focusLockSupported && !manualFoc,
            focusDiopters = clampFocus(c, want.focusDiopters),
            targetFps = if (want.targetFps in c.selectableFps) want.targetFps else 0
        )
    }
}
