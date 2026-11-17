package com.veinfinder.app.processing

/** What the user sees. ORIGINAL is never modified; ENHANCED is spatial only; STABILIZED adds the temporal stage. */
enum class DisplayMode { ORIGINAL, ENHANCED, STABILIZED }

/** Colour rendering of the processed image (kept from the original app). */
enum class ViewMode { GRAY, NEGATIVE, GREEN }

/**
 * LEGACY reproduces the original Windows algorithm exactly (uses k and b) so results can always be compared.
 * LOW / MEDIUM / HIGH use the new noise-aware pipeline with increasing vein emphasis.
 */
enum class EnhanceLevel(val label: String) {
    LEGACY("Legacy (original algorithm)"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High")
}

/**
 * Immutable per-frame snapshot of everything the pipeline needs. The UI thread builds a new one when a
 * setting changes; the processing thread only reads it, so there are no races.
 */
data class ProcessingConfig(
    val level: EnhanceLevel = EnhanceLevel.MEDIUM,
    /** 0..100, 50 = neutral. Gain of the final tone curve. */
    val contrast: Int = 50,
    /** 0..100. Spatial denoise before/after flattening; also raises the noise floor of the local-contrast stage. */
    val noiseReduction: Int = 50,
    /** Legacy algorithm constants (only used by EnhanceLevel.LEGACY). */
    val k: Double = 0.9,
    val b: Double = 10.0,
    /** 0..100. 0 = no temporal averaging and no view smoothing. */
    val stabilizeStrength: Int = 60,
    /** Optional advanced override of the per-level constants (used by the bench / future expert settings). */
    val tuning: LevelTuning? = null
)

/** Overrides for a level's constants. Null fields keep the level default. */
data class LevelTuning(
    val ridgeGain: Double? = null,
    val claheClip: Double? = null,
    val toneGain: Double? = null,
    val edgeBeta: Double? = null,
    val ridgeSigmas: DoubleArray? = null
)

/** Tunable constants of one enhancement level. Values are provisional until validated on real captures. */
internal data class LevelParams(
    val ridgeGain: Double,      // how strongly thin dark ridges (veins) are emphasised
    val claheClip: Double,      // 0 = no CLAHE
    val toneGain: Double,       // base gain of the tone curve before the user's contrast control
    val edgeBeta: Double = 1.2,
    val ridgeSigmas: DoubleArray = doubleArrayOf(1.8, 3.2)
)

internal fun ProcessingConfig.params(): LevelParams {
    val b = level.params(); val o = tuning ?: return b
    return LevelParams(o.ridgeGain ?: b.ridgeGain, o.claheClip ?: b.claheClip, o.toneGain ?: b.toneGain, o.edgeBeta ?: b.edgeBeta, o.ridgeSigmas ?: b.ridgeSigmas)
}

internal fun EnhanceLevel.params(): LevelParams = when (this) {
    EnhanceLevel.LEGACY -> LevelParams(0.0, 0.0, 1.0)
    EnhanceLevel.LOW -> LevelParams(0.50, 0.0, 0.45)
    EnhanceLevel.MEDIUM -> LevelParams(1.00, 1.5, 0.45)
    EnhanceLevel.HIGH -> LevelParams(1.60, 2.5, 0.55)
}
