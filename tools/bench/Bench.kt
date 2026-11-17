package bench

import com.veinfinder.app.VeinProcessor
import com.veinfinder.app.processing.*
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Offline bench that runs the app's real processing classes on a folder of images / frames.
 *
 *   spatial <inDir> <outDir> <variant,variant,...>
 *   seq     <inDir> <outDir> <variant,variant,...>      (frames sorted by name, state carried between frames)
 *
 * Variants:  original | legacy0 (verbatim copy of the old code) | legacy | low | medium | high   -> enhanced
 *            stab:<level>:<strength>                                                           -> stabilized (seq only)
 *            any variant may carry  @nr=<0..100>  @ct=<0..100>   e.g.  medium@nr=70@ct=60
 */
fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val mode = args[0]; val inDir = File(args[1]); val outDir = File(args[2]); val variants = args[3].split(",")
    val files = inDir.listFiles { f -> f.extension.lowercase() in listOf("png", "jpg", "bmp") }!!.sortedBy { it.name }
    outDir.mkdirs()
    val timing = HashMap<String, ArrayList<Double>>()
    val log = StringBuilder("variant,frame,status,dx,dy,rot,inl,eff,ncc,ms\n")

    class Runner(val spec: String) {
        val parts = spec.split("@"); val head = parts[0].split(":")
        var nr = 50; var ct = 50; val tu = HashMap<String, Double>(); var sg: DoubleArray? = null; var gs: Double? = null; var gr: Double? = null
        init { parts.drop(1).forEach { p -> val (k, v) = p.split("="); when (k) { "nr" -> nr = v.toInt(); "ct" -> ct = v.toInt(); "rg" -> tu["rg"] = v.toDouble(); "cl" -> tu["cl"] = v.toDouble(); "tg" -> tu["tg"] = v.toDouble(); "eb" -> tu["eb"] = v.toDouble(); "gs" -> gs = v.toDouble(); "gr" -> gr = v.toDouble(); "sg" -> sg = v.split("/").map { it.toDouble() }.toDoubleArray() } } }
        val name = head[0]
        val level = when (if (name == "stab") head[1] else name) {
            "legacy" -> EnhanceLevel.LEGACY; "low" -> EnhanceLevel.LOW; "high" -> EnhanceLevel.HIGH; else -> EnhanceLevel.MEDIUM }
        val strength = if (name == "stab" && head.size > 2) head[2].toInt() else 60
        val cfg = ProcessingConfig(level = level, contrast = ct, noiseReduction = nr, stabilizeStrength = strength,
            tuning = if (tu.isEmpty() && sg == null) null else LevelTuning(tu["rg"], tu["cl"], tu["tg"], tu["eb"], sg))
        val pipe = FramePipeline().also { p -> gs?.let { p.stabilizer.gateStart = it }; gr?.let { p.stabilizer.gateRange = it } }
        val orig = OriginalVeinProcessor()
        fun run(rgba: Mat): Mat = when (name) {
            "original" -> rgba.clone()
            "legacy0" -> orig.process(rgba, OrigViewMode.GRAY)
            "stab" -> pipe.process(rgba, DisplayMode.STABILIZED, cfg, ViewMode.GRAY).let { o ->
                val i = o.info; log.append("$spec,${cur},${i.stabilizer},${"%.2f".format(i.dx)},${"%.2f".format(i.dy)},${"%.2f".format(i.rotDeg)},${"%.2f".format(i.inlierRatio)},${"%.1f".format(i.effectiveFrames)},${"%.3f".format(i.ncc)},${"%.1f".format(i.processMs)}\n"); o.shown }
            else -> pipe.process(rgba, DisplayMode.ENHANCED, cfg, ViewMode.GRAY).shown
        }
        var cur = 0
    }
    val runners = variants.map { Runner(it) }
    for ((idx, f) in files.withIndex()) {
        val g = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
        val rgba = Mat(); Imgproc.cvtColor(g, rgba, Imgproc.COLOR_GRAY2RGBA)
        for (r in runners) {
            r.cur = idx
            val t0 = System.nanoTime()
            val o = r.run(rgba)
            val ms = (System.nanoTime() - t0) / 1e6
            timing.getOrPut(r.spec) { ArrayList() }.add(ms)
            val gray = Mat(); Imgproc.cvtColor(o, gray, Imgproc.COLOR_RGBA2GRAY)
            val dir = if (mode == "seq") File(outDir, r.spec.replace(":", "_").replace("@", "_").replace("/", "-")).also { it.mkdirs() } else outDir
            val name = if (mode == "seq") f.name else f.nameWithoutExtension + "__" + r.spec.replace(":", "_").replace("@", "_").replace("/", "-") + ".png"
            Imgcodecs.imwrite(File(dir, name).path, gray)
            o.release(); gray.release()
        }
        g.release(); rgba.release()
    }
    for ((k, v) in timing) {
        val w = if (v.size > 5) v.drop(3) else v   // skip JIT warm-up
        println("%-28s n=%d  mean=%.1f ms  p95=%.1f ms".format(k, v.size, w.average(), w.sorted()[(w.size * 0.95).toInt().coerceAtMost(w.size - 1)]))
    }
    File(outDir, "stab_log.csv").writeText(log.toString())
    runners.forEach { it.pipe.release() }
}
