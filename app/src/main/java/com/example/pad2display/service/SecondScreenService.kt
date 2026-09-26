package com.example.pad2display.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.pad2display.MainActivity
import com.example.pad2display.R
import com.example.pad2display.media.DEFAULT_RTP_PORT
import com.example.pad2display.media.ResolutionPreference
import com.example.pad2display.media.RtpReceiver
import com.example.pad2display.media.RtpReceiverStats
import com.example.pad2display.media.TsDemuxer
import com.example.pad2display.media.VideoDecoder
import com.example.pad2display.media.VideoFormatInfo
import com.example.pad2display.mice.MICE_TCP_PORT
import com.example.pad2display.mice.MiceDiscoveryService
import com.example.pad2display.mice.MiceDiscoveryState
import com.example.pad2display.mice.MiceServer
import com.example.pad2display.mice.MiceServerState
import com.example.pad2display.rtsp.DEFAULT_RTSP_PORT
import com.example.pad2display.rtsp.RtspEngineState
import com.example.pad2display.rtsp.RtspServer
import com.example.pad2display.uibc.UibcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "SecondScreenService"

/**
 * Foreground Service maintaining the entire Miracast / MS-MICE pipeline.
 *
 * Implements Milestone 9 (Foreground Service Architecture) and Milestone 9.5
 * (Connection Recovery & Long-Session Stability).
 *
 * Runs independently of the UI lifecycle:
 * - Streaming continues uninterrupted during activity recreation, rotation, or app backgrounding.
 * - Hardware decoder dynamically switches surfaces via MediaCodec.setOutputSurface.
 * - Auto-recovery monitors connection health and re-arms discovery on unexpected disconnects.
 */
class SecondScreenService : Service() {

