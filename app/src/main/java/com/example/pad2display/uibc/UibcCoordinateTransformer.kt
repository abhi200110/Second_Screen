package com.example.pad2display.uibc

import com.example.pad2display.media.DisplayScaleMode
import kotlin.math.roundToInt

/**
 * Coordinate Transformer mapping raw Android touchscreen / stylus pixels to
 * the negotiated WFD video resolution space (0..videoWidth-1, 0..videoHeight-1).
 *
 * Grounded in Wi-Fi Display Technical Specification v1.1.0:
 * - Coordinates are normalized with respect to the negotiated video stream resolution.
 * - Coordinate origin (0, 0) is the top-left corner of the rectangular display region.
 */
object UibcCoordinateTransformer {

    /**
     * Transforms view pixel coordinates to normalized stream coordinates.
     *
     * @param touchX Raw X position in pixels on the rendering SurfaceView
     * @param touchY Raw Y position in pixels on the rendering SurfaceView
     * @param viewWidth Width of the SurfaceView in pixels
     * @param viewHeight Height of the SurfaceView in pixels
     * @param videoWidth Negotiated video width (e.g. 1920, 2736, 3000)
     * @param videoHeight Negotiated video height (e.g. 1080, 1200, 1824, 2120)
     * @param scaleMode Active DisplayScaleMode (FIT, FILL_CROP, STRETCH)
     * @return Pair(normalizedX, normalizedY) in stream space, clamped to [0..videoWidth-1, 0..videoHeight-1]
     */
    fun transform(
        touchX: Float,
        touchY: Float,
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        scaleMode: DisplayScaleMode
    ): Pair<Int, Int> {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return Pair(0, 0)
        }

        val maxX = videoWidth - 1
        val maxY = videoHeight - 1

        return when (scaleMode) {
            DisplayScaleMode.STRETCH -> {
                val nx = ((touchX / viewWidth.toFloat()) * maxX).roundToInt().coerceIn(0, maxX)
                val ny = ((touchY / viewHeight.toFloat()) * maxY).roundToInt().coerceIn(0, maxY)
                Pair(nx, ny)
            }

            DisplayScaleMode.FIT -> {
                val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
                val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

                val renderWidth: Float
                val renderHeight: Float
                val offsetX: Float
                val offsetY: Float

                if (videoAspect > viewAspect) {
                    // Pillarbox top & bottom (letterbox)
                    renderWidth = viewWidth.toFloat()
                    renderHeight = viewWidth.toFloat() / videoAspect
                    offsetX = 0f
                    offsetY = (viewHeight.toFloat() - renderHeight) / 2f
                } else {
                    // Pillarbox left & right
                    renderHeight = viewHeight.toFloat()
                    renderWidth = viewHeight.toFloat() * videoAspect
                    offsetY = 0f
                    offsetX = (viewWidth.toFloat() - renderWidth) / 2f
                }

                val relX = touchX - offsetX
                val relY = touchY - offsetY

                val nx = ((relX / renderWidth) * maxX).roundToInt().coerceIn(0, maxX)
                val ny = ((relY / renderHeight) * maxY).roundToInt().coerceIn(0, maxY)
                Pair(nx, ny)
            }

            DisplayScaleMode.FILL_CROP -> {
                val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
                val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

                val renderWidth: Float
                val renderHeight: Float
                val offsetX: Float
                val offsetY: Float

                if (videoAspect > viewAspect) {
                    // Height fits, crop left and right
                    renderHeight = viewHeight.toFloat()
                    renderWidth = viewHeight.toFloat() * videoAspect
                    offsetX = (viewWidth.toFloat() - renderWidth) / 2f
                    offsetY = 0f
                } else {
                    // Width fits, crop top and bottom
                    renderWidth = viewWidth.toFloat()
                    renderHeight = viewWidth.toFloat() / videoAspect
                    offsetX = 0f
                    offsetY = (viewHeight.toFloat() - renderHeight) / 2f
                }

                val relX = touchX - offsetX
                val relY = touchY - offsetY

                val nx = ((relX / renderWidth) * maxX).roundToInt().coerceIn(0, maxX)
                val ny = ((relY / renderHeight) * maxY).roundToInt().coerceIn(0, maxY)
                Pair(nx, ny)
            }
        }
    }
}
