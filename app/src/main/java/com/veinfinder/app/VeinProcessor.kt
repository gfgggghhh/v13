package com.veinfinder.app

import com.veinfinder.app.processing.EnhanceLevel
import com.veinfinder.app.processing.MatScope
import com.veinfinder.app.processing.ProcessingConfig
import com.veinfinder.app.processing.RenderState
import com.veinfinder.app.processing.ViewMode
import com.veinfinder.app.processing.params
import com.veinfinder.app.processing.scoped
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Output of [VeinProcessor.flatten]: the flattened image plus the validity / glare masks of the same frame. */
class FlatResult(val g: Mat, val valid: Mat, val fill: Mat)

/**
 * Vein image processing.
 *
 * Two independent paths:
 *  - [processLegacy] : port of ImageProcess.CacualteAndReturnImg() of the Windows program (sigma1 = 58.4,
 *    sigma2 = 29.6, sigma3 = 2.0, k, b). Same maths as before, but every temporary is now released.
 *  - [flatten] + [render] : the new noise-aware pipeline. It is split in two stages so the temporal
 *    stabilizer can sit between them and denoise *before* any contrast is applied.
 *
 *      flatten : illumination removal (log-domain Retinex, saturation-aware) followed by local contrast
 *                normalisation with a **noise floor** (so flat skin is not blown up into noise).
 *      render  : light denoise, thin-ridge emphasis (Hessian, with edge suppression so shadow borders do not
 *                become false veins), smoothed tone curve (+ optional CLAHE for High).
 *
 * The input Mat is never modified.
 */
class VeinProcessor(@Volatile var k: Double = 0.9, @Volatile var b: Double = 10.0) {

    var sigma1 = 58.4
    var sigma2 = 29.6
    var sigma3 = 2.0

    // reusable scratch for the robust statistics (avoids a ~1 MB allocation per frame)
    private var scratchA = FloatArray(0)
    private var scratchB = FloatArray(0)

    // ------------------------------------------------------------------ public API

    /** Legacy entry point kept for compatibility. RGBA in -> RGBA out (caller releases). */
    fun process(rgba: Mat, mode: ViewMode): Mat = scoped { s ->
        val gray8 = processLegacyGray(rgba, k, b, s)
        s.keep(toRgba(gray8, mode))
    }

    /** One-shot enhancement of a single frame with any level (no temporal state). RGBA in -> RGBA out. */
    fun enhanceOnce(rgba: Mat, cfg: ProcessingConfig, mode: ViewMode): Mat = scoped { s ->
        val gray = s.mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val g8 = if (cfg.level == EnhanceLevel.LEGACY) processLegacyGray(rgba, cfg.k, cfg.b, s)
        else flatten(gray, cfg, s).let { render(it.g, it.valid, it.fill, cfg, RenderState(), s) }
        s.keep(toRgba(g8, mode))
    }

    /** Grey (8-bit, veins dark) -> RGBA in the requested colour mode. Caller releases the result. */
    fun toRgba(gray8: Mat, mode: ViewMode): Mat {
        val out = Mat()
        try { fillRgba(gray8, mode, out) } catch (t: Throwable) { out.release(); throw t }    // no native leak if OpenCV throws
        return out
    }

    private fun fillRgba(gray8: Mat, mode: ViewMode, out: Mat) {
        when (mode) {
            ViewMode.GRAY -> Imgproc.cvtColor(gray8, out, Imgproc.COLOR_GRAY2RGBA)
            ViewMode.NEGATIVE -> scoped { s ->
                val inv = s.mat(); Core.bitwise_not(gray8, inv)
                Imgproc.cvtColor(inv, out, Imgproc.COLOR_GRAY2RGBA)
            }
            ViewMode.GREEN -> scoped { s ->
                val zero = s.zeros(gray8.rows(), gray8.cols(), CvType.CV_8U)
                val alpha = s.mat(gray8.rows(), gray8.cols(), CvType.CV_8U); alpha.setTo(Scalar(255.0))
                Core.merge(listOf(zero, gray8, zero, alpha), out)            // R=0, G=value, B=0
            }
        }
    }

