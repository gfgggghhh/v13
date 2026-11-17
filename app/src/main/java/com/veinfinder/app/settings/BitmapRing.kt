package com.veinfinder.app.settings

import android.graphics.Bitmap

/**
 * A few reusable bitmaps so each frame does not allocate a new 1+ MB bitmap (the old code allocated two per
 * frame, which caused constant GC pressure). The UI shows one while the next frame is drawn into another.
 */
class BitmapRing(private val n: Int = 3) {
    private val ring = arrayOfNulls<Bitmap>(n)
    private var i = 0

    @Synchronized fun next(w: Int, h: Int): Bitmap {
        i = (i + 1) % n
        var b = ring[i]
        if (b == null || b.isRecycled || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); ring[i] = b
        }
        return b!!
    }

    @Synchronized fun clear() { for (k in 0 until n) ring[k] = null }
}
