package com.example.pad2display.media

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * High-performance lightweight MPEG-TS demuxer for Wi-Fi Display (Miracast / WFD).
 * Extracts H.264 (AVC) elementary stream NAL units from MPEG-2 Transport Stream packets.
 */
class TsDemuxer(
    private val onVideoAccessUnit: (data: ByteArray, length: Int, ptsUs: Long) -> Unit
) {
    private var videoPid: Int = -1
    private val frameBuffer = ByteArrayOutputStream(256 * 1024)
    private var currentPtsUs: Long = 0

    @Synchronized
    fun reset() {
        videoPid = -1
        frameBuffer.reset()
        currentPtsUs = 0
    }

    /**
     * Process an incoming RTP packet payload (starting after the 12-byte RTP header).
     * Usually contains 7 * 188-byte TS packets.
     */
    @Synchronized
    fun processRtpPayload(payload: ByteArray, offset: Int, length: Int) {
        var pos = offset
        val end = offset + length

        while (pos + 188 <= end) {
            // Find 0x47 sync byte
            if (payload[pos] != 0x47.toByte()) {
                pos++
                continue
            }

            parseTsPacket(payload, pos)
            pos += 188
        }
    }

    private fun parseTsPacket(data: ByteArray, start: Int) {
        val b1 = data[start + 1].toInt() and 0xFF
        val b2 = data[start + 2].toInt() and 0xFF
        val b3 = data[start + 3].toInt() and 0xFF

        val transportError = (b1 and 0x80) != 0
        if (transportError) return

        val payloadUnitStart = (b1 and 0x40) != 0
        val pid = ((b1 and 0x1F) shl 8) or b2

        val adaptationControl = (b3 and 0x30) ushr 4
        if (adaptationControl == 0 || adaptationControl == 2) {
            // No payload
            return
        }

        var payloadStart = start + 4
        if (adaptationControl == 3) {
            val adaptLen = data[start + 4].toInt() and 0xFF
            payloadStart = start + 5 + adaptLen
        }

        val packetEnd = start + 188
        if (payloadStart >= packetEnd) return
        val payloadLen = packetEnd - payloadStart

        // Auto-detect video PID if not identified:
        // A video PES packet starts with 00 00 01 E0 (or E0..EF)
        if (payloadUnitStart && payloadLen >= 4) {
            val p0 = data[payloadStart].toInt() and 0xFF
            val p1 = data[payloadStart + 1].toInt() and 0xFF
            val p2 = data[payloadStart + 2].toInt() and 0xFF
            val streamId = data[payloadStart + 3].toInt() and 0xFF

            if (p0 == 0x00 && p1 == 0x00 && p2 == 0x01 && streamId in 0xE0..0xEF) {
                if (videoPid != pid) {
                    videoPid = pid
                    Log.w("TsDemuxer", "Discovered Video Stream PID: 0x${pid.toString(16).uppercase()} ($pid)")
                }
            }
        }

        // Only process the video stream PID
        if (pid != videoPid || videoPid == -1) return

        if (payloadUnitStart) {
            // Flush previously accumulated frame
            flushFrame()

            // Parse PES header:
            // 00 00 01 <stream_id> <pes_length: 2 bytes> <flags: 2 bytes> <pes_header_data_length: 1 byte>
            if (payloadLen >= 9) {
                val flags2 = data[payloadStart + 7].toInt() and 0xFF
                val pesHeaderDataLen = data[payloadStart + 8].toInt() and 0xFF
                val pesHeaderTotal = 9 + pesHeaderDataLen

                // Monotonic local timestamp to prevent PTS jitter and clock drift stalls
                currentPtsUs = System.nanoTime() / 1000

                val esStart = payloadStart + pesHeaderTotal
                if (esStart < packetEnd) {
                    val esLen = packetEnd - esStart
                    frameBuffer.write(data, esStart, esLen)
                }
            }
        } else {
            // Continuation of current frame
            frameBuffer.write(data, payloadStart, payloadLen)
        }
    }

    private fun flushFrame() {
        if (frameBuffer.size() > 0) {
            val bytes = frameBuffer.toByteArray()
            onVideoAccessUnit(bytes, bytes.size, currentPtsUs)
            frameBuffer.reset()
        }
    }
}
