package com.example.pad2display

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.pad2display.diagnostic.*
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.viewinterop.AndroidView
import com.example.pad2display.media.DEFAULT_RTP_PORT
import com.example.pad2display.media.DisplayScaleMode
import com.example.pad2display.media.ResolutionPreference
import com.example.pad2display.media.RtpReceiver
import com.example.pad2display.media.RtpReceiverStats
import com.example.pad2display.media.TsDemuxer
import com.example.pad2display.media.VideoDecoder
import com.example.pad2display.media.VideoFormatInfo
import com.example.pad2display.mice.MiceDiscoveryService
import com.example.pad2display.mice.MiceDiscoveryState
import com.example.pad2display.mice.MiceServer
import com.example.pad2display.mice.MiceServerState
import com.example.pad2display.rtsp.DEFAULT_RTSP_PORT
import com.example.pad2display.rtsp.RtspEngineState
import com.example.pad2display.service.ConnectionState
import com.example.pad2display.service.MirrorConnectionMode
import com.example.pad2display.service.SecondScreenService
import kotlinx.coroutines.delay

val MirrorConnectionMode.icon: ImageVector
    get() = when (this) {
        MirrorConnectionMode.INFRASTRUCTURE_MICE -> Icons.Default.Wifi
        MirrorConnectionMode.DIRECT_P2P -> Icons.Default.WifiTethering
    }

class MainActivity : ComponentActivity() {

    private val logTag = "Pad2Display/Diagnostic"
    private lateinit var p2pController: P2pDiagnosticsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p2pController = P2pDiagnosticsController(this)
        Log.i(logTag, "SecondScreen diagnostic activity started.")

