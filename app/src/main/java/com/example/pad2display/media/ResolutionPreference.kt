package com.example.pad2display.media

/**
 * Resolution preferences for Miracast / Wi-Fi Display (WFD) Sink.
 *
 * Grounded in Wi-Fi Display Specification v1.1.0, AOSP VideoFormats.cpp, and MS-WFDPE.
 *
 * Format string layout:
 * %02x 00 %02x %02x %08x %08x %08x 00 0000 0000 00 none none
 * - Byte 0: (nativeIndex << 3) | nativeType
 *     nativeType: 0 = CEA, 1 = VESA, 2 = HH
 * - Byte 1: 00 (Reserved)
 * - Byte 2: H.264 Profile (01 = CBP, 02 = CHP)
 * - Byte 3: H.264 Level (01 = Level 3.1, 02 = Level 3.2, 07 = Level 5.2 for 3K/4K)
 * - Field 4: CEA 32-bit resolution mask
 * - Field 5: VESA 32-bit resolution mask
 * - Field 6: HH 32-bit resolution mask
 */
enum class ResolutionPreference(
    val title: String,
    val subtitle: String,
    val badge: String,
    val wfdVideoFormats: String,
    val nominalWidth: Int,
    val nominalHeight: Int,
    val edidPayload: String? = null
) {
    PAD2_3K_NATIVE(
        title = "3K 60fps (3000x2120 Native 7:5)",
        subtitle = "OnePlus Pad 2 7:5 panel • Advertises 3K EDID & H.264 Level 5.2 to Windows",
        badge = "3K NATIVE",
        // Native: VESA Index 29 (1920x1200 / custom) -> 0xe9. CHP (02), Level 5.2 (07) for 3K/4K capability
        wfdVideoFormats = "wfd_video_formats: e9 00 02 07 00000180 30000000 00000000 00 0000 0000 00 none none\r\n",
        nominalWidth = 3000,
        nominalHeight = 2120,
        // 128-byte EDID block (256 hex chars) with 3000x2120 @ 60Hz Detailed Timing Descriptor (DTD)
        edidPayload = "00ffffffffffff003e1801000100000026220104a51a12780aee91a3544c99260f5054210800d15cd1dc0000000000000000000000009a9fb8a0b04823803020350004b81000001e000000fc004f6e65506c7573205061642032000000fd0018901e963200000000000000000000001000000000000000000000000000000058"
    ),

    FHD_1080P(
        title = "1080p 60fps (Full HD)",
        subtitle = "1920x1080 • Standard 16:9 • Sharp, fast & reliable",
        badge = "RECOMMENDED",
        // Native: CEA Index 8 (1920x1080 p60) -> 0x40. CEA: 1080p60 + 1080p30 (0x180). VESA: 0. HH: 0.
        wfdVideoFormats = "wfd_video_formats: 40 00 02 02 00000180 00000000 00000000 00 0000 0000 00 none none\r\n",
        nominalWidth = 1920,
        nominalHeight = 1080
    ),

    WUXGA_1200P(
        title = "1200p 60fps (16:10 WUXGA)",
        subtitle = "1920x1200 • 16:10 ratio • Reduced letterboxing on 7:5 tablet screen",
        badge = "TABLET 16:10",
        // Native: VESA Index 29 (1920x1200 p60) -> (29 << 3) | 1 = 0xe9. VESA: bit 29 + 28 (0x30000000). CEA: 1080p fallback.
        wfdVideoFormats = "wfd_video_formats: e9 00 02 02 00000180 30000000 00000000 00 0000 0000 00 none none\r\n",
        nominalWidth = 1920,
        nominalHeight = 1200
    ),

    HD_720P(
        title = "720p 60fps (HD Low Latency)",
        subtitle = "1280x720 • Lower bandwidth • Optimal for weak Wi-Fi / fast gaming",
        badge = "LOW LATENCY",
        // Native: CEA Index 6 (1280x720 p60) -> (6 << 3) | 0 = 0x30. CEA: 720p60 + 720p30 (0x060). VESA: 0.
        wfdVideoFormats = "wfd_video_formats: 30 00 02 02 00000060 00000000 00000000 00 0000 0000 00 none none\r\n",
        nominalWidth = 1280,
        nominalHeight = 720
    ),

    AUTO_MULTI(
        title = "Auto / Multi-Resolution",
        subtitle = "Advertises 1080p, 1200p, 720p • Selectable in Windows Display Settings",
        badge = "FLEXIBLE",
        // Native: CEA Index 8 (1920x1080 p60) -> 0x40. CEA: 1080p, 720p, 480p. VESA: 1200p.
        wfdVideoFormats = "wfd_video_formats: 40 00 02 02 000001ef 30000000 00000000 00 0000 0000 00 none none\r\n",
        nominalWidth = 1920,
        nominalHeight = 1080
    )
}

/**
 * Display scaling mode for the fullscreen wireless player.
 */
enum class DisplayScaleMode(
    val title: String,
    val description: String
) {
    FIT(
        title = "Fit (Letterbox)",
        description = "Preserves exact aspect ratio with black bars"
    ),
    FILL_CROP(
        title = "Fill (Zero Bars)",
        description = "Zooms to fill OnePlus Pad 2 7:5 screen without distortion"
    ),
    STRETCH(
        title = "Stretch",
        description = "Stretches picture to fill entire screen"
    )
}

data class VideoFormatInfo(
    val width: Int,
    val height: Int
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 16f / 9f

    val ratioLabel: String
        get() = when {
            aspectRatio > 1.7f -> "16:9"
            aspectRatio > 1.55f -> "16:10"
            aspectRatio in 1.38f..1.45f -> "7:5 (3K)"
            aspectRatio in 1.48f..1.55f -> "3:2"
            aspectRatio > 1.3f -> "4:3"
            else -> "Custom"
        }

    val displayString: String
        get() = "${width}x${height} ($ratioLabel)"
}
