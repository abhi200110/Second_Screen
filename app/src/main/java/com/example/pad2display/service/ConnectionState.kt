package com.example.pad2display.service

/**
 * Explicit Connection State Lifecycle for SecondScreen
 * Grounded in Milestone 9.5 state machine architecture:
 * DISCONNECTED -> DISCOVERING -> CONNECTING -> NEGOTIATING -> STREAMING -> INTERRUPTED -> RECONNECTING
 */
enum class ConnectionState(
    val title: String,
    val description: String,
    val isStreaming: Boolean = false,
    val isConnected: Boolean = false
) {
    DISCONNECTED(
        title = "Disconnected",
        description = "Services offline or stopped"
    ),
    DISCOVERING(
        title = "Discovering",
        description = "Broadcasting beacons & listening for Windows PC"
    ),
    CONNECTING(
        title = "Connecting",
        description = "TCP / MICE connection established with Windows",
        isConnected = true
    ),
    NEGOTIATING(
        title = "Negotiating",
        description = "Exchanging RTSP capabilities (M1-M6)",
        isConnected = true
    ),
    STREAMING(
        title = "Streaming",
        description = "Wireless display projection active",
        isStreaming = true,
        isConnected = true
    ),
    INTERRUPTED(
        title = "Interrupted",
        description = "Stream packet flow stalled or temporary network drop",
        isConnected = false
    ),
    RECONNECTING(
        title = "Reconnecting",
        description = "Recovering session and re-arming listeners"
    )
}