        // Start Foreground Service so mirroring session survives activity recreation and backgrounding
        SecondScreenService.start(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    onPrimary = Color.Black,
                    primaryContainer = Color(0xFF00391C),
                    onPrimaryContainer = Color(0xFF00E676),
                    surface = Color(0xFF1E1E1E),
                    background = Color(0xFF121212)
                )
            ) {
                DiagnosticScreen(
                    p2pController = p2pController,
                    onLog = { msg -> Log.i(logTag, msg) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        p2pController.registerReceiver()
        p2pController.refreshDiagnostics()
    }

    override fun onDestroy() {
        super.onDestroy()
        p2pController.unregisterReceiver()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    p2pController: P2pDiagnosticsController,
    onLog: (String) -> Unit
) {
    val context = LocalContext.current

    // Foreground Service Instance
    val service by SecondScreenService.instance.collectAsState()

    // Service-backed Live States
    val connectionState = service?.connectionState?.collectAsState()?.value ?: ConnectionState.DISCONNECTED
    val selectedMode = service?.connectionMode?.collectAsState()?.value ?: MirrorConnectionMode.INFRASTRUCTURE_MICE
    val selectedResolution = service?.resolutionPreference?.collectAsState()?.value ?: ResolutionPreference.FHD_1080P
    val rtspState = service?.rtspState?.collectAsState()?.value ?: RtspEngineState()
    val rtpStats = service?.rtpStats?.collectAsState()?.value ?: RtpReceiverStats()
    val miceDiscoveryState = service?.miceDiscoveryState?.collectAsState()?.value ?: MiceDiscoveryState()
    val miceServerState = service?.miceServerState?.collectAsState()?.value ?: MiceServerState()
    val activeFormat = service?.videoFormat?.collectAsState()?.value
    val eventLogs = service?.eventLogs?.collectAsState()?.value ?: listOf("Initializing SecondScreen Service...")
    var logsCleared by remember { mutableStateOf(false) }
    val displayLogs = if (logsCleared) emptyList() else eventLogs

    // Device & Network Diagnostics
    var deviceData by remember { mutableStateOf(DeviceInfoProvider.getDiagnostics(context)) }
    var wifiData by remember { mutableStateOf(WifiDiagnosticsProvider.getDiagnostics(context)) }
    val p2pData by p2pController.p2pStateFlow.collectAsState()

    fun addLog(msg: String) {
        onLog(msg)
        service?.addLog(msg)
    }

    var isFullscreenPlayerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(rtspState.isStreaming) {
        if (rtspState.isStreaming) {
            isFullscreenPlayerOpen = true
        }
    }

    // Keep P2P discovery active continuously so device is always discoverable by Windows
    LaunchedEffect(Unit) {
        // Immediately start discovery and configure WFD sink on app launch without initial delay
        p2pController.startDiscovery { success, msg ->
            addLog("P2P Discovery: $msg")
        }
        p2pController.configureWfdSink(true) { success, msg ->
            addLog("WFD Sink: $msg")
        }
        while (true) {
            delay(10000)
            if (!p2pData.isDiscoveryActive) {
                p2pController.startDiscovery { _, _ -> }
            }
        }
    }

    // Synchronize WFD Sink beacon and P2P listen state on mode change
    LaunchedEffect(selectedMode) {
        p2pController.configureWfdSink(true) { success, msg ->
            addLog("WFD Sink ($selectedMode): $msg")
        }
        p2pController.startDiscovery { success, msg ->
            addLog("Wi-Fi Scan ($selectedMode): $msg")
        }
    }

    // Forward P2P Connection Lifecycle to Foreground Service
    DisposableEffect(p2pController, service) {
        p2pController.onP2pConnected = { isGroupOwner, goAddress ->
            service?.onP2pConnected(isGroupOwner, goAddress)
        }
        p2pController.onP2pDisconnected = {
            service?.onP2pDisconnected()
        }
        onDispose {
            p2pController.onP2pConnected = null
            p2pController.onP2pDisconnected = null
        }
    }

    // Permissions State
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.CHANGE_WIFI_STATE)
            add(Manifest.permission.ACCESS_NETWORK_STATE)
            add(Manifest.permission.INTERNET)
            add(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }

    var permissionsMap by remember {
        mutableStateOf(
            requiredPermissions.associateWith {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsMap = requiredPermissions.associateWith {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        val grantedCount = result.values.count { it }
        addLog("Permission request completed: $grantedCount/${result.size} granted")
        deviceData = DeviceInfoProvider.getDiagnostics(context)
        wifiData = WifiDiagnosticsProvider.getDiagnostics(context)
        p2pController.refreshDiagnostics()
    }

    // Periodic diagnostics refresh
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            wifiData = WifiDiagnosticsProvider.getDiagnostics(context)
        }
    }

    if (isFullscreenPlayerOpen) {
        FullscreenPlayerScreen(
            activeFormat = activeFormat,
            rtpStats = rtpStats,
            rtspState = rtspState,
            selectedResolution = selectedResolution,
            onAttachSurface = { surface ->
                service?.attachSurface(surface)
            },
            onDetachSurface = {
                service?.detachSurface()
            },
            onResolutionChanged = { newRes ->
                service?.setResolutionPreference(newRes)
            },
            onBack = { isFullscreenPlayerOpen = false },
            onDisconnect = {
                service?.disconnectSession()
                isFullscreenPlayerOpen = false
            }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Second Screen", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(
                                "Wireless Display Receiver (MS-MICE & Wi-Fi Direct)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Spacer(modifier = Modifier.height(2.dp)) }

                // Active Stream Card (Tap to view fullscreen display)
                if (rtspState.isStreaming) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isFullscreenPlayerOpen = true },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF00391C)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF00E676))
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("WIRELESS DISPLAY ACTIVE", fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                                        Text("${rtpStats.bitrateMbps} Mbps • Tap to view full screen", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                                    }
                                }
                                Icon(Icons.Default.Fullscreen, contentDescription = null, tint = Color(0xFF00E676))
                            }
                        }
                    }
                }

                // Overview Status Banner
                item {
                    StatusBanner(
                        connectionState = connectionState,
                        isWifiConnected = wifiData.isConnected,
                        ssid = wifiData.ssid,
                        isP2pReady = p2pData.hasWifiDirectFeature && p2pData.p2pState == "ENABLED",
                        p2pState = p2pData.p2pState,
                        isDiscoveryActive = p2pData.isDiscoveryActive
                    )
                }

            // Connection Mode Selector
            item {
                ModeSelectorCard(
                    selectedMode = selectedMode,
                    onModeSelected = { newMode ->
                        service?.setConnectionMode(newMode)
                    }
                )
            }

            // Resolution & Quality Setting Card
            item {
                ResolutionSelectorCard(
                    selectedResolution = selectedResolution,
                    onResolutionSelected = { newRes ->
                        service?.setResolutionPreference(newRes)
                    }
                )
            }

            // Mode-Specific Active Card
            item {
                when (selectedMode) {
                    MirrorConnectionMode.INFRASTRUCTURE_MICE -> {
                        MiceStatusCard(
                            discoveryState = miceDiscoveryState,
                            serverState = miceServerState,
                            wifiData = wifiData,
                            onRestart = {
                                service?.restartActiveMode()
                                addLog("Restarted MS-MICE services")
                            }
                        )
                    }
                    MirrorConnectionMode.DIRECT_P2P -> {
                        P2pWfdStatusCard(
                            p2pData = p2pData,
                            onAdvertiseSink = {
                                p2pController.configureWfdSink(true) { success, msg ->
                                    addLog("WFD Sink: $msg")
                                }
                            },
                            onCreateGroup = {
                                p2pController.createP2pGroup { success, msg ->
                                    addLog("P2P Group: $msg")
                                }
                            },
                            onEndGroup = {
                                p2pController.removeP2pGroup { success, msg ->
                                    addLog("End Group: $msg")
                                }
                            }
                        )
                    }
                }
            }

            // RTSP & RTP Streaming Pipeline Card
            item {
                StreamingPipelineCard(
                    connectionState = connectionState,
                    rtspState = rtspState,
                    rtpStats = rtpStats
                )
            }

            // Diagnostic Controls Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "UTILITIES & DIAGNOSTICS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    addLog("Requesting runtime permissions...")
                                    permissionLauncher.launch(requiredPermissions.toTypedArray())
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Permissions", fontSize = 12.sp)
                            }

                            FilledTonalButton(
                                onClick = {
                                    addLog("Refreshing diagnostics...")
                                    wifiData = WifiDiagnosticsProvider.getDiagnostics(context)
                                    deviceData = DeviceInfoProvider.getDiagnostics(context)
                                    p2pController.refreshDiagnostics()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Refresh", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val report = DiagnosticExporter.generateReport(
                                        deviceData, wifiData, p2pData, permissionsMap
                                    )
                                    DiagnosticExporter.copyToClipboard(context, report)
                                    addLog("Diagnostic report copied to clipboard.")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy Report", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    val report = DiagnosticExporter.generateReport(
                                        deviceData, wifiData, p2pData, permissionsMap
                                    )
                                    DiagnosticExporter.shareReport(context, report)
                                    addLog("Sharing diagnostic report...")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share Report", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Section: Device & Hardware Codecs
            item {
                DiagnosticCard(
                    title = "DISPLAY & MEDIACODEC DECODERS",
                    icon = Icons.Default.Tv
                ) {
                    val m = deviceData.displayMetrics
                    KeyValueRow("Resolution", "${m.widthPixels} x ${m.heightPixels} @ ${m.refreshRateHz} Hz")
                    KeyValueRow("Supported Rates", "${m.supportedRefreshRates.joinToString(", ")} Hz")
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Hardware Decoders:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    for (c in deviceData.videoCodecCapabilities) {
                        Text(
                            "• ${c.codecName} (${c.mimeType.substringAfter('/')}) | HW: ${c.isHardwareAccelerated} | Max: ${c.maxWidth}x${c.maxHeight}@${c.maxFramerate}fps",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Live Event Log Console
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141414))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "LIVE EVENT STREAM",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(onClick = { logsCleared = true }) {
                                Text("Clear", fontSize = 11.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                        ) {
                            for (log in displayLogs) {
                                Text(
                                    log,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = when {
                                        log.contains(">>>") -> Color(0xFF00E676)
                                        log.contains("error", ignoreCase = true) || log.contains("fail", ignoreCase = true) -> Color(0xFFFF5252)
                                        log.contains("warning", ignoreCase = true) -> Color(0xFFFFD740)
                                        log.contains("MS-MICE") -> Color(0xFF40C4FF)
                                        else -> Color(0xFFCCCCCC)
                                    },
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
    }
}

/**
 * Fullscreen Miracast Video Player with hardware decoding via MediaCodec
 */
@Composable
fun FullscreenPlayerScreen(
    activeFormat: VideoFormatInfo?,
    rtpStats: RtpReceiverStats,
    rtspState: RtspEngineState,
    selectedResolution: ResolutionPreference,
    onAttachSurface: (android.view.Surface) -> Unit,
    onDetachSurface: () -> Unit,
    onResolutionChanged: (ResolutionPreference) -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    var scaleMode by remember { mutableStateOf(DisplayScaleMode.FIT) }
    var showResMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { showControls = !showControls }
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val videoAspect = activeFormat?.aspectRatio ?: (selectedResolution.nominalWidth.toFloat() / selectedResolution.nominalHeight.toFloat())
            val screenAspect = maxWidth.value / maxHeight.value

            val surfaceModifier = when (scaleMode) {
                DisplayScaleMode.FIT -> {
                    if (videoAspect > screenAspect) {
                        Modifier.fillMaxWidth().aspectRatio(videoAspect)
                    } else {
                        Modifier.fillMaxHeight().aspectRatio(videoAspect)
                    }
                }
                DisplayScaleMode.FILL_CROP -> {
                    if (videoAspect > screenAspect) {
                        Modifier.fillMaxHeight().aspectRatio(videoAspect)
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(videoAspect)
                    }
                }
                DisplayScaleMode.STRETCH -> {
                    Modifier.fillMaxSize()
                }
            }

            AndroidView(
                modifier = surfaceModifier,
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onAttachSurface(holder.surface)
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                if (holder.surface.isValid) {
                                    onAttachSurface(holder.surface)
                                }
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onDetachSurface()
                            }
                        })
                    }
                }
            )
        }

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.85f),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "LIVE MIRACAST STREAM",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = Color(0xFF00391C),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, Color(0xFF00E676))
                            ) {
                                Text(
                                    activeFormat?.displayString ?: selectedResolution.title.substringBefore(" "),
                                    color = Color(0xFF00E676),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "${rtpStats.bitrateMbps} Mbps • ${rtpStats.packetsReceived} packets • Target: ${selectedResolution.title}",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Scale Mode Switcher (Fit / Fill / Stretch)
                        Button(
                            onClick = {
                                scaleMode = when (scaleMode) {
                                    DisplayScaleMode.FIT -> DisplayScaleMode.FILL_CROP
                                    DisplayScaleMode.FILL_CROP -> DisplayScaleMode.STRETCH
                                    DisplayScaleMode.STRETCH -> DisplayScaleMode.FIT
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.15f),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = when (scaleMode) {
                                    DisplayScaleMode.FIT -> Icons.Default.AspectRatio
                                    DisplayScaleMode.FILL_CROP -> Icons.Default.CropFree
                                    DisplayScaleMode.STRETCH -> Icons.Default.FitScreen
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(scaleMode.title.substringBefore(" "), fontSize = 12.sp)
                        }

                        // Resolution Selector Menu
                        Box {
                            Button(
                                onClick = { showResMenu = !showResMenu },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.15f),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Resolution", fontSize = 12.sp)
                            }
                            DropdownMenu(
                                expanded = showResMenu,
                                onDismissRequest = { showResMenu = false }
                            ) {
                                ResolutionPreference.entries.forEach { res ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(res.title, fontWeight = if (res == selectedResolution) FontWeight.Bold else FontWeight.Normal)
                                                Text(res.subtitle, fontSize = 10.sp, color = Color.Gray)
                                            }
                                        },
                                        onClick = {
                                            onResolutionChanged(res)
                                            showResMenu = false
                                        },
                                        leadingIcon = if (res == selectedResolution) {
                                            { Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF00E676)) }
                                        } else null
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.15f),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Dashboard, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Diagnostics", fontSize = 12.sp)
                        }

                        Button(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE53935),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Disconnect", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Resolution & Quality Setting Card
 */
