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
}
