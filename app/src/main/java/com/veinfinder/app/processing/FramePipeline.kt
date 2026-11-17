package com.veinfinder.app.processing

import com.veinfinder.app.VeinProcessor
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Diagnostics of one processed frame (shown in the on-screen status line). */
data class FrameInfo(
    val display: DisplayMode,
    val stabilizer: TemporalStabilizer.Status?,
    val dx: Double = 0.0, val dy: Double = 0.0, val rotDeg: Double = 0.0,
    val inlierRatio: Double = 0.0,
    val effectiveFrames: Double = 1.0,
    val ncc: Double = 1.0,
    val processMs: Double = 0.0
)

class FrameOutput(val shown: Mat, val info: FrameInfo)

/**
 * Orchestrates Original / Enhanced / Stabilized. The input RGBA frame is only read, never written, so the
 * original image is always available untouched.
 *
 * STABILIZED always uses the new pipeline (the legacy algorithm has no stage where temporal filtering can
 * run without being undone by its global equalizeHist); with level LEGACY it falls back to MEDIUM.
 */
class FramePipeline(val processor: VeinProcessor = VeinProcessor()) {

    private val enhState = RenderState()
    private val stabState = RenderState()
    val stabilizer = TemporalStabilizer()
    private var lastDisplay: DisplayMode? = null
    private var lastLevel: EnhanceLevel? = null

    @Synchronized fun reset() {
        enhState.reset(); stabState.reset(); stabilizer.reset()
        lastDisplay = null; lastLevel = null
    }

    @Synchronized fun release() { stabilizer.release(); enhState.release(); stabState.release() }

    /** Returns an RGBA Mat the caller must release. */
    @Synchronized fun process(rgba: Mat, display: DisplayMode, cfg: ProcessingConfig, view: ViewMode): FrameOutput {
        val t0 = System.nanoTime()
        if (display != lastDisplay || cfg.level != lastLevel) {
            // a clean start after any change of path avoids mixing statistics / history of different settings
            enhState.reset(); stabState.reset(); stabilizer.reset()
            lastDisplay = display; lastLevel = cfg.level
        }
        fun ms() = (System.nanoTime() - t0) / 1e6

        when (display) {
            DisplayMode.ORIGINAL -> return FrameOutput(rgba.clone(), FrameInfo(display, null, processMs = ms()))

            DisplayMode.ENHANCED -> {
                val out = scoped { s ->
                    val gray = s.mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                    val g8 = if (cfg.level == EnhanceLevel.LEGACY) processor.processLegacyGray(rgba, cfg.k, cfg.b, s)
                    else processor.flatten(gray, cfg, s).let { processor.render(it.g, it.valid, it.fill, cfg, enhState, s) }
                    s.keep(processor.toRgba(g8, view))
                }
                return FrameOutput(out, FrameInfo(display, null, processMs = ms()))
            }

            DisplayMode.STABILIZED -> {
                val eff = if (cfg.level == EnhanceLevel.LEGACY) cfg.copy(level = EnhanceLevel.MEDIUM) else cfg
                return scoped { s ->
                    val gray = s.mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                    val flat = processor.flatten(gray, eff, s)
                    val r = stabilizer.process(flat.g, eff.stabilizeStrength)
                    s.track(r.image)
                    val g8 = processor.render(r.image, flat.valid, flat.fill, eff, stabState, s)
                    val shifted = s.track(stabilizer.applyView(g8, r))        // tremor damping on the final picture
                    val out = processor.toRgba(shifted, view)
                    FrameOutput(out, FrameInfo(display, r.status, r.dx, r.dy, r.rotDeg, r.inlierRatio, r.effectiveFrames, r.ncc, ms()))
                }
            }
        }
    }
}
