package com.veinfinder.app.nir

/**
 * Extension points for near-infrared work. Nothing here changes today's behaviour; it defines where the
 * future pieces plug in so MainActivity and the processing code never need to know which hardware is attached.
 *
 *  - [Spectrum]               which light the image was taken with (visible, 850 nm, 940 nm).
 *  - [IlluminationController] switches / dims an external NIR light source (USB-serial, BLE, UVC extension unit...).
 *  - [OpticsProfile]          external lens / band-pass filter description, stored with saved images.
 *  - [com.veinfinder.app.camera.CameraSourceFactory] (see camera/CameraSourceFactory.kt) is where external NIR
 *    cameras and USB (UVC) sources are enumerated and created.
 */
enum class Spectrum(val label: String, val wavelengthNm: Int?) {
    VISIBLE("Visible", null),
    NIR_850("NIR 850 nm", 850),
    NIR_940("NIR 940 nm", 940)
}

interface IlluminationController {
    val supported: Set<Spectrum>
    /** Selects the emitter (no-op if unsupported). */
    fun select(spectrum: Spectrum)
    /** 0..100 % of the maximum drive current. */
    fun setIntensity(percent: Int)
    fun release()
}

/** Default: the phone has no controllable illuminator. */
object NoIllumination : IlluminationController {
    override val supported: Set<Spectrum> = setOf(Spectrum.VISIBLE)
    override fun select(spectrum: Spectrum) {}
    override fun setIntensity(percent: Int) {}
    override fun release() {}
}

/** Free-form description of the optical path; written to the metadata of saved comparison sets. */
data class OpticsProfile(
    val name: String = "Phone camera, no accessories",
    val spectrum: Spectrum = Spectrum.VISIBLE,
    /** Centre wavelength of a band-pass filter in front of the lens, if any. */
    val filterNm: Int? = null,
    val externalLens: Boolean = false
) {
    fun describe() = buildString {
        append(name).append(" | ").append(spectrum.label)
        filterNm?.let { append(" | filter ${it} nm") }
        if (externalLens) append(" | external lens")
    }
}
