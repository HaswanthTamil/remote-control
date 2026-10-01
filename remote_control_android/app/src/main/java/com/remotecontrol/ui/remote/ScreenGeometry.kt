package com.remotecontrol.ui.remote

/** The rectangle of the stage that actually shows the laptop screen. */
data class ScreenRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height
}

/** Laptop pointer coordinates in the relay's 0..1 space. */
data class NormalizedPoint(val x: Float, val y: Float)

/**
 * `object-fit: contain` geometry for the remote screen.
 *
 * The relay maps `(0,0)` to the laptop's top-left pixel and `(1,1)` to its
 * bottom-right, so points are only ever mapped from the rectangle we actually
 * draw the frame into. Touches that land in the letterbox (or outside the
 * stage) are rejected rather than clamped, as required by
 * `docs/io-mapping-PRD.md`.
 */
object ScreenGeometry {

    fun containRect(
        frameWidth: Int,
        frameHeight: Int,
        stageWidth: Int,
        stageHeight: Int,
    ): ScreenRect {
        if (frameWidth <= 0 || frameHeight <= 0 || stageWidth <= 0 || stageHeight <= 0) {
            return ScreenRect(0f, 0f, 0f, 0f)
        }
        val scale = minOf(
            stageWidth.toFloat() / frameWidth,
            stageHeight.toFloat() / frameHeight,
        )
        val width = frameWidth * scale
        val height = frameHeight * scale
        return ScreenRect(
            left = (stageWidth - width) / 2f,
            top = (stageHeight - height) / 2f,
            width = width,
            height = height,
        )
    }

    /** Returns `null` when the touch is outside the displayed laptop screen. */
    fun normalize(x: Float, y: Float, box: ScreenRect): NormalizedPoint? {
        if (box.isEmpty) return null
        if (!box.contains(x, y)) return null
        return NormalizedPoint(
            x = ((x - box.left) / box.width).coerceIn(0f, 1f),
            y = ((y - box.top) / box.height).coerceIn(0f, 1f),
        )
    }
}