    // ------------------------------------------------------------------ legacy path (unchanged maths)

    /** Returns the 8-bit equalised image (the owner of the Mat is [s]). */
    internal fun processLegacyGray(rgba: Mat, kk: Double, bb: Double, s: MatScope): Mat {
        val gray = s.mat(); val f = s.mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        gray.convertTo(f, CvType.CV_32F)                                    // imgDouble

        val g1 = s.mat(); val d = s.mat(); val a = s.mat(); val g2 = s.mat(); val g3 = s.mat()
        Imgproc.GaussianBlur(f, g1, Size(191.0, 191.0), sigma1)             // background / illumination
        Core.subtract(f, g1, d)
        Core.absdiff(d, s.zeros(d.rows(), d.cols(), d.type()), a)
        Imgproc.GaussianBlur(a, g2, Size(95.0, 95.0), sigma2)               // local contrast
        Core.divide(d, g2, g3, 0.1)                                         // 0.1 * d / g2
        normalize01(g3)

        val mean = s.track(MatOfDouble()); val std = s.track(MatOfDouble())
        Core.meanStdDev(g3, mean, std)
        val t = mean.get(0, 0)[0] - kk * std.get(0, 0)[0]                   // threshold = avg - k * sdv

        val tmp = s.mat(); val g4 = s.mat()
        Core.add(g3, Scalar.all(1e-8), tmp)
        Core.divide(t, tmp, tmp)                                            // t / (g3 + 1e-8)
        Core.multiply(tmp, Scalar.all(bb), tmp)                             // * b
        Core.add(tmp, Scalar.all(1.0), tmp)                                 // + 1
        Core.divide(1.0, tmp, g4)                                           // 1 / (1 + b*t/g3)
        normalize01(g4)

        val g6 = s.mat(); val byteImg = s.mat()
        Imgproc.GaussianBlur(g4, g6, Size(3.0, 3.0), sigma3)
        g6.convertTo(byteImg, CvType.CV_8U, 255.0, 0.0)
        Imgproc.equalizeHist(byteImg, byteImg)
        return byteImg
    }

    private fun normalize01(m: Mat) {
        val mm = Core.minMaxLoc(m)
        Core.subtract(m, Scalar.all(mm.minVal), m)
        val mx = Core.minMaxLoc(m).maxVal
        if (mx > 0) Core.divide(m, Scalar.all(mx), m)
    }

    // ------------------------------------------------------------------ new path, stage A

