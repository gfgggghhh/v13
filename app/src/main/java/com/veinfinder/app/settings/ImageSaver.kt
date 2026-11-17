package com.veinfinder.app.settings

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Saves bitmaps under Pictures/Veinfinder[/subDir]. PNG is lossless (used for the untouched original). */
object ImageSaver {
    fun save(ctx: Context, bmp: Bitmap, subDir: String?, name: String, png: Boolean): Uri? {
        val rel = "Veinfinder" + (subDir?.let { "/$it" } ?: "")
        val fmt = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val mime = if (png) "image/png" else "image/jpeg"
        val full = name + if (png) ".png" else ".jpg"
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, full)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + rel)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            uri?.let { ctx.contentResolver.openOutputStream(it)?.use { os -> bmp.compress(fmt, 95, os) } }
            uri
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), rel).apply { mkdirs() }
            val f = File(dir, full)
            FileOutputStream(f).use { bmp.compress(fmt, 95, it) }
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        }
    }
}
