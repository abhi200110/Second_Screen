package com.example.pad2display.rtsp

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.example.pad2display.media.DEFAULT_RTP_PORT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "RtspEngine"
const val DEFAULT_RTSP_PORT = 7236

data class RtspEngineState(
    val isRunning: Boolean = false,
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val mode: String = "IDLE", // "SERVER" or "CLIENT"
    val boundPort: Int = DEFAULT_RTSP_PORT,
    val remoteAddress: String = "None",
    val sessionId: String = "None",
    val presentationUrl: String = "None",
    val lastReceivedMessage: String = "None"
)

class RtspServer(
    private val context: Context? = null,
    private val defaultPort: Int = DEFAULT_RTSP_PORT,
    private val rtpPort: Int = DEFAULT_RTP_PORT,
    private val onStreamStarted: (remoteIp: String, rtpPort: Int) -> Unit = { _, _ -> },
    private val onStreamStopped: () -> Unit = {},
    private val onLog: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var activeSocket: Socket? = null

    private val _engineState = MutableStateFlow(RtspEngineState(boundPort = defaultPort))
    val serverState: StateFlow<RtspEngineState> = _engineState.asStateFlow()

    var resolutionPreference: com.example.pad2display.media.ResolutionPreference =
        com.example.pad2display.media.ResolutionPreference.FHD_1080P

    private val sinkCSeq = AtomicInteger(1)
    private var currentPresentationUrl = ""
    private var currentSessionId = ""
    @Volatile
    private var activeWriter: OutputStreamWriter? = null
    @Volatile
    private var activeRemoteIp: String = "127.0.0.1"
    private var keepAliveJob: Job? = null

    /**
     * UIBC callbacks triggered when Source enables or disables the User Input Back Channel.
     */
    var onUibcNegotiated: ((remoteIp: String, port: Int) -> Unit)? = null
    var onUibcDisabled: (() -> Unit)? = null

    /**
     * Request an immediate IDR Keyframe from Windows Source (wfd_idr_request).
     * Commands Windows NVENC/QuickSync/AMF hardware encoder to generate a fresh I-frame
     * with parameter sets (SPS/PPS) to instantly recover video decoding without delay.
     */
    fun requestIdrFrame() {
        val writer = activeWriter ?: return
        val url = resolveStreamUrl(activeRemoteIp)
        val cseq = sinkCSeq.getAndIncrement()
        val body = "wfd_idr_request\r\n"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val req = buildString {
            append("SET_PARAMETER $url RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            if (currentSessionId.isNotEmpty()) {
                append("Session: $currentSessionId\r\n")
            }
            append("Content-Type: text/parameters\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("\r\n")
            append(body)
        }
        scope.launch {
            try {
                synchronized(writer) {
                    writer.write(req)
                    writer.flush()
                }
                log(">>> Dispatched IDR Keyframe Request (wfd_idr_request) to Windows Source (CSeq: $cseq)")
            } catch (e: Exception) {
                log("Failed to send IDR request: ${e.message}")
            }
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && _engineState.value.isStreaming) {
                delay(10000)
                val writer = activeWriter ?: break
                val url = resolveStreamUrl(activeRemoteIp)
                val cseq = sinkCSeq.getAndIncrement()
                val req = buildString {
                    append("GET_PARAMETER $url RTSP/1.0\r\n")
                    append("CSeq: $cseq\r\n")
                    if (currentSessionId.isNotEmpty()) {
                        append("Session: $currentSessionId\r\n")
                    }
                    append("\r\n")
                }
                try {
                    synchronized(writer) {
                        writer.write(req)
                        writer.flush()
                    }
                    log(">>> Dispatched M16 Keep-Alive probe to Windows Source (CSeq: $cseq)")
                } catch (e: Exception) {
                    log("Keep-alive dispatch error: ${e.message}")
                }
            }
        }
    }

    /**
     * Start RTSP Server for Mode 2 (Wi-Fi Direct P2P) or Mode 1 (MS-MICE):
     * Listens on 0.0.0.0:port waiting for Windows Source to connect in.
     */
    @Synchronized
    fun start(port: Int = defaultPort) {
        if (serverSocket != null && serverSocket?.isBound == true && serverSocket?.isClosed == false) {
            log("RTSP Server already listening on 0.0.0.0:${serverSocket?.localPort}")
            return
        }

        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        serverJob = scope.launch {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss

                _engineState.value = _engineState.value.copy(
                    isRunning = true,
                    mode = "SERVER",
                    boundPort = port
                )
                log("RTSP Server listening on 0.0.0.0:$port (Waiting for Windows Source)")

                while (isActive && !ss.isClosed) {
                    val clientSocket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    log("Accepted RTSP connection from ${clientSocket.remoteSocketAddress}")
                    activeSocket = clientSocket
                    _engineState.value = _engineState.value.copy(
                        isConnected = true,
                        remoteAddress = clientSocket.remoteSocketAddress.toString()
                    )
                    handleSession(clientSocket, isClient = false)
                }
            } catch (e: Exception) {
                if (isActive) {
                    log("RTSP Server error: ${e.message}")
                }
            } finally {
                try {
                    ss?.close()
                } catch (_: Exception) {}
                serverSocket = null
                _engineState.value = _engineState.value.copy(
                    isRunning = false,
                    isConnected = false,
                    isStreaming = false
                )
                log("RTSP Server loop ended")
            }
        }
    }

    /**
     * Connect RTSP Client to Windows Source:
     * Connects out to Windows Source on sourceIp:sourceRtspPort.
     * Retries for up to 45 seconds to accommodate Wi-Fi Direct L2 and DHCP lease negotiation.
     * Automatically binds socket to the local P2P interface IP to enforce correct kernel routing.
     */
    @Synchronized
    fun connectAsClient(sourceIp: String, sourceRtspPort: Int, maxRetries: Int = 45, retryDelayMs: Long = 1000) {
        if (_engineState.value.isConnected && _engineState.value.remoteAddress == "$sourceIp:$sourceRtspPort" && activeSocket?.isConnected == true && activeSocket?.isClosed == false) {
            log("RTSP Client already connected to $sourceIp:$sourceRtspPort")
            return
        }

        clientJob?.cancel()
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null

        clientJob = scope.launch {
            _engineState.value = _engineState.value.copy(
                isRunning = true,
                mode = "CLIENT",
                remoteAddress = "$sourceIp:$sourceRtspPort"
            )
            log("Connecting RTSP Client to Windows Source at $sourceIp:$sourceRtspPort...")

            var socket: Socket? = null
            var connected = false

            for (attempt in 1..maxRetries) {
                if (!isActive) break
                val s = Socket()
                try {
                    s.tcpNoDelay = true
                    s.reuseAddress = true

                    // 1. Enforce local P2P interface routing:
                    // Find the local IPv4 address matching the destination subnet or on the p2p interface
                    val localP2pAddr = findLocalAddressForDestination(sourceIp)
                    if (localP2pAddr != null) {
                        try {
                            s.bind(InetSocketAddress(localP2pAddr, 0))
                            log("Bound RTSP client socket to local P2P address ${localP2pAddr.hostAddress}")
                        } catch (e: Exception) {
                            log("Notice: Local P2P address bind: ${e.message}")
                        }
                    } else {
                        log("Waiting for P2P DHCP IP lease on p2p interface (attempt $attempt/$maxRetries)...")
                    }

                    // 2. Also bind to P2P Network via ConnectivityManager if available
                    context?.let { ctx ->
                        try {
                            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                            cm?.allNetworks?.forEach { net ->
                                val lp = cm.getLinkProperties(net)
                                if (lp?.interfaceName?.contains("p2p") == true) {
                                    net.bindSocket(s)
                                    log("Bound RTSP client socket to P2P network (${lp.interfaceName})")
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    // 3. Connect with 2000ms timeout
                    s.connect(InetSocketAddress(sourceIp, sourceRtspPort), 2000)
                    socket = s
                    activeSocket = s
                    connected = true
                    log(">>> RTSP Client CONNECTED to Windows Source at $sourceIp:$sourceRtspPort on attempt $attempt!")
                    break
                } catch (e: Exception) {
                    try {
                        s.close()
                    } catch (_: Exception) {}
                    if (attempt % 5 == 0 || attempt == 1) {
                        log("Connect attempt $attempt/$maxRetries to $sourceIp:$sourceRtspPort: ${e.message}")
                    }
                    if (attempt < maxRetries) {
                        delay(retryDelayMs)
                    }
                }
            }

            if (!connected || socket == null) {
                log("Failed to connect RTSP Client to $sourceIp:$sourceRtspPort after $maxRetries attempts")
                _engineState.value = _engineState.value.copy(
                    isConnected = false,
                    isStreaming = false
                )
                return@launch
            }

            _engineState.value = _engineState.value.copy(isConnected = true)
            handleSession(socket, isClient = true)
        }
    }

    /**
     * Locate the local IPv4 address that belongs to the same subnet as destIp or to a p2p interface.
     */
    private fun findLocalAddressForDestination(destIp: String): InetAddress? {
        try {
            val destParts = destIp.split(".").mapNotNull { it.toIntOrNull() }
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()

            // 1. Check for exact /24 subnet match (first 3 octets match destIp, e.g. 192.168.137.x)
            for (iface in ifaces) {
                for (ifAddr in iface.interfaceAddresses) {
                    val addr = ifAddr.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val localParts = addr.hostAddress?.split(".")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
                        if (localParts.size >= 3 && destParts.size >= 3 &&
                            localParts[0] == destParts[0] &&
                            localParts[1] == destParts[1] &&
                            localParts[2] == destParts[2]
                        ) {
                            return addr
                        }
                    }
                }
            }

            // 2. Check for any interface whose name contains "p2p"
            for (iface in ifaces) {
                if (iface.name.contains("p2p", ignoreCase = true)) {
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    @Synchronized
    fun stop() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        activeWriter = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null

        serverJob?.cancel()
        serverJob = null
        clientJob?.cancel()
        clientJob = null

        _engineState.value = _engineState.value.copy(
            isRunning = false,
            isConnected = false,
            isStreaming = false,
            mode = "IDLE",
            sessionId = "None",
            presentationUrl = "None"
        )
        onStreamStopped()
        log("RTSP Engine stopped")
    }

    private suspend fun handleSession(socket: Socket, isClient: Boolean) = withContext(Dispatchers.IO) {
        val remoteAddr = socket.remoteSocketAddress.toString()
        val remoteIp = socket.inetAddress.hostAddress ?: "127.0.0.1"

        var receivedAnyMessage = false
        var fallbackM1Job: Job? = null

        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30000 // 30-second timeout for keep-alive/inactivity detection
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            activeWriter = writer
            activeRemoteIp = remoteIp

            // When connecting to Windows Source, the Source normally initiates M1 (OPTIONS) immediately.
            // If Source does not send M1 within 1.5s, proactively send M1 so neither side stalls.
            if (isClient) {
                fallbackM1Job = scope.launch {
                    delay(1500)
                    if (!receivedAnyMessage && isActive && socket.isConnected && !socket.isClosed) {
                        log("Waiting for Source M1 timed out (1.5s); proactively sending M1 (OPTIONS)...")
                        val cseq = sinkCSeq.getAndIncrement()
                        val m1 = "OPTIONS * RTSP/1.0\r\nCSeq: $cseq\r\nRequire: org.wfa.wfd1.0\r\n\r\n"
                        try {
                            synchronized(writer) {
                                writer.write(m1)
                                writer.flush()
                            }
                            log("Sent proactive M1 (OPTIONS) to Source:\n$m1")
                        } catch (_: Exception) {}
                    }
                }
            }

            while (isActive && socket.isConnected && !socket.isClosed) {
                val lines = mutableListOf<String>()
                var line: String?

                // Read RTSP header lines
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.isEmpty()) break
                    lines.add(line!!)
                }

                if (lines.isEmpty()) {
                    break
                }

                receivedAnyMessage = true
                fallbackM1Job?.cancel()

                val headerText = lines.joinToString("\n")
                log("Received from $remoteAddr:\n$headerText")

                // Check for Content-Length with upper-bound clamp (max 64 KB) to prevent OOM DoS
                val rawContentLength = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                val contentLength = rawContentLength.coerceIn(0, 65536)
                if (rawContentLength > 65536) {
                    log("Warning: Content-Length $rawContentLength exceeded max allowed 65536 bytes; clamped.")
                }

                val body = if (contentLength > 0) {
                    val bodyChars = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val count = reader.read(bodyChars, readTotal, contentLength - readTotal)
                        if (count < 0) break
                        readTotal += count
                    }
                    String(bodyChars, 0, readTotal)
                } else ""

                if (body.isNotEmpty()) {
                    log("Received Body ($contentLength bytes):\n$body")
                }

                val firstLine = lines.firstOrNull() ?: continue
                _engineState.value = _engineState.value.copy(
                    lastReceivedMessage = firstLine
                )

                // Dispatch handling
                if (firstLine.startsWith("RTSP/1.0 200 OK", ignoreCase = true)) {
                    // Response to our request (e.g. M1 response, M6 SETUP response, M7 PLAY response)
                    handleRtspResponse(lines, writer, remoteIp)
                } else {
                    // Incoming Request from Source (e.g. M1, M3, M4, M5, M16 Keep-Alive, TEARDOWN)
                    val response = handleRtspRequest(lines, body, writer, remoteIp)
                    if (response != null) {
                        synchronized(writer) {
                            writer.write(response)
                            writer.flush()
                        }
                        log("Sent response to $remoteAddr:\n$response")
                    }
                }
            }
        } catch (e: Exception) {
            log("Session with $remoteAddr error: ${e.message}")
        } finally {
            fallbackM1Job?.cancel()
            keepAliveJob?.cancel()
            keepAliveJob = null
            activeWriter = null
            try {
                socket.close()
            } catch (_: Exception) {}
            _engineState.value = _engineState.value.copy(
                isConnected = false,
                isStreaming = false
            )
            onStreamStopped()
            log("Session disconnected: $remoteAddr")
        }
    }

    private suspend fun handleRtspRequest(
        lines: List<String>,
        body: String,
        writer: OutputStreamWriter,
        remoteIp: String
    ): String? {
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 3) return null

        val method = parts[0].uppercase()
        val uri = parts[1]
        val rawCSeq = lines.firstOrNull { it.startsWith("CSeq:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: "1"
        // Sanitize CSeq to prevent CRLF injection and response splitting
        val cSeq = rawCSeq.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32).ifEmpty { "1" }

        // Extract presentation URL if present in request or body
        if (uri.startsWith("rtsp://", ignoreCase = true)) {
            currentPresentationUrl = uri
            _engineState.value = _engineState.value.copy(presentationUrl = uri)
        }
        val urlMatch = Regex("wfd_presentation_URL:\\s*(rtsp://[^\\s]+)").find(body)
        if (urlMatch != null) {
            currentPresentationUrl = urlMatch.groupValues[1]
            _engineState.value = _engineState.value.copy(presentationUrl = currentPresentationUrl)
        }

        return when (method) {
            // Stage 3 / M1: Windows Source sends OPTIONS to Sink
            // Per WFD spec: Sink responds 200 OK, then immediately sends M2 OPTIONS back
            "OPTIONS" -> {
                val m1Response = buildString {
                    append("RTSP/1.0 200 OK\r\n")
                    append("CSeq: $cSeq\r\n")
                    append("Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n")
                    append("Server: Pad2-Miracast-Sink/1.0\r\n")
                    append("\r\n")
                }

                // Send 200 OK response to M1 first
                writer.write(m1Response)
                writer.flush()
                log("Sent M1 Response (200 OK) to Source")

                // Stage 4 / M2: Sink sends its own OPTIONS request to Source
                // Source waits for M2 before proceeding to M3!
                val m2CSeq = sinkCSeq.getAndIncrement()
                val m2Request = buildString {
                    append("OPTIONS * RTSP/1.0\r\n")
                    append("CSeq: $m2CSeq\r\n")
                    append("Require: org.wfa.wfd1.0\r\n")
                    append("\r\n")
                }
                writer.write(m2Request)
                writer.flush()
                log(">>> Sent M2 (OPTIONS) to Source:\n$m2Request")

                null // Already sent response + M2 directly via writer
            }

            // Stage 5 / M3 or Stage 9 / M16 Keep-Alive:
            "GET_PARAMETER" -> {
                if (body.trim().isEmpty()) {
                    // M16 Keep-Alive probe from Windows Source:
                    // MUST return 200 OK with Session header per WFD specification!
                    log(">>> Received Keep-Alive (M16 GET_PARAMETER) from Source! Sending 200 OK...")
                    buildString {
                        append("RTSP/1.0 200 OK\r\n")
                        append("CSeq: $cSeq\r\n")
                        if (currentSessionId.isNotEmpty()) {
                            append("Session: $currentSessionId\r\n")
                        }
                        append("\r\n")
                    }
                } else {
                    // M3 Capabilities Query:
                    log(">>> Received M3 Capabilities Query from Source. Responding with Sink capabilities...")
                    val capBody = buildCapabilitiesResponse(body)
                    buildString {
                        append("RTSP/1.0 200 OK\r\n")
                        append("CSeq: $cSeq\r\n")
                        append("Content-Type: text/parameters\r\n")
                        append("Content-Length: ${capBody.toByteArray(Charsets.UTF_8).size}\r\n")
                        append("\r\n")
                        append(capBody)
                    }
                }
            }

            // Stage 6 / M4 & M5: Windows sets format or triggers SETUP
            "SET_PARAMETER" -> {
                val isTriggerSetup = body.contains("wfd_trigger_method: SETUP", ignoreCase = true) ||
                        body.contains("wfd_trigger_method: play", ignoreCase = true) ||
                        body.contains("wfd_trigger_method: setup", ignoreCase = true)

                val isTriggerTeardown = body.contains("wfd_trigger_method: TEARDOWN", ignoreCase = true)

                // First build 200 OK to acknowledge the SET_PARAMETER
                val okResponse = buildString {
                    append("RTSP/1.0 200 OK\r\n")
                    append("CSeq: $cSeq\r\n")
                    append("\r\n")
                }

                if (isTriggerSetup) {
                    log(">>> Stage 6 Complete: Received wfd_trigger_method: SETUP! Sending 200 OK, then launching M6 (SETUP)...")
                    // Flush 200 OK response first
                    writer.write(okResponse)
                    writer.flush()

                    // Immediately fire Stage 7: M6 (SETUP)
                    scope.launch {
                        delay(50) // Small yield to let 200 OK reach Windows network stack
                        sendM6Setup(writer, remoteIp)
                    }
                    return null
                }

                if (isTriggerTeardown) {
                    log(">>> Received wfd_trigger_method: TEARDOWN from Source! Closing stream...")
                    _engineState.value = _engineState.value.copy(isStreaming = false)
                    onUibcDisabled?.invoke()
                    onStreamStopped()
                    return okResponse
                }

                // Stage 6 / M4 or M14: User Input Back Channel (UIBC) negotiation
                if (body.contains("wfd_uibc", ignoreCase = true)) {
                    val portMatch = Regex("port=(\\d+)", RegexOption.IGNORE_CASE).find(body)
                    val uibcPort = portMatch?.groupValues?.get(1)?.toIntOrNull()
                    val isEnable = Regex("wfd_uibc_setting:\\s*enable", RegexOption.IGNORE_CASE).containsMatchIn(body) ||
                            (uibcPort != null && uibcPort > 0 && !Regex("wfd_uibc_setting:\\s*disable", RegexOption.IGNORE_CASE).containsMatchIn(body))
                    val isDisable = Regex("wfd_uibc_setting:\\s*disable", RegexOption.IGNORE_CASE).containsMatchIn(body)

                    if (isEnable && uibcPort != null && uibcPort > 0) {
                        log(">>> UIBC Enabled by Source on port $uibcPort! Triggering UIBC connection to $remoteIp:$uibcPort...")
                        onUibcNegotiated?.invoke(remoteIp, uibcPort)
                    } else if (isDisable) {
                        log(">>> UIBC Disabled by Source")
                        onUibcDisabled?.invoke()
                    }
                }

                okResponse
            }

            // Stage 9: TEARDOWN
            "TEARDOWN" -> {
                log(">>> Received TEARDOWN from Source! Stopping media stream...")
                _engineState.value = _engineState.value.copy(isStreaming = false)
                onUibcDisabled?.invoke()
                onStreamStopped()
                buildString {
                    append("RTSP/1.0 200 OK\r\n")
                    append("CSeq: $cSeq\r\n")
                    append("\r\n")
                }
            }

            else -> {
                log("Unhandled RTSP request method: $method")
                buildString {
                    append("RTSP/1.0 200 OK\r\n")
                    append("CSeq: $cSeq\r\n")
                    append("\r\n")
                }
            }
        }
    }

    /**
     * Handle RTSP 200 OK responses from Windows Source
     */
    private suspend fun handleRtspResponse(
        lines: List<String>,
        writer: OutputStreamWriter,
        remoteIp: String
    ) {
        val cSeq = lines.firstOrNull { it.startsWith("CSeq:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()

        // Check if this is M2 response (200 OK with Public header — Source's capabilities)
        val publicLine = lines.firstOrNull { it.startsWith("Public:", ignoreCase = true) }
        if (publicLine != null && currentSessionId.isEmpty()) {
            log(">>> M2 Response received from Source! Public: ${publicLine.substringAfter(":").trim()}")
            log(">>> Stage 4 Complete: Sink <-> Source OPTIONS exchange done. Waiting for M3 (GET_PARAMETER)...")
            return
        }

        // Check if this response contains Session AND Transport header (Response to M6 SETUP)
        val sessionLine = lines.firstOrNull { it.startsWith("Session:", ignoreCase = true) }
        val transportLine = lines.firstOrNull { it.startsWith("Transport:", ignoreCase = true) }
        if (sessionLine != null && transportLine != null && currentSessionId.isEmpty()) {
            val sessionVal = sessionLine.substringAfter(":").trim().substringBefore(";")
            currentSessionId = sessionVal
            _engineState.value = _engineState.value.copy(sessionId = sessionVal)
            log(">>> Stage 7 Part 1 Complete: Received Session ID: '$sessionVal'! Now sending M7 (PLAY)...")

            // Send Stage 7 Part 2: M7 (PLAY)
            sendM7Play(writer, remoteIp)
            return
        }

        // Check if this is the response to M7 PLAY (Range header present or Session header without Transport)
        val hasRange = lines.any { it.startsWith("Range:", ignoreCase = true) }
        if (hasRange || (currentSessionId.isNotEmpty() && !_engineState.value.isStreaming)) {
            log(">>> Stage 7 & 8 Complete: PLAY acknowledged by Windows Source! Streaming is LIVE on UDP port $rtpPort!")
            _engineState.value = _engineState.value.copy(isStreaming = true)
            onStreamStarted(remoteIp, rtpPort)
            startKeepAlive()
            return
        }
    }

    /**
     * Stage 7 Part 1: Send M6 SETUP to Windows Source
     */
    private suspend fun sendM6Setup(writer: OutputStreamWriter, remoteIp: String) = withContext(Dispatchers.IO) {
        val setupUrl = resolveStreamUrl(remoteIp)
        val cseq = sinkCSeq.getAndIncrement()

        val m6 = buildString {
            append("SETUP $setupUrl RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            append("Transport: RTP/AVP/UDP;unicast;client_port=$rtpPort\r\n")
            append("\r\n")
        }
        writer.write(m6)
        writer.flush()
        log(">>> Dispatched M6 (SETUP):\n$m6")
    }

    /**
     * Stage 7 Part 2: Send M7 PLAY to Windows Source
     */
    private suspend fun sendM7Play(writer: OutputStreamWriter, remoteIp: String) = withContext(Dispatchers.IO) {
        val playUrl = resolveStreamUrl(remoteIp)
        val cseq = sinkCSeq.getAndIncrement()

        val m7 = buildString {
            append("PLAY $playUrl RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            append("Session: $currentSessionId\r\n")
            append("\r\n")
        }
        writer.write(m7)
        writer.flush()
        log(">>> Dispatched M7 (PLAY):\n$m7")
    }

    private fun resolveStreamUrl(remoteIp: String): String {
        return if (currentPresentationUrl.startsWith("rtsp://", ignoreCase = true) && !currentPresentationUrl.contains("localhost")) {
            if (currentPresentationUrl.endsWith("/streamid=0")) {
                currentPresentationUrl
            } else {
                "${currentPresentationUrl.trimEnd('/')}/streamid=0"
            }
        } else {
            "rtsp://$remoteIp/wfd1.0/streamid=0"
        }
    }

    private fun buildCapabilitiesResponse(requestBody: String = ""): String {
        return buildString {
            // Video formats based on active ResolutionPreference (3K Native, 1080p, 1200p, 720p, or Auto)
            append(resolutionPreference.wfdVideoFormats)
            append("wfd_audio_codecs: LPCM 00000003 00, AAC 00000007 00\r\n")
            // Port1 MUST be 0 per AOSP WifiDisplaySource.cpp:800!
            append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast $rtpPort 0 mode=play\r\n")
            append("wfd_content_protection: none\r\n")
            append("wfd_uibc_capability: input_category_list=GENERIC, HIDC; generic_cap_list=Mouse, SingleTouch, MultiTouch; hidc_cap_list=none; port=none\r\n")
            append("wfd_connector_type: 05\r\n")
            append("wfd_standby_resume_capability: none\r\n")

            // Extended Display Identification Data (EDID) for 3K 3000x2120 / custom modes
            val edid = resolutionPreference.edidPayload
            if (edid != null) {
                append("wfd_display_edid: 0001 $edid\r\n")
            } else if (requestBody.contains("wfd_display_edid", ignoreCase = true)) {
                append("wfd_display_edid: none\r\n")
            }

            if (requestBody.contains("microsoft", ignoreCase = true)) {
                append("microsoft_format_change_capability: supported\r\n")
                append("microsoft_diagnostics_capability: none\r\n")
                if (requestBody.contains("microsoft_video_formats", ignoreCase = true)) {
                    append("microsoft_video_formats: 0000001fffff\r\n")
                }
                if (requestBody.contains("microsoft_max_bitrate", ignoreCase = true)) {
                    append("microsoft_max_bitrate: 50000\r\n")
                }
            }
        }
    }

    private fun log(message: String) {
        Log.w(TAG, message)
        println("[RtspEngine] $message")
        onLog("[RTSP] $message")
    }
}
