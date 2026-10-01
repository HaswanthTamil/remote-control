package com.remotecontrol.ui.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * One decoded laptop screenshot. Frames arrive as raw JPEG binaries from the
 * relay, so decoding is the expensive step: we sub-sample large frames down to
 * `maxDimension` which keeps 5 fps streaming cheap on a phone.
 */
data class RemoteFrame(val bitmap: Bitmap, val width: Int, val height: Int)

fun decodeFrame(bytes: ByteArray, maxDimension: Int = 1280): RemoteFrame? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    if (maxDimension > 0) {
        while (bounds.outWidth / (sample * 2) >= maxDimension ||
            bounds.outHeight / (sample * 2) >= maxDimension
        ) {
            sample *= 2
        }
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return RemoteFrame(bitmap = bitmap, width = bounds.outWidth, height = bounds.outHeight)
}