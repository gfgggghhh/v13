package com.veinfinder.app.processing

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Temporal stage that sits between VeinProcessor.flatten() and VeinProcessor.render().
 *
 * Per frame:
 *  1. Estimate global motion (similarity transform) between the previous and current frame with sparse
 *     pyramidal Lucas-Kanade optical flow + RANSAC, on a 1/4-scale copy of the flattened image.
 *  2. If the motion is large or unreliable -> REJECT: history is dropped and the current frame is shown as is
 *     (never merged, so no ghost veins and no lag).
 *  3. Otherwise warp the accumulated image onto the current frame (alignment) and blend recursively.
 *     The blend weight is per pixel: where the aligned history disagrees with the current frame (non-rigid
 *     skin movement, residual misalignment, a moving needle/finger) the current frame wins, so a vein can
 *     never appear twice. A recursive filter needs no frame buffer, so display latency does not increase.
 *  4. Optionally damp hand tremor of the *view* with a low-pass on the camera path; the correction is clamped
 *     to a few pixels, so the image follows the hand and never lags more than that.
 *
 * Nothing here can create detail: the output is always a (motion-compensated) weighted average of real frames.
 * Not thread safe: call from the single processing thread.
 */
class TemporalStabilizer {

    enum class Status { INIT, MERGED, REJECTED }

    class Result(
        /** Owned by the caller (release it). Same size/type as the input (CV_32F). */
        val image: Mat,
        val status: Status,
        /** Frame-to-frame translation in full-resolution pixels, rotation in degrees. */
        val dx: Double, val dy: Double, val rotDeg: Double,
        val inlierRatio: Double,
        /** Number of frames effectively averaged in the middle of the image. */
        val effectiveFrames: Double,
        /** Correlation between the motion-compensated previous frame and the current one (1 = identical). */
        val ncc: Double = 1.0,
        /** Small correction to apply to the final 8-bit picture to damp hand tremor (null = none). */
        val view: ViewShift? = null
    )

    class ViewShift(val dx: Double, val dy: Double, val angleRad: Double)

    // ---- tunables (provisional, see tools/bench report) ----
    var maxShiftFrac = 0.02          // reject if frame-to-frame shift > 2 % of the width
    var maxRotDeg = 3.0
    var maxScaleChange = 0.05
    var minInlierRatio = 0.5
    var minInliers = 12
    var maxResidualPx = 0.9           // RMS residual of inliers (estimation-scale pixels)
    var minNcc = 0.0                  // photometric check: correlation of warped previous frame vs current
    var gateStart = 1.0               // x robust-sigma where history starts being discarded
    var gateRange = 1.5
    var viewClampFrac = 0.012         // max view correction = 1.2 % of the width
    var viewClampDeg = 1.5

    private var prevSmall: Mat? = null
    private var acc: Mat? = null
    private var cnt: Mat? = null
    private var goodStreak = 0

    // camera path (content displacement accumulated since the last reset) and its low-passed version
    private var vx = 0.0; private var vy = 0.0
    private var px = 0.0; private var py = 0.0; private var pa = 0.0
    private var sx = 0.0; private var sy = 0.0; private var sa = 0.0

    fun reset() {
        prevSmall?.release(); prevSmall = null
        acc?.release(); acc = null
        cnt?.release(); cnt = null
        goodStreak = 0
        px = 0.0; py = 0.0; pa = 0.0; sx = 0.0; sy = 0.0; sa = 0.0; vx = 0.0; vy = 0.0
    }

    fun release() = reset()

