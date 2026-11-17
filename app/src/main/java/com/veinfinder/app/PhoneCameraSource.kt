package com.veinfinder.app

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CameraCaptureSession
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.veinfinder.app.camera.Camera2Caps
import com.veinfinder.app.camera.CameraCapabilities
import com.veinfinder.app.camera.CameraControls
import com.veinfinder.app.camera.CameraMath
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.Executors

/**
 * Built-in phone cameras (and USB cameras that the phone itself exposes through Camera2 as "external").
 * CameraX delivers the frames; Camera2 interop adds the controls CameraX does not expose:
 * AE/AWB lock, manual exposure + ISO, manual focus / focus lock and a fixed frame-rate range.
 * Every control is checked against the camera's reported capabilities before it is applied.
 */
@OptIn(ExperimentalCamera2Interop::class)
class PhoneCameraSource(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val cameraId: String
) : FrameSource {

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var executor = Executors.newSingleThreadExecutor()
    private val caps: CameraCapabilities? by lazy { Camera2Caps.query(ctx, cameraId) }

    @Volatile private var lastFocus = 0f
    @Volatile private var lastExposure = 0L
    @Volatile private var lastIso = 0
    @Volatile private var pending: CameraControls? = null
    private var scratch: ByteArray? = null                    // only touched by the analyzer thread

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocus = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposure = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
        }
    }

    override fun start(config: CaptureConfig, onFrame: (Mat) -> Unit, onError: (String) -> Unit) {
        if (executor.isShutdown) executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get(); provider = p
                val builder = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder()
                        // CameraX defaults to 4:3: without this a 16:9 request (e.g. 1280x720) silently resolves to a 4:3 size
                        .setAspectRatioStrategy(
                            if (Math.abs(config.resolution.w.toDouble() / config.resolution.h - 16.0 / 9.0) < 0.05)
                                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(config.resolution.w, config.resolution.h),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                Camera2Interop.Extender(builder).setSessionCaptureCallback(resultCallback)
                val analysis = builder.build()
                analysis.setAnalyzer(executor) { img ->
                    var m: Mat? = null
                    try {
                        val plane = img.planes[0]
                        val w = img.width; val h = img.height
                        val buf = plane.buffer; val stride = plane.rowStride
                        val mat = Mat(h, w, CvType.CV_8UC4); m = mat
                        if (stride == w * 4) {
                            val need = w * h * 4
                            var bytes = scratch; if (bytes == null || bytes.size != need) { bytes = ByteArray(need); scratch = bytes }   // reused: no 8 MB allocation per frame
                            buf.position(0); buf.get(bytes, 0, need); mat.put(0, 0, bytes)
                        } else {
                            val row = ByteArray(w * 4)
                            for (y in 0 until h) { buf.position(y * stride); buf.get(row, 0, w * 4); mat.put(y, 0, row) }
                        }
                        val rot = when (img.imageInfo.rotationDegrees) {
                            90 -> Core.ROTATE_90_CLOCKWISE
                            180 -> Core.ROTATE_180
                            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
                            else -> -1
                        }
                        val ready: Mat
                        if (rot >= 0) { val r = Mat(); try { Core.rotate(mat, r, rot) } catch (t: Throwable) { r.release(); throw t }; mat.release(); ready = r }
                        else ready = mat
                        m = null                                   // ownership passes to the receiver
                        onFrame(ready)
                    } catch (t: Throwable) {
                        m?.release()
                        onError(t.message ?: "Frame error")
                    } finally { img.close() }
                }
                val selector = CameraSelector.Builder().addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                }.build()
                p.unbindAll()
                camera = p.bindToLifecycle(owner, selector, analysis)
                pending?.let { applyControls(it) }
            } catch (e: Exception) {
                onError(e.message ?: "Camera error")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    override fun applyControls(controls: CameraControls) {
        pending = controls
        val cam = camera ?: return                       // applied right after binding
        val c = caps ?: return
        val e = CameraMath.effective(c, controls)
        try {
            val ev = if (c.evSupported) e.evIndex else 0
            cam.cameraControl.setExposureCompensationIndex(ev)

            val b = CaptureRequestOptions.Builder()
            if (e.manualExposure) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, e.exposureTimeNs)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, e.iso)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, CameraMath.frameDurationNs(e.exposureTimeNs, e.targetFps))
            } else {
                if (e.aeLock) b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                CameraMath.pickFpsRange(c.fpsRanges, e.targetFps)?.let {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.second))
                }
            }
            if (e.awbLock) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            if (e.manualFocus || e.focusLock) {
                // focus lock = freeze the lens where autofocus put it (last value reported by the camera)
                val d = if (e.manualFocus) e.focusDiopters else CameraMath.clampFocus(c, lastFocus)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, d)
            }
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())   // replaces the previous set
        } catch (ex: Exception) { /* camera closed while applying: ignore */ }
    }

    override fun autoValues(): AutoValues? =
        if (lastExposure > 0) AutoValues(lastExposure, lastIso, lastFocus) else null

    override fun stop() {
        try { provider?.unbindAll() } catch (_: Exception) {}
        provider = null; camera = null; scratch = null
        executor.shutdown()
    }
}
