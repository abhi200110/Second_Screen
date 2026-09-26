package com.example.pad2display.uibc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wi-Fi Display (WFD) User Input Back Channel (UIBC) Protocol Definitions.
 *
 * Grounded in:
 * - Wi-Fi Display Technical Specification v1.1.0, Section 4.11 (User Input Back Channel)
 * - Section 4.11.1 (UIBC Data Encapsulation)
 * - Microsoft [MS-WFDPE] (Wi-Fi Display Protocol Extension) UIBC extensions
 */

/**
 * UIBC Input Category (4 bits in Common Packet Header).
 */
enum class UibcInputCategory(val value: Int) {
    GENERIC(0),
    HIDC(1)
}

/**
 * UIBC Generic Input Type ID (1 octet in Generic Input message header).
 */
enum class GenericInputType(val value: Int) {
    LEFT_MOUSE_DOWN_TOUCH_DOWN(0),
    LEFT_MOUSE_UP_TOUCH_UP(1),
    MOUSE_MOVE_TOUCH_MOVE(2),
    KEY_DOWN(3),
    KEY_UP(4),
    ZOOM(5),
    VERTICAL_SCROLL(6),
    HORIZONTAL_SCROLL(7),
    ROTATE(8)
}

/**
 * Pointer data for Touch and Mouse events.
 *
 * @param pointerId Tracking ID for the touch contact (0..255)
 * @param x Normalized X coordinate relative to negotiated video stream resolution (0..width-1)
 * @param y Normalized Y coordinate relative to negotiated video stream resolution (0..height-1)
 * @param isStylus True if the input originates from an active stylus/pen
 * @param pressure Normalized pressure from 0.0f to 1.0f (useful for stylus logging)
 */
data class TouchPointer(
    val pointerId: Int,
    val x: Int,
    val y: Int,
    val isStylus: Boolean = false,
    val pressure: Float = 1.0f
)

/**
 * Utility functions for constructing UIBC binary packets.
 */
object UibcProtocol {

    /**
     * Constructs a standard WFD UIBC Generic Touch / Mouse event packet.
     *
     * Packet structure:
     * 1. Common Packet Header (4 octets):
     *    - Octet 0:
     *      - Version: 3 bits (0b000)
     *      - T (timestamp flag): 1 bit (0 = no timestamp, 1 = timestamp present)
     *      - Input Category: 4 bits (0 = Generic)
     *    - Octet 1:
     *      - Reserved: 8 bits (0x00)
     *    - Octets 2-3:
     *      - Length: 16 bits (big-endian unsigned short). Total payload size in octets,
     *        measured from bit 0 to end of body, including 16-bit alignment padding.
     * 2. Optional Timestamp (2 octets, if T=1).
     * 3. Generic Input Message:
     *    - Generic Input Type ID: 1 octet (0=Down, 1=Up, 2=Move)
     *    - Length of Describe: 2 octets (16-bit big-endian unsigned short = 1 + 5 * N)
     *    - Describe:
     *      - Number of Pointers (N): 1 octet
     *      - For each pointer (5 octets):
     *        - Pointer ID: 1 octet
     *        - X coordinate: 2 octets (16-bit big-endian unsigned short)
     *        - Y coordinate: 2 octets (16-bit big-endian unsigned short)
     * 4. Alignment Padding:
     *    - If total length is odd, 1 octet of 0x00 is appended to align to 16-bit boundary.
     *
     * @param type Generic input type (Touch Down, Touch Up, or Touch Move)
     * @param pointers List of active touch pointers (must not be empty, max 255)
     * @param timestamp Optional 16-bit RTP timestamp (if null, T flag is 0)
     * @return Formatted byte array ready for transmission over UIBC TCP socket
     */
    fun buildGenericTouchEvent(
        type: GenericInputType,
        pointers: List<TouchPointer>,
        timestamp: Int? = null
    ): ByteArray {
        require(pointers.isNotEmpty()) { "Pointers list must not be empty" }
        require(pointers.size <= 255) { "Max 255 pointers supported per UIBC packet" }

        val hasTimestamp = timestamp != null
        val headerSize = if (hasTimestamp) 6 else 4

        // Describe field = 1 byte (pointer count N) + 5 bytes per pointer
        val describeLength = 1 + (pointers.size * 5)

        // Generic Input Message = 1 byte (Type ID) + 2 bytes (Describe Length) + describeLength
        val genericMessageLength = 1 + 2 + describeLength

        val unpaddedTotalLength = headerSize + genericMessageLength
        val paddingNeeded = if (unpaddedTotalLength % 2 != 0) 1 else 0
        val totalLength = unpaddedTotalLength + paddingNeeded

        val buffer = ByteBuffer.allocate(totalLength).apply {
            order(ByteOrder.BIG_ENDIAN)

            // Octet 0: Version (3 bits = 0) | T (1 bit) | InputCategory (4 bits = 0)
            val tBit = if (hasTimestamp) 1 else 0
            val octet0 = ((0 and 0x07) shl 5) or ((tBit and 0x01) shl 4) or (UibcInputCategory.GENERIC.value and 0x0F)
            put(octet0.toByte())

            // Octet 1: Reserved (8 bits = 0)
            put(0.toByte())

            // Octets 2-3: Length (16 bits)
            putShort(totalLength.toShort())

            // Optional Timestamp
            if (hasTimestamp) {
                putShort((timestamp!! and 0xFFFF).toShort())
            }

            // Generic Input Message:
            // 1. Generic Input Type ID (1 octet)
            put(type.value.toByte())

            // 2. Length of Describe field (2 octets)
            putShort(describeLength.toShort())

            // 3. Describe field:
            // Number of pointers (1 octet)
            put(pointers.size.toByte())

            // Pointer data (5 octets per pointer)
            for (p in pointers) {
                put((p.pointerId and 0xFF).toByte())
                putShort((p.x and 0xFFFF).toShort())
                putShort((p.y and 0xFFFF).toShort())
            }

            // Alignment padding
            if (paddingNeeded > 0) {
                put(0.toByte())
            }
        }

        return buffer.array()
    }
}
