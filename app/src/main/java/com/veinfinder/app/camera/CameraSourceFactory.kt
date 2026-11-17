package com.veinfinder.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.LifecycleOwner
import com.veinfinder.app.CameraEntry
import com.veinfinder.app.FrameSource
import com.veinfinder.app.PhoneCameraSource
import com.veinfinder.app.SourceKind
import com.veinfinder.app.UvcCameraSource
import com.veinfinder.app.nir.IlluminationController
import com.veinfinder.app.nir.NoIllumination
import com.veinfinder.app.nir.OpticsProfile

/**
 * Single place that knows which camera back-ends exist. To add an external NIR camera, a different USB
 * back-end or an illumination board, implement a new factory (or extend this one); MainActivity only
 * talks to this interface.
 */
interface CameraSourceFactory {
    fun enumerate(): List<CameraEntry>
    fun create(entry: CameraEntry, owner: LifecycleOwner): FrameSource?
    fun capabilities(entry: CameraEntry): CameraCapabilities?
    fun illumination(entry: CameraEntry): IlluminationController = NoIllumination
    fun optics(entry: CameraEntry): OpticsProfile = OpticsProfile()
}

class DefaultCameraSourceFactory(private val ctx: Context) : CameraSourceFactory {

    override fun enumerate(): List<CameraEntry> {
        val list = ArrayList<CameraEntry>()
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                val (label, kind) = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Phone back camera ($id)" to SourceKind.PHONE_BUILTIN
                    CameraCharacteristics.LENS_FACING_FRONT -> "Phone front camera ($id)" to SourceKind.PHONE_BUILTIN
                    else -> "External camera ($id)" to SourceKind.CAMERA2_EXTERNAL
                }
                list.add(CameraEntry(label, false, id, kind))
            }
        } catch (_: Exception) {}
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        um.deviceList.values.filter { isUvc(it) }.forEach {
            list.add(CameraEntry("USB: ${it.productName ?: it.deviceName}", true, it.deviceName, SourceKind.USB_UVC))
        }
        return list
    }

    override fun create(entry: CameraEntry, owner: LifecycleOwner): FrameSource? =
        if (entry.usb) {
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            um.deviceList[entry.id]?.let { UvcCameraSource(ctx.applicationContext, it) }
        } else PhoneCameraSource(ctx.applicationContext, owner, entry.id)

    override fun capabilities(entry: CameraEntry): CameraCapabilities? =
        if (entry.usb) null else Camera2Caps.query(ctx, entry.id)

    private fun isUvc(d: UsbDevice): Boolean {
        if (d.deviceClass == UsbConstants.USB_CLASS_VIDEO || d.deviceClass == 239) return true
        for (i in 0 until d.interfaceCount) if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        return false
    }
}
