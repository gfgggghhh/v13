package com.veinfinder.app.processing

import org.opencv.core.Mat

/**
 * Owns every temporary Mat created through it and releases them all in [close].
 *
 * The original code released temporaries by hand, so any exception (or an inline `Mat.zeros(...)` passed
 * as an argument) leaked native memory on every frame. Use it as:
 *
 *     scoped { s -> val tmp = s.mat(); ...; s.keep(result) }
 */
class MatScope : AutoCloseable {
    private val owned = ArrayList<Mat>(32)

    fun mat(): Mat = Mat().also { owned.add(it) }
    fun mat(rows: Int, cols: Int, type: Int): Mat = Mat(rows, cols, type).also { owned.add(it) }
    fun zeros(rows: Int, cols: Int, type: Int): Mat = Mat.zeros(rows, cols, type).also { owned.add(it) }
    fun <T : Mat> track(m: T): T { owned.add(m); return m }

    /** Hands ownership of [m] to the caller: it will NOT be released by [close]. */
    fun keep(m: Mat): Mat {
        val i = owned.indexOfFirst { it === m }
        if (i >= 0) owned.removeAt(i)
        return m
    }

    override fun close() {
        for (m in owned) m.release()
        owned.clear()
    }
}

inline fun <R> scoped(block: (MatScope) -> R): R = MatScope().use(block)
