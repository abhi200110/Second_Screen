package com.example.pad2display.mice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val TAG = "MiceServer"

// [MS-MICE] Command Codes (Section 2.2)
const val MICE_CMD_SOURCE_READY = 0x01
const val MICE_CMD_STOP_PROJECTION = 0x02
const val MICE_CMD_SECURITY_HANDSHAKE = 0x03
const val MICE_CMD_SESSION_REQUEST = 0x04
const val MICE_CMD_PIN_CHALLENGE = 0x05
const val MICE_CMD_PIN_RESPONSE = 0x06

// [MS-MICE] TLV Types (Section 2.2.7)
const val TLV_FRIENDLY_NAME = 0x00
const val TLV_RTSP_PORT = 0x02
const val TLV_SOURCE_ID = 0x03
const val TLV_SECURITY_TOKEN = 0x04
const val TLV_SECURITY_OPTIONS = 0x05

data class MiceServerState(
    val isRunning: Boolean = false,
    val boundPort: Int = MICE_TCP_PORT,
    val connectedClientCount: Int = 0,
    val lastSourceIp: String? = null,
    val lastRtspPort: Int? = null,
    val lastFriendlyName: String? = null,
    val lastMessage: String = "None"
)

/**
 * TCP Port 7250 Server implementing [MS-MICE] control channel.
 */
class MiceServer(
    private val port: Int = MICE_TCP_PORT,
    private val onSourceReady: (sourceIp: String, rtspPort: Int, friendlyName: String) -> Unit,
    private val onStopProjection: () -> Unit = {},
    private val onLog: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val _serverState = MutableStateFlow(MiceServerState(boundPort = port))
    val serverState: StateFlow<MiceServerState> = _serverState.asStateFlow()

    @Synchronized
    fun start() {
        if (_serverState.value.isRunning) {
            return
        }

        serverJob = scope.launch {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss

                _serverState.value = _serverState.value.copy(
                    isRunning = true,
                    boundPort = port
                )
                log("MS-MICE Server listening on 0.0.0.0:$port")

                while (isActive && !ss.isClosed) {
                    val clientSocket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    log("Accepted MS-MICE TCP connection from ${clientSocket.remoteSocketAddress}")
                    _serverState.value = _serverState.value.copy(
                        connectedClientCount = _serverState.value.connectedClientCount + 1,
                        lastSourceIp = clientSocket.inetAddress.hostAddress
                    )
                    launch { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    log("MICE Server error: ${e.message}")
                }
            } finally {
                try {
                    ss?.close()
                } catch (_: Exception) {}
                serverSocket = null
                _serverState.value = _serverState.value.copy(
                    isRunning = false,
                    connectedClientCount = 0
                )
                log("MS-MICE Server stopped")
            }
        }
    }

    @Synchronized
    fun stop() {
        val ss = serverSocket
        serverSocket = null
        try {
            ss?.close()
        } catch (_: Exception) {}
        serverJob?.cancel()
        serverJob = null
        _serverState.value = _serverState.value.copy(
            isRunning = false,
            connectedClientCount = 0
        )
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
        val clientPort = socket.port
        log("Handling MICE client: $clientIp:$clientPort")

        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15000 // 15-second read timeout to prevent hung connection starvation
            val inputStream = DataInputStream(socket.getInputStream())
            val outputStream = DataOutputStream(socket.getOutputStream())

            while (isActive && socket.isConnected && !socket.isClosed) {
                val size = inputStream.readUnsignedShort()
                val version = inputStream.readUnsignedByte()
                val command = inputStream.readUnsignedByte()

                log("Received MICE message: size=$size, version=0x${version.toString(16)}, command=0x${command.toString(16)}")

                if (size !in 4..8192) {
                    log("Invalid or oversized MICE message size: $size (allowed 4..8192)")
                    break
                }

                val payloadLen = size - 4
                val payloadBytes = ByteArray(payloadLen)
                inputStream.readFully(payloadBytes)

                val buffer = ByteBuffer.wrap(payloadBytes).order(ByteOrder.BIG_ENDIAN)

                when (command) {
                    MICE_CMD_SOURCE_READY -> {
                        var friendlyName = "Windows Source"
                        var rtspPort = 7236
                        var sourceId: String? = null

                        while (buffer.hasRemaining()) {
                            if (buffer.remaining() < 3) break
                            val tlvType = buffer.get().toInt() and 0xFF
                            val tlvLen = buffer.short.toInt() and 0xFFFF
                            if (buffer.remaining() < tlvLen) break

                            val tlvVal = ByteArray(tlvLen)
                            buffer.get(tlvVal)

                            when (tlvType) {
                                TLV_FRIENDLY_NAME -> {
                                    friendlyName = try {
                                        String(tlvVal, StandardCharsets.UTF_16LE).trimEnd('\u0000')
                                    } catch (_: Exception) {
                                        String(tlvVal, StandardCharsets.UTF_8).trimEnd('\u0000')
                                    }
                                }
                                TLV_RTSP_PORT -> {
                                    if (tlvLen >= 2) {
                                        val portBuf = ByteBuffer.wrap(tlvVal).order(ByteOrder.BIG_ENDIAN)
                                        rtspPort = portBuf.short.toInt() and 0xFFFF
                                    }
                                }
                                TLV_SOURCE_ID -> {
                                    if (tlvLen >= 16) {
                                        val idBuf = ByteBuffer.wrap(tlvVal).order(ByteOrder.BIG_ENDIAN)
                                        sourceId = UUID(idBuf.long, idBuf.long).toString()
                                    }
                                }
                            }
                        }

                        log("Parsed SOURCE_READY: Source='$friendlyName', RTSP Port=$rtspPort, ID=$sourceId, IP=$clientIp")
                        _serverState.value = _serverState.value.copy(
                            lastFriendlyName = friendlyName,
                            lastRtspPort = rtspPort,
                            lastMessage = "SOURCE_READY from $friendlyName ($clientIp:$rtspPort)"
                        )

                        onSourceReady(clientIp, rtspPort, friendlyName)
                    }

                    MICE_CMD_STOP_PROJECTION -> {
                        log("Parsed STOP_PROJECTION from $clientIp")
                        _serverState.value = _serverState.value.copy(
                            lastMessage = "STOP_PROJECTION from $clientIp"
                        )
                        onStopProjection()
                    }

                    else -> {
                        log("Unhandled MICE command: 0x${command.toString(16)} (size=$size)")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is java.io.EOFException || e is java.net.SocketException) {
                log("Client disconnected: $clientIp")
            } else {
                log("MICE client exception ($clientIp): ${e.message}")
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
            _serverState.value = _serverState.value.copy(
                connectedClientCount = maxOf(0, _serverState.value.connectedClientCount - 1)
            )
            log("MICE connection closed: $clientIp")
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        println("[MS-MICE Server] $msg")
        onLog("[MICE Server] $msg")
    }
}