    /**
     * Gray 8-bit -> illumination-flattened, noise-aware, locally normalised image (CV_32F, veins negative,
     * roughly unit variance in vein-bearing areas). Result is owned by [s].
     */
    fun flatten(gray8: Mat, cfg: ProcessingConfig, s: MatScope): FlatResult {
        val w = gray8.cols(); val h = gray8.rows()
        val nr = cfg.noiseReduction.coerceIn(0, 100) / 100.0
        val sc = w / REF_WIDTH                                   // all pixel scales are defined at 640 px width

        // 1. light edge-preserving denoise (bilateral keeps vein borders, unlike a plain blur)
        val src: Mat = if (nr > 0.05) {
            val d = s.mat()
            Imgproc.bilateralFilter(gray8, d, if (sc > 1.4) 7 else 5, 4.0 + 28.0 * nr, 2.0 * max(sc, 0.5))
            d
        } else gray8
        val f = s.mat(); src.convertTo(f, CvType.CV_32F)

        // 2. validity mask: ignore saturated glare and black background when estimating illumination.
        //    It is computed on a blurred copy and cleaned morphologically so it is spatially coherent
        //    (a per-pixel threshold flickers on noise and produced salt-and-pepper output).
        val gb = s.mat(); Imgproc.GaussianBlur(gray8, gb, Size(0.0, 0.0), MASK_BLUR * sc)
        val m8 = s.mat(); Core.inRange(gb, Scalar(MASK_LO), Scalar(MASK_HI), m8)
        val se = s.track(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0)))
        Imgproc.morphologyEx(m8, m8, Imgproc.MORPH_OPEN, se)
        Imgproc.morphologyEx(m8, m8, Imgproc.MORPH_CLOSE, se)
        val m = s.mat(); m8.convertTo(m, CvType.CV_32F, 1.0 / 255.0)
        // feathered mask for the OUTPUT, eroded first so the blend starts inside the trustworthy area
        val valid = s.mat(); Imgproc.erode(m, valid, s.track(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))))
        Imgproc.GaussianBlur(valid, valid, Size(0.0, 0.0), 1.5 * sc)
        // fill colour for invalid pixels = fraction of *invalid* pixels nearby that are glare (1 = bright, 0 = dark).
        // Defined this way it is consistent with (1 - valid) in the whole blend band.
        val glare = s.mat(); Core.inRange(gb, Scalar(MASK_HI), Scalar(255.0), m8); m8.convertTo(glare, CvType.CV_32F, 1.0 / 255.0)
        val inval = s.mat(); Core.subtract(m, Scalar.all(1.0), inval); Core.multiply(inval, Scalar.all(-1.0), inval)
        Imgproc.GaussianBlur(glare, glare, Size(0.0, 0.0), 4.0); Imgproc.GaussianBlur(inval, inval, Size(0.0, 0.0), 4.0)
        Core.add(inval, Scalar.all(1e-3), inval)
        val fill = s.mat(); Core.divide(glare, inval, fill); Core.min(fill, Scalar.all(1.0), fill)

        // 3. illumination = normalised convolution (blur(f*m) / blur(m)) computed at reduced scale.
        //    sigma1 is huge, so working at 1/4 resolution is equivalent and far cheaper than a 191-px kernel.
        val ds = if (w >= 480) 4 else 2
        val sw = max(8, w / ds); val sh = max(8, h / ds)
        val dsx = w.toDouble() / sw
        val small = Size(sw.toDouble(), sh.toDouble())
        val fm = s.mat(); Core.multiply(f, m, fm)
        val fmS = s.mat(); val mS = s.mat()
        Imgproc.resize(fm, fmS, small, 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.resize(m, mS, small, 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(fmS, fmS, Size(0.0, 0.0), sigma1 * sc / dsx)
        Imgproc.GaussianBlur(mS, mS, Size(0.0, 0.0), sigma1 * sc / dsx)
        val sumM = Core.sumElems(m).`val`[0]
        val meanV = if (sumM > 1.0) Core.sumElems(fm).`val`[0] / sumM else 128.0
        Core.add(fmS, Scalar.all(0.01 * meanV), fmS)
        Core.add(mS, Scalar.all(0.01), mS)
        val bS = s.mat(); Core.divide(fmS, mS, bS)
        val bg = s.mat(); Imgproc.resize(bS, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        // 4. log-ratio (Retinex): veins absorb light multiplicatively, so the ratio is the right domain
        val num = s.mat(); val den = s.mat(); val ratio = s.mat(); val r = s.mat()
        Core.add(f, Scalar.all(4.0), num); Core.add(bg, Scalar.all(4.0), den)
        Core.divide(num, den, ratio); Core.log(ratio, r)
        Core.multiply(r, m, r)

        // 5. robust sensor-noise estimate (MAD of a Laplacian-like residual on valid pixels)
        val sigmaN = estimateNoise(r, m, s)

        // 6. local contrast normalisation WITH a noise floor: S = sqrt(local_var + (c*sigmaN)^2)
        val r2 = s.mat(); Core.multiply(r, r, r2)
        val v2 = s.mat(); Imgproc.resize(r2, v2, small, 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(v2, v2, Size(0.0, 0.0), sigma2 * sc / dsx)
        val vFull = s.mat(); Imgproc.resize(v2, vFull, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val c = NOISE_FLOOR_BASE + NOISE_FLOOR_SLOPE * nr
        Core.add(vFull, Scalar.all((c * sigmaN).pow(2)), vFull)
        val scaleMat = s.mat(); Core.sqrt(vFull, scaleMat)
        val g = s.mat(); Core.divide(r, scaleMat, g)
        Core.min(g, Scalar.all(8.0), g); Core.max(g, Scalar.all(-8.0), g)
        Core.multiply(g, m, g)
        // inpaint invalid pixels from their valid neighbours so the feathered border blends similar values
        // (otherwise masked pixels sit at 0 and show as a grey ring around glare / the hand border)
        val gn = s.mat(); val mn = s.mat()
        Imgproc.GaussianBlur(g, gn, Size(0.0, 0.0), 3.0 * sc); Imgproc.GaussianBlur(m, mn, Size(0.0, 0.0), 3.0 * sc)
        Core.add(mn, Scalar.all(1e-3), mn); Core.divide(gn, mn, gn)
        Core.min(gn, Scalar.all(8.0), gn); Core.max(gn, Scalar.all(-8.0), gn)
        val inv = s.mat(); Core.subtract(m, Scalar.all(1.0), inv); Core.multiply(inv, Scalar.all(-1.0), inv)        // 1 - m
        Core.multiply(gn, inv, gn); Core.add(g, gn, g)
        return FlatResult(g, valid, fill)
    }

    private fun estimateNoise(r: Mat, m: Mat, s: MatScope): Double {
        val k = s.mat(3, 3, CvType.CV_32F)
        k.put(0, 0, floatArrayOf(0f, -0.25f, 0f, -0.25f, 1f, -0.25f, 0f, -0.25f, 0f))
        val hp = s.mat(); Imgproc.filter2D(r, hp, CvType.CV_32F, k)
        val me = s.mat(); Imgproc.erode(m, me, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)).also { s.track(it) })
        val n = hp.rows() * hp.cols()
        if (scratchA.size < n) { scratchA = FloatArray(n); scratchB = FloatArray(n) }
        hp.get(0, 0, scratchA); me.get(0, 0, scratchB)
        val bins = IntArray(NOISE_BINS); var cnt = 0
        for (i in 0 until n) if (scratchB[i] > 0.5f) {
            val v = Math.abs(scratchA[i]); val bi = min(NOISE_BINS - 1, (v / NOISE_MAX * NOISE_BINS).toInt())
            bins[bi]++; cnt++
        }
        if (cnt < 200) return 0.02
        var acc = 0; var bi = 0
        while (bi < NOISE_BINS && acc + bins[bi] < cnt / 2) { acc += bins[bi]; bi++ }
        val med = (bi + 0.5) * NOISE_MAX / NOISE_BINS
        return (1.4826 * med / sqrt(1.25)).coerceIn(0.003, 0.25)
    }

    // ------------------------------------------------------------------ new path, stage B

    /**
     * Flattened image -> 8-bit, veins dark. Result is owned by [s].
     * [flat] is the image to render (the temporally filtered one when stabilising); [valid] / [fill] are the
     * masks of the *current* frame returned by [flatten]: invalid areas are drawn dark (no signal) or bright (glare)
     * instead of being rendered as amplified noise.
     */
    fun render(flat: Mat, valid: Mat, fill: Mat, cfg: ProcessingConfig, st: RenderState, s: MatScope): Mat {
        val p = cfg.params()
        val nr = cfg.noiseReduction.coerceIn(0, 100) / 100.0
        val sc = flat.cols() / REF_WIDTH

        // 1. light Gaussian smoothing of the normalised image (sigma 0.7 .. 1.7 px)
        val fs = s.mat(); Imgproc.GaussianBlur(flat, fs, Size(0.0, 0.0), (SMOOTH_BASE + SMOOTH_SLOPE * nr) * sc)

        // 2. thin-ridge emphasis: only positive curvature (dark line), minus gradient (so step edges / shadow borders cancel out)
        val e = s.mat()
        if (p.ridgeGain > 0.0) {
            val ridge = ridge(fs, p, sc, s)
            Core.addWeighted(fs, 1.0, ridge, -p.ridgeGain, 0.0, e)
        } else fs.copyTo(e)

        // 3. temporally-smoothed robust location/spread (median, (p84-p16)/2) of the whole image
        val (med0, spr0) = robustLocationSpread(e, valid)
        val a = if (st.med.isNaN()) 1.0 else STAT_EMA
        st.med = if (st.med.isNaN()) med0 else st.med + a * (med0 - st.med)
        st.spread = if (st.spread.isNaN()) spr0 else st.spread + a * (spr0 - st.spread)
        val spread = max(st.spread, 1e-3)

        // 4. smooth tone curve: y = sigmoid(gain * (E - median) / spread)   (veins are negative -> dark)
        val gain = TONE_BASE * p.toneGain * 2.0.pow((cfg.contrast.coerceIn(0, 100) - 50) / 50.0)
        val z = s.mat(); Core.subtract(e, Scalar.all(st.med), z)
        Core.multiply(z, Scalar.all(-gain / spread), z)
        Core.exp(z, z); Core.add(z, Scalar.all(1.0), z)
        val y = s.mat(); Core.divide(1.0, z, y)
        // outside the valid area: dark (no signal) or bright (saturated glare), feathered
        val inv = s.mat(); Core.subtract(valid, Scalar.all(1.0), inv); Core.multiply(inv, Scalar.all(-1.0), inv)   // 1 - valid
        Core.multiply(y, valid, y); Core.multiply(fill, inv, inv); Core.add(y, inv, y)
        val y8 = s.mat(); y.convertTo(y8, CvType.CV_8U, 255.0)

        if (p.claheClip > 0.0) {
            val cl = st.clahe ?: Imgproc.createCLAHE(p.claheClip, Size(8.0, 8.0)).also { st.clahe = it }
            cl.clipLimit = p.claheClip
            val out = s.mat(); cl.apply(y8, out); return out
        }
        return y8
    }

    /** Multi-scale Hessian ridge strength for DARK lines, edge-suppressed. Always >= 0. Owned by [s]. */
    private fun ridge(src: Mat, p: com.veinfinder.app.processing.LevelParams, sc: Double, s: MatScope): Mat {
        val best = s.zeros(src.rows(), src.cols(), CvType.CV_32F)
        for (sg0 in p.ridgeSigmas) {
            val sg = sg0 * sc
            val (g0, g1, g2) = gaussKernels(sg, s)
            val ixx = s.mat(); val iyy = s.mat(); val ixy = s.mat(); val ix = s.mat(); val iy = s.mat()
            Imgproc.sepFilter2D(src, ixx, CvType.CV_32F, g2, g0)
            Imgproc.sepFilter2D(src, iyy, CvType.CV_32F, g0, g2)
            Imgproc.sepFilter2D(src, ixy, CvType.CV_32F, g1, g1)
            Imgproc.sepFilter2D(src, ix, CvType.CV_32F, g1, g0)
            Imgproc.sepFilter2D(src, iy, CvType.CV_32F, g0, g1)
            // lambda_max = (ixx+iyy)/2 + sqrt(((ixx-iyy)/2)^2 + ixy^2)
            val a = s.mat(); val bq = s.mat(); val sq = s.mat()
            Core.add(ixx, iyy, a); Core.multiply(a, Scalar.all(0.5), a)
            Core.subtract(ixx, iyy, bq); Core.multiply(bq, Scalar.all(0.5), bq)
            Core.multiply(bq, bq, bq); Core.multiply(ixy, ixy, sq); Core.add(bq, sq, bq); Core.sqrt(bq, bq)
            val lam = s.mat(); Core.add(a, bq, lam)
            Core.multiply(lam, Scalar.all(sg * sg), lam)                              // scale-normalised
            // edge suppression: subtract scale-normalised gradient magnitude
            val gm = s.mat(); Core.multiply(ix, ix, gm); Core.multiply(iy, iy, sq); Core.add(gm, sq, gm); Core.sqrt(gm, gm)
            Core.multiply(gm, Scalar.all(sg * p.edgeBeta), gm)
            Core.subtract(lam, gm, lam)
            Core.max(lam, Scalar.all(0.0), lam)
            Core.max(best, lam, best)
        }
        return best
    }

    /** Gaussian, first and second derivative kernels (as 1xN rows) for sepFilter2D. */
    private fun gaussKernels(sg: Double, s: MatScope): Triple<Mat, Mat, Mat> {
        val rad = Math.ceil(3.0 * sg).toInt(); val n = 2 * rad + 1
        val g0 = FloatArray(n); val g1 = FloatArray(n); val g2 = FloatArray(n)
        var sum = 0.0
        for (i in 0 until n) { val x = (i - rad).toDouble(); sum += exp(-x * x / (2 * sg * sg)) }
        for (i in 0 until n) {
            val x = (i - rad).toDouble(); val g = exp(-x * x / (2 * sg * sg)) / sum
            g0[i] = g.toFloat(); g1[i] = (-x / (sg * sg) * g).toFloat(); g2[i] = ((x * x / (sg * sg * sg * sg) - 1.0 / (sg * sg)) * g).toFloat()
        }
        fun mk(a: FloatArray): Mat { val m = s.mat(1, n, CvType.CV_32F); m.put(0, 0, a); return m }
        return Triple(mk(g0), mk(g1), mk(g2))
    }

    /** Median and (p84 - p16)/2 of a float image via a 512-bin histogram over [-8, 8]. */
    private fun robustLocationSpread(e: Mat, valid: Mat): Pair<Double, Double> {
        val n = e.rows() * e.cols()
        if (scratchA.size < n) { scratchA = FloatArray(n); scratchB = FloatArray(n) }
        e.get(0, 0, scratchA); valid.get(0, 0, scratchB)
        val bins = IntArray(HB); var cnt = 0
        for (i in 0 until n) {
            if (scratchB[i] < 0.9f) continue                                  // statistics of valid pixels only
            val v = scratchA[i]
            val bi = ((v + HR) / (2 * HR) * HB).toInt().coerceIn(0, HB - 1)
            bins[bi]++; cnt++
        }
        if (cnt < 500) return Pair(0.0, 1.0)
        fun q(p: Double): Double {
            val target = p * cnt; var acc = 0; var i = 0
            while (i < HB - 1 && acc + bins[i] < target) { acc += bins[i]; i++ }
            return -HR + (i + 0.5) * (2 * HR) / HB
        }
        val med = q(0.5); val sp = (q(0.84) - q(0.16)) / 2.0
        return Pair(med, max(sp, 0.05))
    }

    companion object {
        private const val REF_WIDTH = 640.0
        private const val MASK_BLUR = 2.5
        private const val MASK_LO = 14.0
        private const val MASK_HI = 250.0
        private const val NOISE_BINS = 1024
        private const val NOISE_MAX = 0.4
        private const val NOISE_FLOOR_BASE = 1.0
        private const val NOISE_FLOOR_SLOPE = 2.0
        private const val SMOOTH_BASE = 0.7
        private const val SMOOTH_SLOPE = 1.0
        private const val STAT_EMA = 0.12
        private const val TONE_BASE = 1.6
        private const val HB = 512
        private const val HR = 8.0
    }
}
