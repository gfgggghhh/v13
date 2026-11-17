package bench

import com.veinfinder.app.processing.*
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/** Leak / robustness stress test.   java ... bench.StressKt <image.png> */
private fun rssMb(): Long = File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter { it.isDigit() }.toLong() / 1024

fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val g = Imgcodecs.imread(args[0], Imgcodecs.IMREAD_GRAYSCALE)
    val rgba = Mat(); Imgproc.cvtColor(g, rgba, Imgproc.COLOR_GRAY2RGBA)
    val rnd = java.util.Random(1)

    fun run(name: String, n: Int, f: (Int) -> Unit) {
        for (i in 0 until 20) f(i)                                       // warm-up
        val r0 = rssMb(); val t0 = System.nanoTime()
        for (i in 0 until n) f(i)
        val r1 = rssMb()
        println("%-34s %4d frames  RSS %4d -> %4d MB  (%+d MB, %+.2f MB/frame)  %.0f ms/frame".format(name, n, r0, r1, r1 - r0, (r1 - r0).toDouble() / n, (System.nanoTime() - t0) / 1e6 / n))
    }
    val N = 400
    run("ORIGINAL legacy code", N) { OriginalVeinProcessor().process(rgba, OrigViewMode.GRAY).release() }
    val p1 = FramePipeline()
    run("new: Legacy level (ENHANCED)", N) { p1.process(rgba, DisplayMode.ENHANCED, ProcessingConfig(level = EnhanceLevel.LEGACY), ViewMode.GRAY).shown.release() }
    val p2 = FramePipeline()
    run("new: Medium (ENHANCED)", N) { p2.process(rgba, DisplayMode.ENHANCED, ProcessingConfig(), ViewMode.GRAY).shown.release() }
    val p3 = FramePipeline()
    run("new: Medium (STABILIZED, w/ motion)", N) { i ->
        val m = Mat(2, 3, CvType.CV_64F); m.put(0, 0, 1.0, 0.0, (i % 7) * 1.5 - 4, 0.0, 1.0, (i % 5) * 1.0 - 2)
        val w = Mat(); Imgproc.warpAffine(rgba, w, m, rgba.size(), Imgproc.INTER_LINEAR, Core.BORDER_REFLECT)
        p3.process(w, DisplayMode.STABILIZED, ProcessingConfig(), ViewMode.GREEN).shown.release(); w.release(); m.release()
    }
    val modes = DisplayMode.values(); val lv = EnhanceLevel.values()
    val p4 = FramePipeline()
    run("rapid mode/level switching", N) { i ->
        p4.process(rgba, modes[i % 3], ProcessingConfig(level = lv[(i / 3) % 4], contrast = (i * 7) % 101, noiseReduction = (i * 13) % 101), ViewMode.values()[i % 3]).shown.release()
    }

    // degenerate inputs must not throw and must return a same-size 4-channel image
    val cases = linkedMapOf<String, Mat>()
    fun solid(w: Int, h: Int, v: Double) = Mat(h, w, CvType.CV_8UC4, Scalar(v, v, v, 255.0))
    cases["all black"] = solid(640, 480, 0.0); cases["all white"] = solid(640, 480, 255.0); cases["flat grey"] = solid(640, 480, 128.0)
    cases["pure noise"] = Mat(480, 640, CvType.CV_8UC4).also { Core.randu(it, 0.0, 255.0) }
    cases["tiny 64x48"] = solid(64, 48, 100.0).also { Core.randu(it, 60.0, 160.0) }
    cases["160x120"] = Mat().also { Imgproc.resize(rgba, it, Size(160.0, 120.0)) }
    cases["odd 641x479"] = Mat().also { Imgproc.resize(rgba, it, Size(641.0, 479.0)) }
    cases["1280x720"] = Mat().also { Imgproc.resize(rgba, it, Size(1280.0, 720.0)) }
    var fail = 0
    for ((name, m) in cases) for (d in modes) for (l in lv) {
        val p = FramePipeline()
        try {
            repeat(4) {
                val o = p.process(m, d, ProcessingConfig(level = l), ViewMode.GRAY)
                require(o.shown.size() == m.size() && o.shown.channels() == 4) { "bad output size" }
                o.shown.release()
            }
        } catch (e: Throwable) { fail++; println("FAIL  $name / $d / $l : ${e.javaClass.simpleName}: ${e.message}") }
        p.release()
    }
    println("degenerate-input cases: ${cases.size * modes.size * lv.size} runs, failures = $fail")

    // input must never be modified
    val before = rgba.clone(); val pp = FramePipeline()
    for (d in modes) for (l in lv) pp.process(rgba, d, ProcessingConfig(level = l), ViewMode.GRAY).shown.release()
    val diff = Mat(); Core.absdiff(rgba, before, diff)
    println("input frame modified by any pipeline: " + (Core.countNonZero(diff.reshape(1)) != 0))
}
