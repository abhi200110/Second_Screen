package com.example.pad2display.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "VideoDecoder"

data class NalUnitInfo(val type: Int, val offset: Int, val length: Int)

class VideoDecoder(
    private val onLog: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var codec: MediaCodec? = null
    private var renderSurface: Surface? = null
    private var outputJob: Job? = null
    private var inputJob: Job? = null
    private var isConfigured = false
    private var framesRendered = 0L

    val framesRenderedCount: Long
        get() = framesRendered

    var onRequestKeyframe: (() -> Unit)? = null

    // Cached Parameter Sets for immediate decoder bootstrapping
    @Volatile
    var cachedSps: ByteArray? = null
        private set
    @Volatile
    var cachedPps: ByteArray? = null
        private set
    @Volatile
    private var pendingKeyframe: ByteArray? = null

    private data class AccessUnit(
        val data: ByteArray,
        val length: Int,
        val ptsUs: Long,
        val isKeyframe: Boolean
    )

    // Decoupled input queue so RTP UDP reception is never blocked by MediaCodec
    private val inputQueue = LinkedBlockingQueue<AccessUnit>(60)

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
                    // Request fresh IDR keyframe to paint the new surface immediately
                    onRequestKeyframe?.invoke()
                    return
                } catch (e: Exception) {
                    log("Dynamic setOutputSurface failed ($e), re-initializing codec on new surface")
                }
            }
            initCodec(surface)
            onRequestKeyframe?.invoke()
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

            // Inject cached parameter sets if available to configure codec immediately
            val sps = cachedSps
            val pps = cachedPps
            if (sps != null && pps != null) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                log("Configured MediaCodec with pre-cached SPS (${sps.size} B) & PPS (${pps.size} B)")
            }

            val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            newCodec.configure(format, surface, null, 0)
            newCodec.start()

            codec = newCodec
            isConfigured = true
            renderSurface = surface
            framesRendered = 0L
            log("Hardware H.264 Video Decoder started on Surface (${newCodec.name})")

            // Start dedicated input feeding worker
            inputJob = scope.launch {
                while (isActive) {
                    val unit = try {
                        inputQueue.poll(20, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        break
                    } ?: continue

                    val c = codec ?: continue
                    if (!isConfigured) continue

                    try {
                        val inIndex = c.dequeueInputBuffer(10000)
                        if (inIndex >= 0) {
                            val inBuffer: ByteBuffer? = c.getInputBuffer(inIndex)
                            if (inBuffer != null) {
                                val capacity = inBuffer.capacity()
                                if (unit.length > capacity) {
                                    log("Warning: NAL size (${unit.length} B) exceeds buffer ($capacity B)")
                                    c.queueInputBuffer(inIndex, 0, 0, unit.ptsUs, 0)
                                } else {
                                    inBuffer.clear()
                                    inBuffer.put(unit.data, 0, unit.length)
                                    c.queueInputBuffer(inIndex, 0, unit.length, unit.ptsUs, 0)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Codec flush or reset in progress
                    }
                }
            }

            // Start output buffer polling loop
            outputJob = scope.launch {
                val bufferInfo = MediaCodec.BufferInfo()
                var lastRenderTime = System.currentTimeMillis()

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
                                lastRenderTime = System.currentTimeMillis()
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

                        // Auto-recovery: If frames are arriving in queue but not rendering for > 2.5s, request keyframe
                        if (inputQueue.isNotEmpty() && (System.currentTimeMillis() - lastRenderTime > 2500)) {
                            log("Self-healing: Decoder output quiet for 2.5s while frames queued. Requesting IDR keyframe...")
                            lastRenderTime = System.currentTimeMillis()
                            onRequestKeyframe?.invoke()
                        }
                    } catch (_: Exception) {
                        // Buffer loop exception during codec stop/reset
                    }
                }
            }

            // If a cached keyframe was stored before surface attached, queue it immediately
            pendingKeyframe?.let { kf ->
                inputQueue.offer(AccessUnit(kf, kf.size, System.nanoTime() / 1000, true))
            }
        } catch (e: Exception) {
            log("Failed to initialize MediaCodec: ${e.message}")
        }
    }

    /**
     * Feed video elementary stream access unit from TsDemuxer.
     * Extracts SPS/PPS, caches keyframes, and submits asynchronously to input queue.
     */
    fun decodeAccessUnit(data: ByteArray, length: Int, ptsUs: Long) {
        // Scan NAL units for SPS (7), PPS (8), IDR (5)
        val nals = extractNalUnits(data, length)
        var hasIdr = false

        for (nal in nals) {
            when (nal.type) {
                7 -> { // SPS
                    val spsBytes = normalizeNalWithStartCode(data.copyOfRange(nal.offset, nal.offset + nal.length))
                    cachedSps = spsBytes
                    log("Extracted and cached SPS (${spsBytes.size} bytes)")
                }
                8 -> { // PPS
                    val ppsBytes = normalizeNalWithStartCode(data.copyOfRange(nal.offset, nal.offset + nal.length))
                    cachedPps = ppsBytes
                    log("Extracted and cached PPS (${ppsBytes.size} bytes)")
                }
                5 -> { // IDR Keyframe
                    hasIdr = true
                }
            }
        }

        if (hasIdr) {
            pendingKeyframe = data.copyOf(length)
        }

        // Bound queue to prevent memory growth or latency spikes during network bursts
        if (inputQueue.size >= 45 && !hasIdr) {
            // Drop oldest non-keyframe if queue is backing up
            return
        }

        inputQueue.offer(AccessUnit(data.copyOf(length), length, ptsUs, hasIdr))
    }

    @Synchronized
    fun releaseCodec() {
        inputJob?.cancel()
        inputJob = null
        outputJob?.cancel()
        outputJob = null
        inputQueue.clear()

        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        isConfigured = false
        _activeFormat.value = null
        log("Hardware Video Decoder released")
    }

    private fun extractNalUnits(data: ByteArray, length: Int): List<NalUnitInfo> {
        val list = mutableListOf<NalUnitInfo>()
        var i = 0
        var lastStart = -1
        var lastType = -1

        while (i <= length - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                var startCodeLen = 0
                if (data[i + 2] == 1.toByte()) {
                    startCodeLen = 3
                } else if (data[i + 2] == 0.toByte() && i <= length - 5 && data[i + 3] == 1.toByte()) {
                    startCodeLen = 4
                }
                if (startCodeLen > 0) {
                    if (lastStart >= 0) {
                        list.add(NalUnitInfo(lastType, lastStart, i - lastStart))
                    }
                    lastType = data[i + startCodeLen].toInt() and 0x1F
                    lastStart = i
                    i += startCodeLen
                    continue
                }
            }
            i++
        }
        if (lastStart >= 0 && lastStart < length) {
            list.add(NalUnitInfo(lastType, lastStart, length - lastStart))
        }
        return list
    }

    private fun normalizeNalWithStartCode(raw: ByteArray): ByteArray {
        if (raw.size >= 4 && raw[0] == 0.toByte() && raw[1] == 0.toByte() && raw[2] == 0.toByte() && raw[3] == 1.toByte()) {
            return raw
        }
        if (raw.size >= 3 && raw[0] == 0.toByte() && raw[1] == 0.toByte() && raw[2] == 1.toByte()) {
            val out = ByteArray(raw.size + 1)
            out[0] = 0
            System.arraycopy(raw, 0, out, 1, raw.size)
            return out
        }
        val out = ByteArray(raw.size + 4)
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
        System.arraycopy(raw, 0, out, 4, raw.size)
        return out
    }

    private fun log(msg: String) {
        Log.w(TAG, msg)
        onLog("[Decoder] $msg")
    }
}
