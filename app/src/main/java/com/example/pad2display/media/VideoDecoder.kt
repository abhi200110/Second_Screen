package com.example.pad2display.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

private const val TAG = "VideoDecoder"

class VideoDecoder(
    private val onLog: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var codec: MediaCodec? = null
    private var renderSurface: Surface? = null
    private var outputJob: Job? = null
    private var isConfigured = false
    private var framesRendered = 0L

    private val _activeFormat = MutableStateFlow<VideoFormatInfo?>(null)
    val activeFormat: StateFlow<VideoFormatInfo?> = _activeFormat.asStateFlow()

    @Synchronized
    fun setSurface(surface: Surface?) {
        renderSurface = surface
        if (surface != null && surface.isValid) {
            val c = codec
            if (c != null && isConfigured) {
                try {
                    c.setOutputSurface(surface)
                    log("Dynamically re-attached Surface to active hardware decoder (${_activeFormat.value?.displayString ?: "running"})")
                    return
                } catch (e: Exception) {
                    log("Dynamic setOutputSurface failed ($e), re-initializing codec on new surface")
                }
            }
            initCodec(surface)
        } else {
            // When surface is detached (e.g. app in background or device rotating),
            // do not release codec immediately so SPS/PPS state and decoder continuity are kept alive!
            log("Surface detached (in background / rotating). Decoder keeping state alive.")
        }
    }

    @Synchronized
    private fun initCodec(surface: Surface) {
        releaseCodec()
        try {
            // Default 1920x1080 @ 60fps AVC decoder
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            // Low latency decoding flags for Snapdragon / Android 11+
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // 2 MB input buffer accommodating high-detail 3K / 4K IDR keyframes
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)

            val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            newCodec.configure(format, surface, null, 0)
            newCodec.start()

            codec = newCodec
            isConfigured = true
            renderSurface = surface
            framesRendered = 0L
            log("Hardware H.264 Video Decoder started on Surface (${newCodec.name})")

            // Start output buffer polling loop
            outputJob = scope.launch {
                val bufferInfo = MediaCodec.BufferInfo()
                while (isActive) {
                    val c = codec ?: break
                    try {
                        val outIndex = c.dequeueOutputBuffer(bufferInfo, 10000)
                        if (outIndex >= 0) {
                            val currentSurface = renderSurface
                            val shouldRender = currentSurface != null && currentSurface.isValid
                            c.releaseOutputBuffer(outIndex, shouldRender)
                            if (shouldRender) {
                                framesRendered++
                                if (framesRendered % 300L == 1L) {
                                    log("Video pipeline rendering active: $framesRendered frames displayed")
                                }
                            }
                        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFormat = c.outputFormat
                            log("Decoder output format changed: $newFormat")
                            val width = if (newFormat.containsKey("crop-right") && newFormat.containsKey("crop-left")) {
                                newFormat.getInteger("crop-right") - newFormat.getInteger("crop-left") + 1
                            } else {
                                newFormat.getInteger(MediaFormat.KEY_WIDTH)
                            }
                            val height = if (newFormat.containsKey("crop-bottom") && newFormat.containsKey("crop-top")) {
                                newFormat.getInteger("crop-bottom") - newFormat.getInteger("crop-top") + 1
                            } else {
                                newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                            }
                            if (width > 0 && height > 0) {
                                val fmt = VideoFormatInfo(width, height)
                                _activeFormat.value = fmt
                                log("Active Video Format: ${fmt.displayString}")
                            }
                        }
                    } catch (_: Exception) {
                        // Buffer loop exception during codec stop/reset
                    }
                }
            }
        } catch (e: Exception) {
            log("Failed to initialize MediaCodec: ${e.message}")
        }
    }

    fun decodeAccessUnit(data: ByteArray, length: Int, ptsUs: Long) {
        val c = codec ?: return
        if (!isConfigured) return

        try {
            val inIndex = c.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val inBuffer: ByteBuffer? = c.getInputBuffer(inIndex)
                if (inBuffer != null) {
                    val capacity = inBuffer.capacity()
                    if (length > capacity) {
                        log("Warning: NAL size ($length B) exceeds input buffer capacity ($capacity B) - skipping")
                        c.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                    } else {
                        inBuffer.clear()
                        inBuffer.put(data, 0, length)
                        c.queueInputBuffer(inIndex, 0, length, ptsUs, 0)
                    }
                }
            }
        } catch (_: Exception) {
            // Buffer queue exception during codec stop/reset
        }
    }

    @Synchronized
    fun releaseCodec() {
        outputJob?.cancel()
        outputJob = null
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        isConfigured = false
        _activeFormat.value = null
        log("Hardware Video Decoder released")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog("[Decoder] $msg")
    }
}
