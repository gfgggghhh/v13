package com.veinfinder.app

import com.veinfinder.app.camera.CameraCapabilities
import com.veinfinder.app.camera.CameraControls
import com.veinfinder.app.camera.Res
import org.opencv.core.Mat

/** What the camera should deliver. */
data class CaptureConfig(val resolution: Res = Res(640, 480))

/** A camera that delivers RGBA frames. The receiver owns (and must release) every Mat it gets. */
interface FrameSource {
    fun start(config: CaptureConfig, onFrame: (Mat) -> Unit, onError: (String) -> Unit)
    fun stop()

    /** Applies (a subset of) the controls while running. Sources that cannot control anything ignore it. */
    fun applyControls(controls: CameraControls) {}

    /** Latest values chosen by the camera's auto algorithms (null if unknown): used to seed the manual sliders. */
    fun autoValues(): AutoValues? = null
}

data class AutoValues(val exposureTimeNs: Long, val iso: Int, val focusDiopters: Float)

enum class SourceKind { PHONE_BUILTIN, CAMERA2_EXTERNAL, USB_UVC }

data class CameraEntry(val label: String, val usb: Boolean, val id: String, val kind: SourceKind = if (usb) SourceKind.USB_UVC else SourceKind.PHONE_BUILTIN)
