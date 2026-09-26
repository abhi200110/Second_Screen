package com.example.pad2display

import com.example.pad2display.media.ResolutionPreference
import com.example.pad2display.media.TsDemuxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun testCSeqSanitization() {
        // CRLF injection attack vector
        val maliciousCSeq = "123\r\nInjected-Header: evil\r\n"
        val sanitized = maliciousCSeq.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32).ifEmpty { "1" }
        assertEquals("123Injected-Headerevil", sanitized)
        assertTrue("Sanitized CSeq must not contain newlines", !sanitized.contains("\r") && !sanitized.contains("\n"))

        // Standard CSeq
        val normalCSeq = " 42 \r\n"
        val normalSanitized = normalCSeq.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32).ifEmpty { "1" }
        assertEquals("42", normalSanitized)
    }

    @Test
    fun testContentLengthClamp() {
        val oversizedContentLength = 2_000_000
        val clamped = oversizedContentLength.coerceIn(0, 65536)
        assertEquals(65536, clamped)

        val negativeContentLength = -10
        val clampedNegative = negativeContentLength.coerceIn(0, 65536)
        assertEquals(0, clampedNegative)

        val validContentLength = 512
        val clampedValid = validContentLength.coerceIn(0, 65536)
        assertEquals(512, clampedValid)
    }

    @Test
    fun test3kEdidChecksumAndLength() {
        val edidHex = ResolutionPreference.PAD2_3K_NATIVE.edidPayload
        assertNotNull("PAD2_3K_NATIVE EDID payload must not be null", edidHex)
        // 128 bytes = 256 hex characters
        assertEquals("EDID payload must be exactly 256 hex characters (128 bytes)", 256, edidHex!!.length)

        // Validate EDID 1.4 header: 00 FF FF FF FF FF FF 00
        assertTrue("EDID must start with 00ffffffffffff00", edidHex.startsWith("00ffffffffffff00"))

        // Validate checksum: sum of all 128 bytes modulo 256 must equal 0
        var sum = 0
        for (i in 0 until edidHex.length step 2) {
            val byteVal = edidHex.substring(i, i + 2).toInt(16)
            sum = (sum + byteVal) and 0xFF
        }
        assertEquals("EDID checksum modulo 256 must equal 0", 0, sum)
    }

    @Test
    fun testMpegTsDemuxerSyncDetection() {
        var videoUnitsExtracted = 0
        val demuxer = TsDemuxer { data, length, ptsUs ->
            videoUnitsExtracted++
        }

        // Build a mock 188-byte MPEG-TS packet with sync byte 0x47, PID 0x1011, payloadUnitStart = true
        val tsPacket = ByteArray(188)
        tsPacket[0] = 0x47.toByte() // Sync byte
        tsPacket[1] = 0x50.toByte() // payloadUnitStart=1, PID high=0x10
        tsPacket[2] = 0x11.toByte() // PID low=0x11 (PID=0x1011 = 4113)
        tsPacket[3] = 0x10.toByte() // payload only (adaptation_field_control = 01)

        // Mock PES start code prefix: 00 00 01 E0 (video stream)
        tsPacket[4] = 0x00.toByte()
        tsPacket[5] = 0x00.toByte()
        tsPacket[6] = 0x01.toByte()
        tsPacket[7] = 0xE0.toByte() // Video stream ID
        tsPacket[8] = 0x00.toByte() // PES packet length
        tsPacket[9] = 0x00.toByte()
        tsPacket[10] = 0x80.toByte() // flags 1
        tsPacket[11] = 0x00.toByte() // flags 2 (no PTS)
        tsPacket[12] = 0x00.toByte() // PES header data length = 0

        // Mock Annex-B NAL: 00 00 00 01 67 (SPS)
        tsPacket[13] = 0x00.toByte()
        tsPacket[14] = 0x00.toByte()
        tsPacket[15] = 0x00.toByte()
        tsPacket[16] = 0x01.toByte()
        tsPacket[17] = 0x67.toByte()

        // Feed mock TS packet (with 12-byte RTP dummy prefix)
        val rtpPayload = ByteArray(12 + 188)
        System.arraycopy(tsPacket, 0, rtpPayload, 12, 188)

        demuxer.processRtpPayload(rtpPayload, 12, 188)

        // Now send a second frame to flush the first accumulated frame
        demuxer.processRtpPayload(rtpPayload, 12, 188)

        assertTrue("Demuxer should extract video access units on payload unit start", videoUnitsExtracted >= 1)
    }

    @Test
    fun testConnectionStateLifecycle() {
        val states = com.example.pad2display.service.ConnectionState.entries
        assertEquals(7, states.size)

        // Validate STREAMING state properties
        val streaming = com.example.pad2display.service.ConnectionState.STREAMING
        assertTrue("STREAMING must have isStreaming == true", streaming.isStreaming)
        assertTrue("STREAMING must have isConnected == true", streaming.isConnected)

        // Validate DISCONNECTED state properties
        val disconnected = com.example.pad2display.service.ConnectionState.DISCONNECTED
        assertTrue("DISCONNECTED must have isStreaming == false", !disconnected.isStreaming)
        assertTrue("DISCONNECTED must have isConnected == false", !disconnected.isConnected)

        // Validate INTERRUPTED and RECONNECTING recovery states
        val interrupted = com.example.pad2display.service.ConnectionState.INTERRUPTED
        assertTrue("INTERRUPTED must have isStreaming == false", !interrupted.isStreaming)
        assertTrue("INTERRUPTED must have isConnected == false", !interrupted.isConnected)

        val reconnecting = com.example.pad2display.service.ConnectionState.RECONNECTING
        assertEquals("Reconnecting", reconnecting.title)
    }

    @Test
    fun testMirrorConnectionModes() {
        val modes = com.example.pad2display.service.MirrorConnectionMode.entries
        assertEquals(2, modes.size)

        val mice = com.example.pad2display.service.MirrorConnectionMode.INFRASTRUCTURE_MICE
        assertTrue("MICE mode title must contain MS-MICE", mice.title.contains("MS-MICE"))
        assertEquals("RECOMMENDED", mice.badge)

        val p2p = com.example.pad2display.service.MirrorConnectionMode.DIRECT_P2P
        assertTrue("P2P mode title must contain Wi-Fi Direct", p2p.title.contains("Wi-Fi Direct"))
        assertEquals("OFFLINE", p2p.badge)
    }

    @Test
    fun testWfdIdrRequestFormatting() {
        val url = "rtsp://192.168.0.198/wfd1.0/streamid=0"
        val cseq = 42
        val sessionId = "987654321"
        val body = "wfd_idr_request\r\n"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)

        val req = buildString {
            append("SET_PARAMETER $url RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            append("Session: $sessionId\r\n")
            append("Content-Type: text/parameters\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("\r\n")
            append(body)
        }

        assertTrue("Request must start with SET_PARAMETER", req.startsWith("SET_PARAMETER $url RTSP/1.0\r\n"))
        assertTrue("Request must contain CSeq", req.contains("CSeq: 42\r\n"))
        assertTrue("Request must contain Session ID", req.contains("Session: 987654321\r\n"))
        assertTrue("Request must contain Content-Type text/parameters", req.contains("Content-Type: text/parameters\r\n"))
        assertTrue("Request must contain Content-Length: 17", req.contains("Content-Length: 17\r\n"))
        assertTrue("Request must contain wfd_idr_request body", req.endsWith("\r\n\r\nwfd_idr_request\r\n"))
    }

    @Test
    fun testM16KeepAliveResponseWithSession() {
        val cseq = "10"
        val sessionId = "1707142695"
        val response = buildString {
            append("RTSP/1.0 200 OK\r\n")
            append("CSeq: $cseq\r\n")
            if (sessionId.isNotEmpty()) {
                append("Session: $sessionId\r\n")
            }
            append("\r\n")
        }

        assertTrue("Response must start with RTSP/1.0 200 OK", response.startsWith("RTSP/1.0 200 OK\r\n"))
        assertTrue("Response must include CSeq", response.contains("CSeq: 10\r\n"))
        assertTrue("Response must include Session header for active sessions", response.contains("Session: 1707142695\r\n"))
    }

    @Test
    fun testNalUnitExtractionAndNormalization() {
        // Construct Annex-B stream with 4-byte start codes:
        // [00 00 00 01 67 (SPS)] [00 00 00 01 68 (PPS)] [00 00 00 01 65 (IDR)]
        val spsPayload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67.toByte(), 0x42, 0x00, 0x1F)
        val ppsPayload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68.toByte(), 0xCE.toByte(), 0x38, 0x80.toByte())
        val idrPayload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65.toByte(), 0x88.toByte(), 0x84.toByte())

        val stream = ByteArray(spsPayload.size + ppsPayload.size + idrPayload.size)
        System.arraycopy(spsPayload, 0, stream, 0, spsPayload.size)
        System.arraycopy(ppsPayload, 0, stream, spsPayload.size, ppsPayload.size)
        System.arraycopy(idrPayload, 0, stream, spsPayload.size + ppsPayload.size, idrPayload.size)

        // Validate that NAL types 7, 8, 5 are detected
        val nalTypes = mutableListOf<Int>()
        var i = 0
        while (i <= stream.size - 4) {
            if (stream[i] == 0.toByte() && stream[i + 1] == 0.toByte()) {
                val startCodeLen = if (stream[i + 2] == 1.toByte()) 3
                else if (stream[i + 2] == 0.toByte() && i <= stream.size - 5 && stream[i + 3] == 1.toByte()) 4
                else 0

                if (startCodeLen > 0) {
                    val nalType = stream[i + startCodeLen].toInt() and 0x1F
                    nalTypes.add(nalType)
                    i += startCodeLen
                    continue
                }
            }
            i++
        }

        assertEquals(3, nalTypes.size)
        assertEquals(7, nalTypes[0]) // SPS
        assertEquals(8, nalTypes[1]) // PPS
        assertEquals(5, nalTypes[2]) // IDR
    }
}

