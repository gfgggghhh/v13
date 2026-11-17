package com.veinfinder.app.processing

import org.opencv.imgproc.CLAHE

/**
 * Per-stream state of the tone-mapping stage. Percentile statistics are smoothed over time so the image
 * brightness does not flicker from frame to frame (the original recomputed min/max/equalizeHist every frame).
 * Use one instance per output stream (Enhanced / Stabilized) and call [reset] when the camera restarts.
 */
class RenderState {
    var med = Double.NaN
    var spread = Double.NaN
    var clahe: CLAHE? = null
    fun reset() { med = Double.NaN; spread = Double.NaN }
    fun release() { clahe = null }
}
