package com.example.pad2display.media

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

private const val TAG = "RtpReceiver"
const val DEFAULT_RTP_PORT = 19000

data class RtpReceiverStats(
    val isRunning: Boolean = false,
    val boundPort: Int = DEFAULT_RTP_PORT,
    val packetsReceived: Long = 0,
    val bytesReceived: Long = 0,
    val bitrateMbps: Double = 0.0,
    val lastSender: String = "None"
)

class RtpReceiver(
    private val port: Int = DEFAULT_RTP_PORT,
    private val onLog: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null

    var onDataPacket: ((data: ByteArray, offset: Int, length: Int) -> Unit)? = null
    var expectedSenderIp: String? = null

    private val _stats = MutableStateFlow(RtpReceiverStats(boundPort = port))
    val stats: StateFlow<RtpReceiverStats> = _stats.asStateFlow()

    @Synchronized
    fun start() {
        if (_stats.value.isRunning && socket?.isBound == true && socket?.isClosed == false) {
            return
        }
        stop()

        receiveJob = scope.launch {
            var totalPackets = 0L
            var totalBytes = 0L
            var windowBytes = 0L
            var windowTime = System.currentTimeMillis()

            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.receiveBufferSize = 4 * 1024 * 1024 // 4 MB receive buffer to prevent packet drops
                s.bind(InetSocketAddress("0.0.0.0", port))
                socket = s

                _stats.value = _stats.value.copy(
                    isRunning = true,
                    boundPort = port
                )
                log("RTP UDP Receiver listening on 0.0.0.0:$port (buffer=${s.receiveBufferSize / 1024} KB)")

                val buffer = ByteArray(4096) // 4 KB buffer accommodating jumbo frames
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive && !s.isClosed) {
                    // MUST reset packet length before each receive; otherwise Java DatagramSocket truncates
                    // subsequent packets to the length of the previous packet!
                    packet.length = buffer.size
                    s.receive(packet)
                    val len = packet.length
                    val senderIp = packet.address?.hostAddress
                    val sender = packet.socketAddress.toString()

                    // Sender IP authorization: drop unauthorized packets on shared LANs
                    val authorizedIp = expectedSenderIp
                    if (authorizedIp != null && senderIp != null && senderIp != authorizedIp) {
                        continue
                    }

                    if (len > 12) {
                        onDataPacket?.invoke(buffer, 12, len - 12)
                    }

                    totalPackets++
                    totalBytes += len
                    windowBytes += len

                    val now = System.currentTimeMillis()
                    val elapsed = now - windowTime
                    if (elapsed >= 1000) {
                        val mbps = (windowBytes * 8.0) / (elapsed * 1000.0)
                        _stats.value = _stats.value.copy(
                            packetsReceived = totalPackets,
                            bytesReceived = totalBytes,
                            bitrateMbps = (mbps * 100).toLong() / 100.0,
                            lastSender = sender
                        )
                        windowBytes = 0L
                        windowTime = now
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    log("RTP Receiver exception: ${e.message}")
                }
            } finally {
                stop()
            }
        }
    }

    @Synchronized
    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        receiveJob?.cancel()
        receiveJob = null
        _stats.value = _stats.value.copy(
            isRunning = false,
            bitrateMbps = 0.0
        )
        log("RTP Receiver stopped")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        println("[RTP Receiver] $msg")
        onLog("[RTP] $msg")
    }
}
