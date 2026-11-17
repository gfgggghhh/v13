package com.veinfinder.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata

/** Reads what a Camera2 device can really do (varies a lot between phones, so nothing is assumed). */
object Camera2Caps {

    fun query(ctx: Context, cameraId: String): CameraCapabilities? = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ch = cm.getCameraCharacteristics(cameraId)
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val manual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map { Res(it.width, it.height) } ?: emptyList()
        val evr = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val expR = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoR = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        CameraCapabilities(
            cameraId = cameraId, hardwareLevel = level,
            resolutions = CameraMath.offerable(sizes),
            evMin = evr?.lower ?: 0, evMax = evr?.upper ?: 0, evStep = evStep?.toFloat() ?: 0f,
            aeLockSupported = ch.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            awbLockSupported = ch.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            manualSensor = manual,
            exposureTimeMinNs = expR?.lower ?: 0L, exposureTimeMaxNs = expR?.upper ?: 0L,
            isoMin = isoR?.lower ?: 0, isoMax = isoR?.upper ?: 0,
            minFocusDistance = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            afOffSupported = afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF),
            fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.lower to it.upper } ?: emptyList()
        )
    } catch (e: Exception) { null }
}