@Composable
fun ResolutionSelectorCard(
    selectedResolution: ResolutionPreference,
    onResolutionSelected: (ResolutionPreference) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.HighQuality,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Resolution & Quality Setting",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        selectedResolution.badge,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Configures native resolution advertised to Windows during Miracast capability exchange (Stage 5 / M3). Also adjustable in Windows Settings -> Display.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ResolutionPreference.entries.forEach { res ->
                    val isSelected = res == selectedResolution
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResolutionSelected(res) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color(0xFF222222),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF333333)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        res.title,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF3A3A3A),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            res.badge,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.Black else Color.LightGray,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    res.subtitle,
                                    fontSize = 11.sp,
                                    color = Color(0xFFAAAAAA)
                                )
                            }
                            RadioButton(
                                selected = isSelected,
                                onClick = { onResolutionSelected(res) },
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mode Selector Card allowing smooth 1-tap switching between MS-MICE and Direct P2P
 */
@Composable
fun ModeSelectorCard(
    selectedMode: MirrorConnectionMode,
    onModeSelected: (MirrorConnectionMode) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "CONNECTION MODE",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MirrorConnectionMode.values().forEach { mode ->
                    val isSelected = selectedMode == mode
                    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF333333)
                    val bgColor = if (isSelected) Color(0xFF1B3B2B) else Color(0xFF242424)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(bgColor)
                            .border(width = if (isSelected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(10.dp))
                            .clickable { onModeSelected(mode) }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = mode.icon,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                            Surface(
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF3A3A3A),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    mode.badge,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else Color.LightGray,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            mode.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isSelected) Color.White else Color(0xFFCCCCCC)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            mode.subtitle,
                            fontSize = 10.sp,
                            color = Color(0xFFAAAAAA),
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Mode 1: MS-MICE Live Monitor Card
 */
@Composable
fun MiceStatusCard(
    discoveryState: com.example.pad2display.mice.MiceDiscoveryState,
    serverState: com.example.pad2display.mice.MiceServerState,
    wifiData: WifiDiagnosticData,
    onRestart: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (serverState.connectedClientCount > 0) Color(0xFF1B5E20) else Color(0xFF1E2833)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (discoveryState.isAdvertising && serverState.isRunning) Color(0xFF00E676) else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "MS-MICE Infrastructure Status",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }

                Surface(
                    color = Color(0xFF00B0FF),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "PORT 7250",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val localIp = wifiData.interfaces.flatMap { it.ipv4Addresses }.firstOrNull() ?: "Disconnected"
            KeyValueRow("Wi-Fi IP Address", "$localIp (${wifiData.ssid})")
            KeyValueRow("mDNS Advertised", "${discoveryState.serviceName}._display._tcp.local")
            KeyValueRow("mDNS Active", if (discoveryState.registeredDisplay) "Registered on LAN" else "Publishing...")
            KeyValueRow("MICE TCP Listener", if (serverState.isRunning) "0.0.0.0:7250 LISTENING" else "Offline")
            KeyValueRow("Windows Source", serverState.lastFriendlyName ?: "Waiting for Win + K...")
            KeyValueRow("Last Message", serverState.lastMessage)

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRestart) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restart MICE Discovery", fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * Mode 2: Wi-Fi Direct P2P Status Card
 */
@Composable
fun P2pWfdStatusCard(
    p2pData: P2pDiagnosticData,
    onAdvertiseSink: () -> Unit,
    onCreateGroup: () -> Unit,
    onEndGroup: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Wi-Fi Direct (P2P WFD Sink)",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            KeyValueRow("P2P State", p2pData.p2pState)
            KeyValueRow("Connection Info", p2pData.connectionInfoSummary)
            KeyValueRow("Group Info", p2pData.groupInfoSummary)
            KeyValueRow("WFD Framework Reflection", if (p2pData.wfdFrameworkInspection.isSetWfdInfoMethodPresent) "Accessible" else "Not Present")

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAdvertiseSink,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Advertise Sink", fontSize = 11.sp)
                }
                FilledTonalButton(
                    onClick = onCreateGroup,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Create Group", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onEndGroup,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("End Group", fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * RTSP Session and RTP Media Streaming Status Card
 */
@Composable
fun StreamingPipelineCard(
    connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    rtspState: com.example.pad2display.rtsp.RtspEngineState,
    rtpStats: com.example.pad2display.media.RtpReceiverStats
) {
    val isStreaming = connectionState.isStreaming || rtspState.isStreaming || rtpStats.packetsReceived > 0
    val cardColor = if (isStreaming) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionState) {
                                    ConnectionState.STREAMING -> Color(0xFF00E676)
                                    ConnectionState.CONNECTING, ConnectionState.NEGOTIATING -> Color(0xFFFFD740)
                                    ConnectionState.INTERRUPTED, ConnectionState.RECONNECTING -> Color(0xFFFF5252)
                                    ConnectionState.DISCOVERING -> Color(0xFF00B0FF)
                                    ConnectionState.DISCONNECTED -> Color.Gray
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "RTSP & RTP Media Pipeline",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (isStreaming) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    color = when (connectionState) {
                        ConnectionState.STREAMING -> Color(0xFF00E676)
                        ConnectionState.CONNECTING, ConnectionState.NEGOTIATING -> Color(0xFFFFD740)
                        ConnectionState.INTERRUPTED, ConnectionState.RECONNECTING -> Color(0xFFFF5252)
                        ConnectionState.DISCOVERING -> Color(0xFF00B0FF)
                        ConnectionState.DISCONNECTED -> Color.Gray
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        connectionState.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            KeyValueRow("Connection Lifecycle", "${connectionState.title} (${connectionState.description})")
            KeyValueRow("RTSP Engine Mode", "${rtspState.mode} (${if (rtspState.isConnected) "CONNECTED" else "WAITING"})")
            KeyValueRow("Remote Endpoint", rtspState.remoteAddress)
            KeyValueRow("RTSP Session ID", rtspState.sessionId)
            KeyValueRow("Presentation URL", rtspState.presentationUrl)
            KeyValueRow("RTP UDP Port", "${rtpStats.boundPort} (${if (rtpStats.isRunning) "RECEIVING" else "IDLE"})")
            KeyValueRow("Packets Received", "${rtpStats.packetsReceived}")
            KeyValueRow("Bitrate", "${rtpStats.bitrateMbps} Mbps")
        }
    }
}

@Composable
fun StatusBanner(
    connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    isWifiConnected: Boolean,
    ssid: String,
    isP2pReady: Boolean,
    p2pState: String,
    isDiscoveryActive: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connectionState.isStreaming || isWifiConnected) Color(0xFF00391C) else Color(0xFF3E2723)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (connectionState.isStreaming || isWifiConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (connectionState.isStreaming) Color(0xFF00E676) else if (isWifiConnected) Color(0xFF40C4FF) else Color(0xFFFFAB00),
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = if (connectionState.isStreaming) "SecondScreen Active: ${connectionState.title}" else if (isWifiConnected) "Wi-Fi: $ssid" else "Wi-Fi Disconnected",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Text(
                    text = "State: ${connectionState.title} | P2P: $p2pState | Discovery: ${if (isDiscoveryActive) "Active" else "Idle"}",
                    fontSize = 12.sp,
                    color = Color(0xFFB0BEC5)
                )
            }
        }
    }
}

@Composable
fun DiagnosticCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ============================================================================
// COMPOSE PREVIEWS FOR ANDROID STUDIO DESIGN / SPLIT VIEW
// ============================================================================

@Preview(showBackground = true)
@Composable
fun ModeSelectorPreview() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00E676),
            surface = Color(0xFF1E1E1E)
        )
    ) {
        Surface(color = Color(0xFF121212), modifier = Modifier.padding(16.dp)) {
            ModeSelectorCard(
                selectedMode = MirrorConnectionMode.INFRASTRUCTURE_MICE,
                onModeSelected = {}
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StatusBannerPreview() {
    MaterialTheme {
        Surface(color = Color(0xFF121212), modifier = Modifier.padding(16.dp)) {
            StatusBanner(
                isWifiConnected = true,
                ssid = "Home_WiFi_5G",
                isP2pReady = true,
                p2pState = "ENABLED",
                isDiscoveryActive = true
            )
        }
    }
}

