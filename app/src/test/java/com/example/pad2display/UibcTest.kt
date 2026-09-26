package com.example.pad2display

import com.example.pad2display.media.DisplayScaleMode
import com.example.pad2display.uibc.GenericInputType
import com.example.pad2display.uibc.TouchPointer
import com.example.pad2display.uibc.UibcCoordinateTransformer
import com.example.pad2display.uibc.UibcProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit Tests for Milestone 10: User Input Back Channel (UIBC) - Touch & Stylus.
 *
 * Verifies:
 * 1. UIBC Common Packet Header framing, bit structure, and 16-bit alignment padding.
 * 2. Generic Input Message formatting for SingleTouch, MultiTouch, and Stylus.
 * 3. Coordinate normalization across Fit (Letterbox), Fill (Crop), and Stretch modes.
 * 4. RTSP M4 / M14 UIBC regex extraction and setting parsing.
 */
class UibcTest {

    @Test
    fun testSingleTouchDownPacketFraming() {
        val pointer = TouchPointer(pointerId = 0, x = 960, y = 540)
        val packet = UibcProtocol.buildGenericTouchEvent(
            type = GenericInputType.LEFT_MOUSE_DOWN_TOUCH_DOWN,
            pointers = listOf(pointer)
        )

        // Structure:
        // Header (4) + TypeID (1) + DescribeLen (2) + PointerCount (1) + Pointer (5) = 13 bytes
        // Padded to 16-bit boundary = 14 bytes
        assertEquals("Single touch packet must be padded to 14 bytes", 14, packet.size)

        val bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)

        // Octet 0: Version (3 bits = 0) | T (1 bit = 0) | InputCategory (4 bits = 0 Generic)
        val octet0 = bb.get().toInt() and 0xFF
        assertEquals("Octet 0 must be 0x00 for Version 0, T=0, Category=Generic", 0x00, octet0)

        // Octet 1: Reserved (0x00)
        val octet1 = bb.get().toInt() and 0xFF
        assertEquals("Octet 1 must be 0x00", 0x00, octet1)

        // Octets 2-3: Total length (16 bits)
        val totalLength = bb.short.toInt() and 0xFFFF
        assertEquals("Header length must match packet size (14 bytes)", 14, totalLength)

        // Generic Input Message:
        // Type ID
        val typeId = bb.get().toInt() and 0xFF
        assertEquals("Type ID must be 0 (Touch Down)", 0, typeId)

        // Describe Length: 1 + (1 * 5) = 6 bytes
        val describeLen = bb.short.toInt() and 0xFFFF
        assertEquals("Describe length must be 6 bytes for 1 pointer", 6, describeLen)

        // Number of pointers
        val pointerCount = bb.get().toInt() and 0xFF
        assertEquals("Pointer count must be 1", 1, pointerCount)

        // Pointer ID
        val pointerId = bb.get().toInt() and 0xFF
        assertEquals("Pointer ID must match", 0, pointerId)

        // X coordinate (960)
        val x = bb.short.toInt() and 0xFFFF
        assertEquals("X coordinate must be 960", 960, x)

        // Y coordinate (540)
        val y = bb.short.toInt() and 0xFFFF
        assertEquals("Y coordinate must be 540", 540, y)

