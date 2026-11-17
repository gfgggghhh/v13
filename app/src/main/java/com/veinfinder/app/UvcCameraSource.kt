package com.veinfinder.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * USB (UVC) webcam connected through OTG cable. Uses the UVCAndroid library (fork of saki4510t/UVCCamera).
 * NOTE: the library API differs slightly between versions - if the build fails here, only this file needs adjusting.
 */
class UvcCameraSource(private val ctx: Context, private val device: UsbDevice) : FrameSource {

    private var monitor: USBMonitor? = null
    private var camera: UVCCamera? = null
    private var width = 640
    private var height = 480
    private var texture: SurfaceTexture? = null
    private var scratch: ByteArray? = null

    override fun start(config: CaptureConfig, onFrame: (Mat) -> Unit, onError: (String) -> Unit) {
        width = config.resolution.w; height = config.resolution.h
        monitor = USBMonitor(ctx, object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(d: UsbDevice?) {}
            override fun onDetach(d: UsbDevice?) { onError("USB camera disconnected") }
            override fun onCancel(d: UsbDevice?) { onError("USB permission denied") }
            override fun onDeviceOpen(d: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {
                try {
                    val cam = UVCCamera(UVCParam())
                    cam.open(ctrlBlock)
                    try {
                        cam.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG)
                    } catch (e: IllegalArgumentException) {
                        cam.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_YUYV)
                    }
                    val tex = SurfaceTexture(0); texture = tex
                    cam.setPreviewTexture(tex)
                    cam.setFrameCallback(IFrameCallback { frame ->
                        val need = width * height * 4
                        if (frame.remaining() < need) return@IFrameCallback      // camera delivered a different size than requested: never build a wrong Mat
                        var bytes = scratch; if (bytes == null || bytes.size != need) { bytes = ByteArray(need); scratch = bytes }
                        frame.get(bytes, 0, need)
                        val m = Mat(height, width, CvType.CV_8UC4)
                        try { m.put(0, 0, bytes) } catch (t: Throwable) { m.release(); return@IFrameCallback }
                        onFrame(m)   // controls (exposure/focus) of UVC cameras are not wired yet: see camera/CameraSourceFactory.kt
                    }, UVCCamera.PIXEL_FORMAT_RGBX)
                    cam.startPreview()
                    camera = cam
                } catch (e: Exception) {
                    onError(e.message ?: "USB camera error")
                }
            }
            override fun onDeviceClose(d: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {}
        }).also {
            it.register()
            it.requestPermission(device)
        }
    }

    override fun stop() {
        try { camera?.stopPreview(); camera?.destroy() } catch (_: Exception) {}
        camera = null
        try { texture?.release() } catch (_: Exception) {}
        texture = null; scratch = null
        try { monitor?.unregister(); monitor?.destroy() } catch (_: Exception) {}
        monitor = null
    }
}
