package bench

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Display modes of the original program (processType 0 / 1 / 2). */
enum class OrigViewMode { GRAY, NEGATIVE, GREEN }

/**
 * Port of ImageProcess.CacualteAndReturnImg() from EnhanceVideoFrm.exe (Windows version).
 * Same constants as the original: sigma1 = 58.4 (kernel 191), sigma2 = 29.6 (kernel 95), sigma3 = 2.0 (kernel 3),
 * k (0..1, default 0.9) and b (0..10, default 10) are the two user settings from the .config file.
 */
class OriginalVeinProcessor(@Volatile var k: Double = 0.9, @Volatile var b: Double = 10.0) {

    var sigma1 = 58.4
    var sigma2 = 29.6
    var sigma3 = 2.0

    /** Takes an RGBA frame, returns an RGBA frame (caller must release). */
    fun process(rgba: Mat, mode: OrigViewMode): Mat {
        val kk = k
        val bb = b

        val gray = Mat(); val f = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        gray.convertTo(f, CvType.CV_32F)                                    // imgDouble

        val g1 = Mat(); val d = Mat(); val a = Mat(); val g2 = Mat(); val g3 = Mat()
        Imgproc.GaussianBlur(f, g1, Size(191.0, 191.0), sigma1)             // background / illumination
        Core.subtract(f, g1, d)
        Core.absdiff(d, Mat.zeros(d.size(), d.type()), a)
        Imgproc.GaussianBlur(a, g2, Size(95.0, 95.0), sigma2)               // local contrast
        Core.divide(d, g2, g3, 0.1)                                         // 0.1 * d / g2
        normalize01(g3)

        val mean = MatOfDouble(); val std = MatOfDouble()
        Core.meanStdDev(g3, mean, std)
        val t = mean.get(0, 0)[0] - kk * std.get(0, 0)[0]                   // threshold = avg - k * sdv

        val tmp = Mat(); val g4 = Mat()
        Core.add(g3, Scalar.all(1e-8), tmp)
        Core.divide(t, tmp, tmp)                                            // t / (g3 + 1e-8)
        Core.multiply(tmp, Scalar.all(bb), tmp)                             // * b
        Core.add(tmp, Scalar.all(1.0), tmp)                                 // + 1
        Core.divide(1.0, tmp, g4)                                           // 1 / (1 + b*t/g3)
        normalize01(g4)

        val g6 = Mat(); val byteImg = Mat()
        Imgproc.GaussianBlur(g4, g6, Size(3.0, 3.0), sigma3)
        g6.convertTo(byteImg, CvType.CV_8U, 255.0, 0.0)
        Imgproc.equalizeHist(byteImg, byteImg)

        val out = Mat()
        when (mode) {
            OrigViewMode.GRAY -> Imgproc.cvtColor(byteImg, out, Imgproc.COLOR_GRAY2RGBA)
            OrigViewMode.NEGATIVE -> {
                val inv = Mat(); Core.bitwise_not(byteImg, inv)
                Imgproc.cvtColor(inv, out, Imgproc.COLOR_GRAY2RGBA); inv.release()
            }
            OrigViewMode.GREEN -> {
                val zero = Mat.zeros(byteImg.size(), CvType.CV_8U)
                val alpha = Mat(byteImg.size(), CvType.CV_8U, Scalar(255.0))
                Core.merge(listOf(zero, byteImg, zero, alpha), out)         // R=0, G=value, B=0
                zero.release(); alpha.release()
            }
        }
        listOf(gray, f, g1, d, a, g2, g3, mean, std, tmp, g4, g6, byteImg).forEach { it.release() }
        return out
    }

    private fun normalize01(m: Mat) {
        val mm = Core.minMaxLoc(m)
        Core.subtract(m, Scalar.all(mm.minVal), m)
        val mx = Core.minMaxLoc(m).maxVal
        if (mx > 0) Core.divide(m, Scalar.all(mx), m)
    }
}