    /** [flat] = FlatResult.g of the current frame. The returned image is aligned to the CURRENT frame. */
    fun process(flat: Mat, strengthPct: Int): Result = scoped { s ->
        val strength = strengthPct.coerceIn(0, 100) / 100.0
        val w = flat.cols(); val h = flat.rows()
        val sw = max(32, w / 2); val sh = max(24, h / 2)
        val k = w.toDouble() / sw

        val small = s.mat(); Imgproc.resize(flat, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val cur8 = s.mat(); small.convertTo(cur8, CvType.CV_8U, 32.0, 128.0)

        val prev = prevSmall
        val a0 = acc; val c0 = cnt
        if (strength <= 0.0 || prev == null || a0 == null || c0 == null || prev.size() != cur8.size()) {
            return@scoped startOver(flat, s.keep(cur8), Status.INIT, 0.0, 0.0, 0.0, 1.0)
        }

        val mo = estimate(prev, cur8, sw, sh)
        var ncc = 1.0
        var reject = mo == null || !mo.ok(k, w, this)
        if (!reject) {
            // photometric verification: does the previous frame, moved by the estimated motion, really look like the current one?
            ncc = alignmentNcc(prev, cur8, mo!!)
            if (ncc < minNcc) reject = true
        }
        if (reject) {
            val m = mo
            return@scoped startOver(flat, s.keep(cur8), Status.REJECTED, (m?.tx ?: 0.0) * k, (m?.ty ?: 0.0) * k, Math.toDegrees(m?.rot ?: 0.0), m?.inlierRatio ?: 0.0, ncc)
        }
        mo!!

        // --- alignment: previous frame coordinates -> current frame coordinates (full resolution)
        val warp = s.mat(2, 3, CvType.CV_64F)
        warp.put(0, 0, mo.a, -mo.b, mo.tx * k, mo.b, mo.a, mo.ty * k)
        val accW = s.mat(); val cntW = s.mat()
        Imgproc.warpAffine(a0, accW, warp, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        Imgproc.warpAffine(c0, cntW, warp, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(0.0))

        // --- recursive blend with per-pixel ghost gate
        val nMax = 1.0 + 5.0 * strength                                   // up to ~6 frames of averaging
        val alphaMin = 1.0 / nMax
        val ad = s.mat(); Core.absdiff(flat, accW, ad)
        Imgproc.GaussianBlur(ad, ad, Size(3.0, 3.0), 0.0)
        val tau = max(gateStart * robustSigma(ad, s), 0.25)
        val gate = s.mat(); Core.subtract(ad, Scalar.all(tau * 1.0), gate)
        Core.multiply(gate, Scalar.all(1.0 / (tau * gateRange)), gate)
        Core.min(gate, Scalar.all(1.0), gate); Core.max(gate, Scalar.all(0.0), gate)

        val den = s.mat(); Core.add(cntW, Scalar.all(1.0), den)
        val alpha = s.mat(); Core.divide(1.0, den, alpha); Core.max(alpha, Scalar.all(alphaMin), alpha)
        val ag = s.mat(); Core.multiply(alpha, gate, ag)
        val alphaF = s.mat(); Core.add(alpha, gate, alphaF); Core.subtract(alphaF, ag, alphaF)   // a + g - a*g

        val dif = s.mat(); Core.subtract(flat, accW, dif); Core.multiply(dif, alphaF, dif)
        val merged = s.mat(); Core.add(accW, dif, merged)

        val oneMinusG = s.mat(); Core.subtract(gate, Scalar.all(1.0), oneMinusG); Core.multiply(oneMinusG, Scalar.all(-1.0), oneMinusG)
        val newCnt = s.mat(); Core.multiply(den, oneMinusG, newCnt); Core.min(newCnt, Scalar.all(nMax - 1.0), newCnt)

        acc?.release(); acc = merged.clone()
        cnt?.release(); cnt = s.keep(newCnt)
        prevSmall?.release(); prevSmall = s.keep(cur8)
        goodStreak++

        // --- view stabilisation (path low-pass, clamped)
        px += mo.tx * k; py += mo.ty * k; pa += mo.rot
        // sustained intentional motion (velocity of the path over the last frames) releases the smoothing
        vx += 0.4 * (mo.tx * k - vx); vy += 0.4 * (mo.ty * k - vy)
        val speed = hypot(vx, vy)
        val release = (1.0 - (speed / (0.004 * w)).coerceIn(0.0, 1.0))      // 0 at >= 0.4 % of width per frame
        val rho = (0.3 + 0.4 * strength) * release
        sx += (1 - rho) * (px - sx); sy += (1 - rho) * (py - sy); sa += (1 - rho) * (pa - sa)
        val cl = viewClampFrac * w
        var dx = (sx - px); var dy = (sy - py); var da = (sa - pa)
        dx = dx.coerceIn(-cl, cl); dy = dy.coerceIn(-cl, cl)
        val dl = Math.toRadians(viewClampDeg); da = da.coerceIn(-dl, dl)
        sx = px + dx; sy = py + dy; sa = pa + da                          // anti wind-up

        val view = if (abs(dx) < 0.05 && abs(dy) < 0.05 && abs(da) < 1e-4) null else ViewShift(dx, dy, da)
        val mid = Core.mean(s.track(cntW.submat(h / 4, 3 * h / 4, w / 4, 3 * w / 4))).`val`[0] + 1.0
        Result(s.keep(merged), Status.MERGED, mo.tx * k, mo.ty * k, Math.toDegrees(mo.rot), mo.inlierRatio, min(mid, nMax), ncc, view)
    }

    /** Applies the view correction of [r] to a rendered image. Returns a new Mat (caller releases) or a clone if none. */
    fun applyView(img: Mat, r: Result): Mat {
        val v = r.view ?: return img.clone()
        val w = img.cols(); val h = img.rows(); val cx = w / 2.0; val cy = h / 2.0
        val ca = cos(v.angleRad); val sn = sin(v.angleRad)
        val vm = Mat(2, 3, CvType.CV_64F)
        vm.put(0, 0, ca, -sn, cx - ca * cx + sn * cy + v.dx, sn, ca, cy - sn * cx - ca * cy + v.dy)
        val out = Mat()
        Imgproc.warpAffine(img, out, vm, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        vm.release()
        return out
    }

    /** Drop all history; the current frame becomes the new reference. */
    private fun startOver(flat: Mat, cur8: Mat, st: Status, dx: Double, dy: Double, rotDeg: Double, inl: Double, ncc: Double = 1.0): Result {
        acc?.release(); acc = flat.clone()
        cnt?.release(); cnt = Mat.zeros(flat.size(), CvType.CV_32F)
        prevSmall?.release(); prevSmall = cur8
        goodStreak = 0
        px = 0.0; py = 0.0; pa = 0.0; sx = 0.0; sy = 0.0; sa = 0.0
        return Result(flat.clone(), st, dx, dy, rotDeg, inl, 1.0, ncc)
    }

    // ---------------------------------------------------------------- motion estimation

    private class Motion(val a: Double, val b: Double, val tx: Double, val ty: Double, val inlierRatio: Double, val inliers: Int, val rmsRes: Double) {
        val rot get() = atan2(b, a)
        val scale get() = hypot(a, b)
        fun ok(k: Double, w: Int, t: TemporalStabilizer): Boolean {
            if (inliers < t.minInliers || inlierRatio < t.minInlierRatio || rmsRes > t.maxResidualPx) return false
            if (hypot(tx, ty) * k > t.maxShiftFrac * w) return false
            if (abs(Math.toDegrees(rot)) > t.maxRotDeg) return false
            if (abs(scale - 1.0) > t.maxScaleChange) return false
            return true
        }
    }

    private fun estimate(prev: Mat, cur: Mat, sw: Int, sh: Int): Motion? = scoped { s ->
        // candidate points: coarse grid + Shi-Tomasi corners
        val pts = ArrayList<Point>(192)
        val gx = 14; val gy = 10
        for (j in 0 until gy) for (i in 0 until gx)
            pts.add(Point(sw * (i + 0.5) / gx, sh * (j + 0.5) / gy))
        val corners = s.track(MatOfPoint())
        Imgproc.goodFeaturesToTrack(prev, corners, 100, 0.01, 8.0)
        for (c in corners.toArray()) pts.add(c)
        val p0 = MatOfPoint2f(*pts.toTypedArray()); s.track(p0)
        val p1 = s.track(MatOfPoint2f()); val st = s.track(MatOfByte()); val er = s.track(MatOfFloat())
        Video.calcOpticalFlowPyrLK(prev, cur, p0, p1, st, er, Size(21.0, 21.0), 4,
            TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 20, 0.03), 0, 1e-3)
        val a0 = p0.toArray(); val a1 = p1.toArray(); val sa = st.toArray(); val ea = er.toArray()
        val f = ArrayList<Point>(); val t = ArrayList<Point>()
        for (i in a0.indices) if (sa[i].toInt() == 1 && ea[i] < 25f) { f.add(a0[i]); t.add(a1[i]) }
        if (f.size < 12) return@scoped null
        val mf = s.track(MatOfPoint2f(*f.toTypedArray())); val mt = s.track(MatOfPoint2f(*t.toTypedArray()))
        val inl = s.mat()
        val m = Calib3d.estimateAffinePartial2D(mf, mt, inl, Calib3d.RANSAC, 0.8)
        s.track(m)
        if (m.empty()) return@scoped null
        val a = m.get(0, 0)[0]; val b = m.get(1, 0)[0]; val tx = m.get(0, 2)[0]; val ty = m.get(1, 2)[0]
        val nIn = Core.countNonZero(inl)
        var ss = 0.0; var n = 0
        val ia = ByteArray(f.size); inl.get(0, 0, ia)
        for (i in f.indices) if (ia[i].toInt() != 0) {
            val ex = a * f[i].x - b * f[i].y + tx - t[i].x
            val ey = b * f[i].x + a * f[i].y + ty - t[i].y
            ss += ex * ex + ey * ey; n++
        }
        Motion(a, b, tx, ty, nIn.toDouble() / f.size, nIn, if (n > 0) Math.sqrt(ss / n) else 99.0)
    }

    private fun alignmentNcc(prev: Mat, cur: Mat, mo: Motion): Double = scoped { s ->
        val m = s.mat(2, 3, CvType.CV_64F); m.put(0, 0, mo.a, -mo.b, mo.tx, mo.b, mo.a, mo.ty)
        val wp = s.mat(); Imgproc.warpAffine(prev, wp, m, cur.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        val mx = (cur.cols() * 0.12).toInt(); val my = (cur.rows() * 0.12).toInt()
        val roi = org.opencv.core.Rect(mx, my, cur.cols() - 2 * mx, cur.rows() - 2 * my)
        val a = s.track(wp.submat(roi)); val b = s.track(cur.submat(roi))
        val r = s.mat(); Imgproc.matchTemplate(a, b, r, Imgproc.TM_CCOEFF_NORMED)
        r.get(0, 0)[0]
    }

    /** 1.4826 * median(|x|) via a histogram, ignoring exact zeros (masked background). */
    private fun robustSigma(absImg: Mat, s: MatScope): Double {
        val n = absImg.rows() * absImg.cols()
        val arr = FloatArray(n); absImg.get(0, 0, arr)
        val nb = 512; val mx = 8.0f; val bins = IntArray(nb); var cnt = 0
        for (v in arr) if (v > 1e-6f) { bins[min(nb - 1, (v / mx * nb).toInt())]++; cnt++ }
        if (cnt < 100) return 0.3
        var acc = 0; var i = 0
        while (i < nb - 1 && acc + bins[i] < cnt / 2) { acc += bins[i]; i++ }
        return 1.4826 * (i + 0.5) * mx / nb
    }
}