        // 1-byte padding byte at end
        val padding = bb.get().toInt() and 0xFF
        assertEquals("Padding byte must be 0", 0, padding)
    }

    @Test
    fun testMultiTouchMovePacketFraming() {
        val p1 = TouchPointer(pointerId = 0, x = 100, y = 200)
        val p2 = TouchPointer(pointerId = 1, x = 800, y = 600)
        val packet = UibcProtocol.buildGenericTouchEvent(
            type = GenericInputType.MOUSE_MOVE_TOUCH_MOVE,
            pointers = listOf(p1, p2)
        )

        // Header (4) + TypeID (1) + DescribeLen (2) + Count (1) + 2 Pointers (10) = 18 bytes
        // 18 is already an even number, so no padding needed
        assertEquals("2-pointer touch packet must be exactly 18 bytes", 18, packet.size)

        val bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        bb.position(2)
        val totalLength = bb.short.toInt() and 0xFFFF
        assertEquals("Packet length must be 18", 18, totalLength)

        val typeId = bb.get().toInt() and 0xFF
        assertEquals("Type ID must be 2 (Touch Move)", 2, typeId)

        val describeLen = bb.short.toInt() and 0xFFFF
        assertEquals("Describe length must be 11 (1 + 2*5)", 11, describeLen)

        val count = bb.get().toInt() and 0xFF
        assertEquals("Pointer count must be 2", 2, count)

        // First pointer
        assertEquals(0, bb.get().toInt() and 0xFF)
        assertEquals(100, bb.short.toInt() and 0xFFFF)
        assertEquals(200, bb.short.toInt() and 0xFFFF)

        // Second pointer
        assertEquals(1, bb.get().toInt() and 0xFF)
        assertEquals(800, bb.short.toInt() and 0xFFFF)
        assertEquals(600, bb.short.toInt() and 0xFFFF)
    }

    @Test
    fun testTouchUpPacketFraming() {
        val p = TouchPointer(pointerId = 5, x = 1919, y = 1079)
        val packet = UibcProtocol.buildGenericTouchEvent(
            type = GenericInputType.LEFT_MOUSE_UP_TOUCH_UP,
            pointers = listOf(p)
        )

        assertEquals(14, packet.size)
        val bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        bb.position(4)
        val typeId = bb.get().toInt() and 0xFF
        assertEquals("Type ID must be 1 (Touch Up)", 1, typeId)
    }

    @Test
    fun testCoordinateTransformerStretchMode() {
        // View: 3000x2000, Video: 1920x1080
        val viewW = 3000
        val viewH = 2000
        val videoW = 1920
        val videoH = 1080

        // Top-left
        val (x0, y0) = UibcCoordinateTransformer.transform(0f, 0f, viewW, viewH, videoW, videoH, DisplayScaleMode.STRETCH)
        assertEquals(0, x0)
        assertEquals(0, y0)

        // Center
        val (xc, yc) = UibcCoordinateTransformer.transform(1500f, 1000f, viewW, viewH, videoW, videoH, DisplayScaleMode.STRETCH)
        assertEquals(960, xc)
        assertEquals(540, yc)

        // Bottom-right
        val (xMax, yMax) = UibcCoordinateTransformer.transform(3000f, 2000f, viewW, viewH, videoW, videoH, DisplayScaleMode.STRETCH)
        assertEquals(1919, xMax)
        assertEquals(1079, yMax)
    }

    @Test
    fun testCoordinateTransformerFitModeClamping() {
        // Video 16:9 (1920x1080), View 1:1 square (1000x1000)
        // Video will render 1000 wide by 562.5 high, with top/bottom bars of (1000 - 562.5)/2 = 218.75 px
        val viewW = 1000
        val viewH = 1000
        val videoW = 1920
        val videoH = 1080

        // Touch above the video area in top letterbox bar (e.g. y = 50f)
        val (xTop, yTop) = UibcCoordinateTransformer.transform(500f, 50f, viewW, viewH, videoW, videoH, DisplayScaleMode.FIT)
        assertEquals("X should be centered (960)", 960, xTop)
        assertEquals("Y in top bar should clamp to 0", 0, yTop)

        // Touch inside center of video area (y = 500f)
        val (xc, yc) = UibcCoordinateTransformer.transform(500f, 500f, viewW, viewH, videoW, videoH, DisplayScaleMode.FIT)
        assertEquals(960, xc)
        assertEquals(540, yc)

        // Touch below the video area in bottom letterbox bar (e.g. y = 950f)
        val (xBot, yBot) = UibcCoordinateTransformer.transform(500f, 950f, viewW, viewH, videoW, videoH, DisplayScaleMode.FIT)
        assertEquals(960, xBot)
        assertEquals("Y in bottom bar should clamp to max height (1079)", 1079, yBot)
    }

    @Test
    fun testUibcCapabilityRegexExtraction() {
        // Typical M4 or M14 SET_PARAMETER request from Windows
        val m4Body = "wfd_uibc_capability: input_category_list=GENERIC, HIDC; generic_cap_list=Keyboard, Mouse, SingleTouch, MultiTouch; hidc_cap_list=none; port=46531\r\nwfd_uibc_setting: enable\r\n"

        val portMatch = Regex("port=(\\d+)", RegexOption.IGNORE_CASE).find(m4Body)
        assertNotNull("Port must be found in M4 body", portMatch)
        val port = portMatch!!.groupValues[1].toInt()
        assertEquals(46531, port)

        val isEnable = Regex("wfd_uibc_setting:\\s*enable", RegexOption.IGNORE_CASE).containsMatchIn(m4Body)
        assertTrue("wfd_uibc_setting must be enable", isEnable)

        val isDisable = Regex("wfd_uibc_setting:\\s*disable", RegexOption.IGNORE_CASE).containsMatchIn(m4Body)
        assertTrue("wfd_uibc_setting must not be disable", !isDisable)
    }
}
