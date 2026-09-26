package com.example.pad2display.service

/**
 * Supported Connection Modes for SecondScreen Wireless Display
 */
enum class MirrorConnectionMode(
    val title: String,
    val subtitle: String,
    val badge: String
) {
    INFRASTRUCTURE_MICE(
        title = "Wi-Fi Router Mode (MS-MICE)",
        subtitle = "Connects over local Wi-Fi • Internet active • Zero WPS prompt",
        badge = "RECOMMENDED"
    ),
    DIRECT_P2P(
        title = "Direct P2P Mode (Wi-Fi Direct)",
        subtitle = "Offline direct connection • No router needed • Pure 802.11 Direct",
        badge = "OFFLINE"
    )
}
