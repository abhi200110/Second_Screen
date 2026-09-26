package com.example.pad2display.uibc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "UibcManager"

/**
 * Manages the User Input Back Channel (UIBC) TCP transport session.
 *
 * Implements low-latency transmission of touch and stylus inputs to the
 * Wi-Fi Display Source (Windows PC).
 *
 * Key Design Principles:
 * 1. Immediate dispatch with Nagle's algorithm disabled (TCP_NODELAY = true).
 * 2. Asynchronous decoupling via non-blocking Channel with DROP_OLDEST backpressure
 *    to prevent touch latency accumulation during rapid dragging.
 * 3. Thread-safe event ingestion from the Android UI thread without blocking rendering.
 */
class UibcManager(
    private val onLog: (String) -> Unit = {}
) {
    private val managerScope = CoroutineScope(Dispatchers.IO + Job())
    private var connectionJob: Job? = null
    private var activeSocket: Socket? = null

    // Event queue for outgoing binary UIBC frames
    private val eventChannel = Channel<ByteArray>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _isUibcNegotiated = MutableStateFlow(false)
    val isUibcNegotiated: StateFlow<Boolean> = _isUibcNegotiated.asStateFlow()

    private val _isUibcConnected = MutableStateFlow(false)
    val isUibcConnected: StateFlow<Boolean> = _isUibcConnected.asStateFlow()

    private val _isTouchEnabled = MutableStateFlow(true)
    val isTouchEnabled: StateFlow<Boolean> = _isTouchEnabled.asStateFlow()

    private val _packetsSent = MutableStateFlow(0L)
    val packetsSent: StateFlow<Long> = _packetsSent.asStateFlow()

    private val _lastEventInfo = MutableStateFlow("Idle")
    val lastEventInfo: StateFlow<String> = _lastEventInfo.asStateFlow()

    private val packetCounter = AtomicLong(0)

    /**
     * Toggles whether touchscreen input is dispatched over UIBC.
     */
    fun setTouchEnabled(enabled: Boolean) {
        _isTouchEnabled.value = enabled
        log("Touch input ${if (enabled) "ENABLED" else "DISABLED"} by user")
    }

    /**
     * Called when WFD Source negotiates UIBC in M4 / M14 SET_PARAMETER.
     */
    fun onNegotiated(remoteIp: String, port: Int) {
        log("UIBC negotiated by Source: $remoteIp:$port")
        _isUibcNegotiated.value = true
        connect(remoteIp, port)
    }

    /**
     * Called when WFD Source disables UIBC (wfd_uibc_setting: disable) or RTSP tears down.
     */
    fun onDisabled() {
        log("UIBC disabled or session ended")
        _isUibcNegotiated.value = false
        disconnect()
    }

    /**
     * Connects to the Source's UIBC TCP port.
     */
    fun connect(remoteIp: String, port: Int) {
        disconnect()

        connectionJob = managerScope.launch {
            log("Attempting UIBC TCP connection to $remoteIp:$port...")
            try {
                val socket = Socket().apply {
                    // Disable Nagle's algorithm for minimum input latency per MS-WFDPE & WFD Spec
                    tcpNoDelay = true
                    soTimeout = 15000
                    connect(InetSocketAddress(remoteIp, port), 5000)
                }
                activeSocket = socket
                _isUibcConnected.value = true
                log("UIBC TCP channel successfully CONNECTED to $remoteIp:$port (TCP_NODELAY=true)")

                val outStream: OutputStream = socket.getOutputStream()

                while (isActive && socket.isConnected && !socket.isClosed) {
                    val packet = eventChannel.receive()
                    try {
                        outStream.write(packet)
                        outStream.flush()
                        val count = packetCounter.incrementAndGet()
                        _packetsSent.value = count
                    } catch (e: Exception) {
                        log("Error transmitting UIBC packet: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                log("UIBC connection to $remoteIp:$port failed: ${e.message}")
            } finally {
                _isUibcConnected.value = false
                try {
                    activeSocket?.close()
                } catch (_: Exception) {}
                activeSocket = null
                log("UIBC TCP channel closed")
            }
        }
    }

    /**
     * Enqueues a touch or stylus event for transmission to the Windows PC.
     */
    fun sendTouchEvent(type: GenericInputType, pointers: List<TouchPointer>) {
        if (!_isTouchEnabled.value || !_isUibcConnected.value) {
            return
        }

        try {
            val packet = UibcProtocol.buildGenericTouchEvent(type, pointers)
            val success = eventChannel.trySend(packet).isSuccess
            if (success) {
                val primary = pointers.firstOrNull()
                val toolType = if (primary?.isStylus == true) "Stylus" else "Touch"
                _lastEventInfo.value = "${type.name.substringAfterLast("_")} $toolType P=${pointers.size} (${primary?.x}, ${primary?.y})"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build UIBC packet: ${e.message}", e)
        }
    }

    /**
     * Disconnects the UIBC TCP session.
     */
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null
        _isUibcConnected.value = false
    }

    private fun log(message: String) {
        Log.w(TAG, message)
        onLog("[UIBC] $message")
    }
}