    inner class LocalBinder : Binder() {
        val service: SecondScreenService
            get() = this@SecondScreenService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // ------------------------------------------------------------------------
    // Pipeline Components
    // ------------------------------------------------------------------------
    lateinit var videoDecoder: VideoDecoder
        private set
    lateinit var tsDemuxer: TsDemuxer
        private set
    lateinit var rtpReceiver: RtpReceiver
        private set
    lateinit var rtspServer: RtspServer
        private set
    lateinit var miceDiscovery: MiceDiscoveryService
        private set
    lateinit var miceServer: MiceServer
        private set
    lateinit var uibcManager: UibcManager
        private set

    // ------------------------------------------------------------------------
    // State Flows
    // ------------------------------------------------------------------------
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectionMode = MutableStateFlow(MirrorConnectionMode.INFRASTRUCTURE_MICE)
    val connectionMode: StateFlow<MirrorConnectionMode> = _connectionMode.asStateFlow()

    private val _resolutionPreference = MutableStateFlow(ResolutionPreference.FHD_1080P)
    val resolutionPreference: StateFlow<ResolutionPreference> = _resolutionPreference.asStateFlow()

    private val _eventLogs = MutableStateFlow<List<String>>(emptyList())
    val eventLogs: StateFlow<List<String>> = _eventLogs.asStateFlow()

    // Pass-through flows for UI consumption
    val rtspState: StateFlow<RtspEngineState>
        get() = rtspServer.serverState
    val rtpStats: StateFlow<RtpReceiverStats>
        get() = rtpReceiver.stats
    val videoFormat: StateFlow<VideoFormatInfo?>
        get() = videoDecoder.activeFormat
    val miceDiscoveryState: StateFlow<MiceDiscoveryState>
        get() = miceDiscovery.discoveryState
    val miceServerState: StateFlow<MiceServerState>
        get() = miceServer.serverState

    private var lastRenderSurface: Surface? = null
    private var lastPacketsReceived = 0L
    private var lastFramesRendered = 0L
    private var stallCheckSeconds = 0
    private var decoderStallSeconds = 0

    // ------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SecondScreenService onCreate: initializing core pipeline...")
        _instance.value = this

        createNotificationChannel()
        initPipeline()
        startForegroundServiceNotification()

        startStateObservers()
        startWatchdog()

        // Start in default mode (MS-MICE Wi-Fi router mode)
        setConnectionMode(_connectionMode.value)
        addLog("SecondScreenService foreground engine initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand received action: $action")
        when (action) {
            ACTION_START -> {
                restartActiveMode()
            }
            ACTION_DISCONNECT -> {
                addLog("Disconnect requested via notification or UI action")
                disconnectSession()
            }
            ACTION_STOP -> {
                addLog("Stop service requested")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "SecondScreenService onDestroy: cleaning up resources...")
        _instance.value = null
        _connectionState.value = ConnectionState.DISCONNECTED

        try {
            miceDiscovery.stopAdvertising()
        } catch (_: Exception) {}
        try {
            miceServer.stop()
        } catch (_: Exception) {}
        try {
            rtspServer.stop()
        } catch (_: Exception) {}
        try {
            rtpReceiver.stop()
        } catch (_: Exception) {}
        try {
            videoDecoder.releaseCodec()
        } catch (_: Exception) {}
        try {
            uibcManager.disconnect()
        } catch (_: Exception) {}

        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------
    // Pipeline Initialization
    // ------------------------------------------------------------------------
    private fun initPipeline() {
        // User Input Back Channel (UIBC) Manager
        uibcManager = UibcManager { logMsg ->
            addLog(logMsg)
        }

        // Video Decoder
        videoDecoder = VideoDecoder { logMsg ->
            addLog(logMsg)
        }.apply {
            onRequestKeyframe = {
                rtspServer.requestIdrFrame()
            }
        }

        // MPEG-TS Demuxer
        tsDemuxer = TsDemuxer { data, length, ptsUs ->
            videoDecoder.decodeAccessUnit(data, length, ptsUs)
        }

        // RTP Media Receiver (UDP port 19000)
        rtpReceiver = RtpReceiver(port = DEFAULT_RTP_PORT) { logMsg ->
            addLog(logMsg)
        }.apply {
            onDataPacket = { data, offset, len ->
                tsDemuxer.processRtpPayload(data, offset, len)
            }
        }

        // RTSP Server (Port 7236)
        rtspServer = RtspServer(
            context = this,
            defaultPort = DEFAULT_RTSP_PORT,
            rtpPort = DEFAULT_RTP_PORT,
            onStreamStarted = { remoteIp, port ->
                addLog(">>> Media pipeline triggered from $remoteIp! Starting RTP receiver on port $port...")
                tsDemuxer.reset()
                rtpReceiver.expectedSenderIp = remoteIp
                rtpReceiver.start()
                _connectionState.value = ConnectionState.STREAMING
                updateNotification()

                // Request initial IDR keyframe after short delay so SurfaceView can finish attaching
                serviceScope.launch {
                    delay(250)
                    rtspServer.requestIdrFrame()
                }
            },
            onStreamStopped = {
                addLog("Media stream stopped. Resetting RTP receiver and decoder...")
                uibcManager.onDisabled()
                rtpReceiver.expectedSenderIp = null
                rtpReceiver.stop()
                videoDecoder.releaseCodec()

                // Milestone 9.5: Auto-recovery on unexpected stream drop
                if (_connectionState.value == ConnectionState.STREAMING) {
                    _connectionState.value = ConnectionState.INTERRUPTED
                    addLog("Stream interrupted unexpectedly. Triggering auto-recovery in 1s...")
                    serviceScope.launch {
                        delay(1000)
                        _connectionState.value = ConnectionState.RECONNECTING
                        delay(500)
                        restartActiveMode()
                    }
                } else if (_connectionState.value != ConnectionState.DISCONNECTED) {
                    _connectionState.value = ConnectionState.DISCOVERING
                }
                updateNotification()
            },
            onLog = { logMsg ->
                addLog(logMsg)
            }
        ).apply {
            resolutionPreference = _resolutionPreference.value
            onUibcNegotiated = { remoteIp, port ->
                uibcManager.onNegotiated(remoteIp, port)
            }
            onUibcDisabled = {
                uibcManager.onDisabled()
            }
        }

        // MS-MICE Discovery
        miceDiscovery = MiceDiscoveryService(this) { logMsg ->
            addLog(logMsg)
        }

        // MS-MICE Port 7250 Server
        miceServer = MiceServer(
            port = MICE_TCP_PORT,
            onSourceReady = { sourceIp, sourceRtspPort, friendlyName ->
                addLog(">>> [MS-MICE] Source Ready received from '$friendlyName' ($sourceIp:$sourceRtspPort)! Connecting RTSP Client...")
                _connectionState.value = ConnectionState.CONNECTING
                updateNotification()
                rtspServer.connectAsClient(sourceIp, sourceRtspPort)
            },
            onStopProjection = {
                addLog(">>> [MS-MICE] STOP_PROJECTION received from Source! Stopping stream...")
                disconnectSession()
            },
            onLog = { logMsg ->
                addLog(logMsg)
            }
        )
    }

    private fun startStateObservers() {
        // Track RTSP handshake state transitions
        serviceScope.launch {
            rtspServer.serverState.collect { rState ->
                if (rState.isStreaming) {
                    if (_connectionState.value != ConnectionState.STREAMING) {
                        _connectionState.value = ConnectionState.STREAMING
                        updateNotification()
                    }
                } else if (rState.isConnected) {
                    if (_connectionState.value != ConnectionState.NEGOTIATING) {
                        _connectionState.value = ConnectionState.NEGOTIATING
                        updateNotification()
                    }
                }
            }
        }
    }

    /**
     * Long-session stability watchdog (Milestone 9.5):
     * - Periodically refreshes notification with live bitrate and resolution
     * - Detects stalled RTP packet flow and initiates recovery
     */
    private fun startWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(2000)
                if (_connectionState.value == ConnectionState.STREAMING) {
                    val currentPackets = rtpReceiver.stats.value.packetsReceived
                    val currentBitrate = rtpReceiver.stats.value.bitrateMbps
                    val currentRendered = videoDecoder.framesRenderedCount

                    // 1. Packet flow stall detection
                    if (currentPackets == lastPacketsReceived && currentPackets > 0) {
                        stallCheckSeconds += 2
                        if (stallCheckSeconds >= 10 && stallCheckSeconds % 10 == 0) {
                            addLog("Warning: Streaming packet flow idle for ${stallCheckSeconds}s")
                        }
                        if (stallCheckSeconds >= 25) {
                            addLog("Network stall detected (>25s no packets). Recovering session...")
                            disconnectSession()
                        }
                    } else {
                        stallCheckSeconds = 0
                    }

                    // 2. Decoder freeze detection: packets arriving but no new frames rendered
                    if (currentBitrate > 0.5 && currentRendered == lastFramesRendered && currentRendered > 0) {
                        decoderStallSeconds += 2
                        if (decoderStallSeconds >= 2) {
                            addLog("Video freeze detected: RTP active (${currentBitrate} Mbps) but no frames rendered for ${decoderStallSeconds}s. Requesting IDR keyframe...")
                            rtspServer.requestIdrFrame()
                            decoderStallSeconds = 0
                        }
                    } else {
                        decoderStallSeconds = 0
                    }

                    lastPacketsReceived = currentPackets
                    lastFramesRendered = currentRendered
                    updateNotification()
                } else {
                    stallCheckSeconds = 0
                    decoderStallSeconds = 0
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Public Commands & Surface Management
    // ------------------------------------------------------------------------

    /**
     * Attach a new rendering surface from SurfaceView.
     * MediaCodec.setOutputSurface dynamically binds without dropping the session!
     */
    fun attachSurface(surface: Surface) {
        lastRenderSurface = surface
        videoDecoder.setSurface(surface)
        if (_connectionState.value == ConnectionState.STREAMING) {
            rtspServer.requestIdrFrame()
        }
        addLog("Surface attached to VideoDecoder (${surface.isValid})")
    }

    /**
     * Detach surface when activity goes to background or is destroyed.
     * Codec state is preserved so rotation or app switching is seamless!
     */
    fun detachSurface() {
        videoDecoder.setSurface(null)
        lastRenderSurface = null
        addLog("Surface detached from VideoDecoder (holding decoder state)")
    }

    /**
     * Select active connection mode (MS-MICE Wi-Fi Router vs Direct P2P)
     */
    fun setConnectionMode(mode: MirrorConnectionMode) {
        _connectionMode.value = mode
        addLog("Connection mode set to: ${mode.title}")
        restartActiveMode()
    }

    /**
     * Update target resolution preference for RTSP capability exchange
     */
    fun setResolutionPreference(res: ResolutionPreference) {
        _resolutionPreference.value = res
        rtspServer.resolutionPreference = res
        addLog("Resolution preference set to: ${res.title}")
    }

    /**
     * Restart current connection mode listeners
     */
    fun restartActiveMode() {
        when (_connectionMode.value) {
            MirrorConnectionMode.INFRASTRUCTURE_MICE -> {
                addLog("Starting MS-MICE listeners (mDNS + TCP 7250 + RTSP 7236)...")
                _connectionState.value = ConnectionState.DISCOVERING
                miceDiscovery.startAdvertising("OnePlus Pad 2")
                miceServer.start()
                rtspServer.start(DEFAULT_RTSP_PORT)
            }
            MirrorConnectionMode.DIRECT_P2P -> {
                addLog("Starting Direct P2P listener (RTSP 7236)...")
                _connectionState.value = ConnectionState.DISCOVERING
                miceDiscovery.stopAdvertising()
                miceServer.stop()
                rtspServer.start(DEFAULT_RTSP_PORT)
            }
        }
        updateNotification()
    }

    /**
     * Disconnect active streaming session and return to discovery standby
     */
    fun disconnectSession() {
        addLog("Disconnecting streaming session...")
        try {
            uibcManager.disconnect()
        } catch (_: Exception) {}
        rtspServer.stop()
        rtpReceiver.stop()
        videoDecoder.releaseCodec()
        _connectionState.value = ConnectionState.DISCOVERING
        restartActiveMode()
        updateNotification()
    }

    /**
     * Forward P2P group status events from P2pDiagnosticsController
     */
    fun onP2pConnected(isGroupOwner: Boolean, goAddress: String) {
        if (!isGroupOwner) {
            addLog(">>> [Wi-Fi Direct] Group established! Windows is GO at $goAddress. Connecting RTSP Client...")
            _connectionState.value = ConnectionState.CONNECTING
            updateNotification()
            rtspServer.connectAsClient(goAddress, DEFAULT_RTSP_PORT)
        } else {
            addLog(">>> [Wi-Fi Direct] Group established! Tablet is GO. RTSP Server listening on $DEFAULT_RTSP_PORT...")
            _connectionState.value = ConnectionState.DISCOVERING
            rtspServer.start(DEFAULT_RTSP_PORT)
            updateNotification()
        }
    }

    fun onP2pDisconnected() {
        addLog("[Wi-Fi Direct] Group removed. Resetting RTSP Server...")
        disconnectSession()
    }

    fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$time] $msg"
        _eventLogs.value = listOf(entry) + _eventLogs.value.take(49)
        Log.w(TAG, msg)
    }

    // ------------------------------------------------------------------------
    // Notifications & Foreground Service
    // ------------------------------------------------------------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SecondScreen Wireless Display",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps wireless display connection and video decoding active"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = _connectionState.value
        val format = if (::videoDecoder.isInitialized) videoDecoder.activeFormat.value else null
        val stats = if (::rtpReceiver.isInitialized) rtpReceiver.stats.value else null

        val title = when (state) {
            ConnectionState.STREAMING -> "Wireless Display Active"
            ConnectionState.CONNECTING -> "Connecting to Windows PC..."
            ConnectionState.NEGOTIATING -> "Negotiating Display Session..."
            ConnectionState.INTERRUPTED -> "Stream Interrupted — Reconnecting..."
            ConnectionState.RECONNECTING -> "Reconnecting to Windows..."
            ConnectionState.DISCOVERING -> "SecondScreen Ready (Win + K)"
            ConnectionState.DISCONNECTED -> "SecondScreen Offline"
        }

        val text = when (state) {
            ConnectionState.STREAMING -> {
                val res = format?.displayString ?: _resolutionPreference.value.title.substringBefore(" ")
                val mbps = stats?.bitrateMbps ?: 0.0
                val pkts = stats?.packetsReceived ?: 0
                "$res • $mbps Mbps • $pkts pkts"
            }
            ConnectionState.DISCOVERING -> {
                "Listening on ${_connectionMode.value.title.substringBefore(" ")} • Ready for Win + K"
            }
            else -> state.description
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SecondScreenService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_cast)
            .setColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            .setContentIntent(contentIntent)
            .setOngoing(state == ConnectionState.STREAMING || state == ConnectionState.DISCOVERING)
            .setOnlyAlertOnce(true)

        if (state == ConnectionState.STREAMING) {
            builder.addAction(R.drawable.ic_notification_cast, "Disconnect", disconnectIntent)
        }

        return builder.build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "channel_second_screen_service"

        const val ACTION_START = "com.example.pad2display.action.START"
        const val ACTION_DISCONNECT = "com.example.pad2display.action.DISCONNECT"
        const val ACTION_STOP = "com.example.pad2display.action.STOP"

        private val _instance = MutableStateFlow<SecondScreenService?>(null)
        val instance: StateFlow<SecondScreenService?> = _instance.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, SecondScreenService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SecondScreenService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}
