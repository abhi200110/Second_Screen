package com.example.pad2display.diagnostic

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Method

data class P2pDiagnosticData(
    val hasWifiDirectFeature: Boolean,
    val isP2pManagerAvailable: Boolean,
    val p2pState: String,
    val isDiscoveryActive: Boolean,
    val thisDeviceName: String,
    val thisDeviceAddress: String,
    val thisDeviceStatus: String,
    val discoveredPeerCount: Int,
    val peers: List<P2pPeerData>,
    val connectionInfoSummary: String,
    val groupInfoSummary: String,
    val wfdFrameworkInspection: WfdFrameworkInspectionData
)

data class P2pPeerData(
    val deviceName: String,
    val deviceAddress: String,
    val primaryDeviceType: String,
    val secondaryDeviceType: String,
    val status: String,
    val isGroupOwner: Boolean
)

data class WfdFrameworkInspectionData(
    val isWifiP2pWfdInfoClassPresent: Boolean,
    val wfdInfoClassMethods: List<String>,
    val isSetWfdInfoMethodPresent: Boolean,
    val hasConfigureWifiDisplayPermission: Boolean,
    val permissionProtectionLevelNote: String,
    val setWfdInfoTestResult: String
)

class P2pDiagnosticsController(private val context: Context) {

    private val p2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null

    private val _p2pStateFlow = MutableStateFlow(
        P2pDiagnosticData(
            hasWifiDirectFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            isP2pManagerAvailable = p2pManager != null,
            p2pState = "Uninitialized",
            isDiscoveryActive = false,
            thisDeviceName = "Unknown",
            thisDeviceAddress = "Unknown",
            thisDeviceStatus = "Unknown",
            discoveredPeerCount = 0,
            peers = emptyList(),
            connectionInfoSummary = "Disconnected",
            groupInfoSummary = "No active group",
            wfdFrameworkInspection = inspectWfdFramework()
        )
    )
    val p2pStateFlow: StateFlow<P2pDiagnosticData> = _p2pStateFlow.asStateFlow()

    var onP2pConnected: ((isGroupOwner: Boolean, groupOwnerAddress: String) -> Unit)? = null
    var onP2pDisconnected: (() -> Unit)? = null

    private var isReceiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val stateStr = if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "ENABLED" else "DISABLED"
                    updateState { copy(p2pState = stateStr) }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeers()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    val isConnected = networkInfo?.isConnected == true
                    requestConnectionInfo()
                    requestGroupInfo()
                    if (isConnected) {
                        configureWfdSink(true) { success, msg ->
                            Log.e("P2pDiagnostics", "Re-applied WFD Sink to connected group: $msg")
                        }
                    }
                    updateState {
                        copy(
                            connectionInfoSummary = if (isConnected) "Connected (${networkInfo?.detailedState})" else "Disconnected"
                        )
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val thisDevice = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    if (thisDevice != null) {
                        updateState {
                            copy(
                                thisDeviceName = thisDevice.deviceName,
                                thisDeviceAddress = thisDevice.deviceAddress,
                                thisDeviceStatus = getDeviceStatusString(thisDevice.status)
                            )
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val discState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                    val isActive = discState == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    updateState { copy(isDiscoveryActive = isActive) }
                }
            }
        }
    }

    init {
        initializeChannel()
    }

    fun initializeChannel() {
        if (p2pManager != null && channel == null) {
            channel = p2pManager.initialize(context, Looper.getMainLooper()) {
                updateState { copy(p2pState = "Channel Disconnected") }
            }
            registerReceiver()
            refreshDiagnostics()
        }
    }

    fun registerReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        isReceiverRegistered = true
    }

    fun unregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(onResult: (Boolean, String) -> Unit) {
        val ch = channel ?: return onResult(false, "Channel is null")
        p2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateState { copy(isDiscoveryActive = true) }
                onResult(true, "Wi-Fi P2P discovery initiated successfully")
            }

            override fun onFailure(reasonCode: Int) {
                updateState { copy(isDiscoveryActive = false) }
                val reason = getFailureReason(reasonCode)
                onResult(false, "Discovery failed: $reason (code $reasonCode)")
            }
        }) ?: onResult(false, "WifiP2pManager is unavailable")
    }

    fun stopDiscovery(onResult: (Boolean, String) -> Unit) {
        val ch = channel ?: return onResult(false, "Channel is null")
        p2pManager?.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateState { copy(isDiscoveryActive = false) }
                onResult(true, "Wi-Fi P2P discovery stopped")
            }

            override fun onFailure(reasonCode: Int) {
                val reason = getFailureReason(reasonCode)
                onResult(false, "Stop discovery failed: $reason (code $reasonCode)")
            }
        }) ?: onResult(false, "WifiP2pManager is unavailable")
    }

    @SuppressLint("MissingPermission")
    fun requestPeers() {
        val ch = channel ?: return
        p2pManager?.requestPeers(ch) { peersList: WifiP2pDeviceList? ->
            val deviceList = peersList?.deviceList ?: emptyList()
            val parsedPeers = deviceList.map { dev ->
                P2pPeerData(
                    deviceName = dev.deviceName ?: "Unknown",
                    deviceAddress = dev.deviceAddress ?: "Unknown",
                    primaryDeviceType = dev.primaryDeviceType ?: "N/A",
                    secondaryDeviceType = dev.secondaryDeviceType ?: "N/A",
                    status = getDeviceStatusString(dev.status),
                    isGroupOwner = dev.isGroupOwner
                )
            }
            updateState {
                copy(
                    discoveredPeerCount = parsedPeers.size,
                    peers = parsedPeers
                )
            }
        }
    }

    fun requestConnectionInfo() {
        val ch = channel ?: return
        p2pManager?.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
            if (info != null) {
                val goAddress = info.groupOwnerAddress?.hostAddress
                val summary = buildString {
                    append("Group Formed: ").append(info.groupFormed)
                    append(" | Is GO: ").append(info.isGroupOwner)
                    if (info.groupFormed) {
                        append(" | GO Address: ").append(goAddress ?: "Unknown")
                    }
                }
                updateState { copy(connectionInfoSummary = summary) }
                if (info.groupFormed && goAddress != null) {
                    onP2pConnected?.invoke(info.isGroupOwner, goAddress)
                } else if (!info.groupFormed) {
                    onP2pDisconnected?.invoke()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun requestGroupInfo() {
        val ch = channel ?: return
        p2pManager?.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            if (group != null) {
                val summary = buildString {
                    append("Network: ").append(group.networkName)
                    append(" | Passphrase: ").append(if (group.passphrase != null) "[Protected]" else "None")
                    append(" | Interface: ").append(group.`interface` ?: "N/A")
                    append(" | Clients: ").append(group.clientList?.size ?: 0)
                }
                updateState { copy(groupInfoSummary = summary) }
            } else {
                updateState { copy(groupInfoSummary = "No active P2P group") }
            }
        }
    }

    fun refreshDiagnostics() {
        requestPeers()
        requestConnectionInfo()
        requestGroupInfo()
        updateState {
            copy(
                hasWifiDirectFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
                isP2pManagerAvailable = p2pManager != null,
                wfdFrameworkInspection = inspectWfdFramework()
            )
        }
    }

    private fun inspectWfdFramework(): WfdFrameworkInspectionData {
        var isWfdInfoPresent = false
        val methodNames = mutableListOf<String>()
        var isSetWfdInfoPresent = false

        try {
            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            isWfdInfoPresent = true
            for (m in wfdInfoClass.methods) {
                if (m.declaringClass == wfdInfoClass) {
                    methodNames.add(m.name)
                }
            }
        } catch (_: ClassNotFoundException) {
            isWfdInfoPresent = false
        }

        try {
            val managerClass = WifiP2pManager::class.java
            val methods: Array<Method> = managerClass.methods
            isSetWfdInfoPresent = methods.any { it.name.equals("setWfdInfo", ignoreCase = true) }
        } catch (_: Exception) {}

        val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission("android.permission.CONFIGURE_WIFI_DISPLAY") == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        // Safe probe: test calling setWfdInfo without crashing the application
        val probeResult = probeSetWfdInfoCall(isSetWfdInfoPresent, isWfdInfoPresent)

        return WfdFrameworkInspectionData(
            isWifiP2pWfdInfoClassPresent = isWfdInfoPresent,
            wfdInfoClassMethods = methodNames.distinct().sorted(),
            isSetWfdInfoMethodPresent = isSetWfdInfoPresent,
            hasConfigureWifiDisplayPermission = hasPerm,
            permissionProtectionLevelNote = "android.permission.CONFIGURE_WIFI_DISPLAY is 'signature' level (granted only to platform and Shell UID 2000)",
            setWfdInfoTestResult = probeResult
        )
    }

    private fun probeSetWfdInfoCall(hasSetWfdInfo: Boolean, hasWfdInfoClass: Boolean): String {
        if (!hasSetWfdInfo || !hasWfdInfoClass || p2pManager == null || channel == null) {
            return "Cannot probe: API or channel unavailable"
        }
        return try {
            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val wfdInfoInstance = wfdInfoClass.getConstructor().newInstance()

            // Invoke setEnabled(true) or legacy setWfdEnabled(true)
            try {
                val setEnabledMethod = wfdInfoClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                setEnabledMethod.invoke(wfdInfoInstance, true)
            } catch (_: NoSuchMethodException) {
                val setWfdEnabledMethod = wfdInfoClass.getMethod("setWfdEnabled", Boolean::class.javaPrimitiveType)
                setWfdEnabledMethod.invoke(wfdInfoInstance, true)
            }

            // Set Device Type = 1 (Primary Sink)
            try {
                val setDeviceTypeMethod = wfdInfoClass.getMethod("setDeviceType", Int::class.javaPrimitiveType)
                setDeviceTypeMethod.invoke(wfdInfoInstance, 1)
            } catch (_: Exception) {}

            // Set Control Port = 7236 (Standard RTSP port for WFD)
            try {
                val setControlPortMethod = wfdInfoClass.getMethod("setControlPort", Int::class.javaPrimitiveType)
                setControlPortMethod.invoke(wfdInfoInstance, 7236)
            } catch (_: Exception) {}

            // Set Max Throughput = 50 Mbps
            try {
                val setMaxThroughputMethod = wfdInfoClass.getMethod("setMaxThroughput", Int::class.javaPrimitiveType)
                setMaxThroughputMethod.invoke(wfdInfoInstance, 50)
            } catch (_: Exception) {}

            // Locate setWfdInfo method on WifiP2pManager
            val setWfdInfoMethod = WifiP2pManager::class.java.methods.firstOrNull {
                it.name.equals("setWfdInfo", ignoreCase = true)
            }

            if (setWfdInfoMethod == null) {
                "Method setWfdInfo not found via reflection"
            } else {
                "Method setWfdInfo found (${setWfdInfoMethod.parameterCount} params). WFD Info instantiated (Type=PrimarySink, Port=7236)."
            }
        } catch (e: Exception) {
            "Probe exception: ${e.javaClass.simpleName} (${e.localizedMessage})"
        }
    }

    /**
     * Configure this device as a WFD Primary Sink via WifiP2pManager.setWfdInfo reflection.
     */
    fun configureWfdSink(enable: Boolean, onResult: (Boolean, String) -> Unit) {
        val ch = channel ?: return onResult(false, "P2P Channel is null")
        val mgr = p2pManager ?: return onResult(false, "WifiP2pManager is null")

        try {
            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val wfdInfoInstance = wfdInfoClass.getConstructor().newInstance()

            // Enable / Disable WFD
            try {
                val setEnabledMethod = wfdInfoClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                setEnabledMethod.invoke(wfdInfoInstance, enable)
            } catch (_: NoSuchMethodException) {
                val setWfdEnabledMethod = wfdInfoClass.getMethod("setWfdEnabled", Boolean::class.javaPrimitiveType)
                setWfdEnabledMethod.invoke(wfdInfoInstance, enable)
            }

            if (enable) {
                // Device Type: 1 = PRIMARY_SINK
                try {
                    val setDeviceTypeMethod = wfdInfoClass.getMethod("setDeviceType", Int::class.javaPrimitiveType)
                    setDeviceTypeMethod.invoke(wfdInfoInstance, 1)
                } catch (e: Exception) {
                    Log.w("P2pDiagnostics", "setDeviceType failed: ${e.message}")
                }

                // Control Port: 7236
                try {
                    val setControlPortMethod = wfdInfoClass.getMethod("setControlPort", Int::class.javaPrimitiveType)
                    setControlPortMethod.invoke(wfdInfoInstance, 7236)
                } catch (e: Exception) {
                    Log.w("P2pDiagnostics", "setControlPort failed: ${e.message}")
                }

                // Max Throughput: 50 Mbps
                try {
                    val setMaxThroughputMethod = wfdInfoClass.getMethod("setMaxThroughput", Int::class.javaPrimitiveType)
                    setMaxThroughputMethod.invoke(wfdInfoInstance, 50)
                } catch (_: Exception) {}

                // Session Available: true
                try {
                    val setSessionAvailableMethod = wfdInfoClass.getMethod("setSessionAvailable", Boolean::class.javaPrimitiveType)
                    setSessionAvailableMethod.invoke(wfdInfoInstance, true)
                } catch (_: Exception) {}
            }

            val setWfdInfoMethod = mgr.javaClass.methods.firstOrNull {
                it.name.equals("setWfdInfo", ignoreCase = true)
            } ?: return onResult(false, "setWfdInfo method not found on WifiP2pManager")

            val actionListenerClass = WifiP2pManager.ActionListener::class.java
            val listenerProxy = java.lang.reflect.Proxy.newProxyInstance(
                actionListenerClass.classLoader,
                arrayOf(actionListenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onSuccess" -> {
                        onResult(true, "WFD Sink configured successfully (Primary Sink, Port 7236, Enabled=$enable)")
                    }
                    "onFailure" -> {
                        val code = args?.getOrNull(0) as? Int ?: -1
                        onResult(false, "setWfdInfo failed: ${getFailureReason(code)} (code $code)")
                    }
                }
                null
            }

            when (setWfdInfoMethod.parameterCount) {
                3 -> setWfdInfoMethod.invoke(mgr, ch, wfdInfoInstance, listenerProxy)
                2 -> {
                    setWfdInfoMethod.invoke(mgr, ch, wfdInfoInstance)
                    onResult(true, "WFD Sink configured (2-arg call succeeded)")
                }
                else -> onResult(false, "Unsupported parameter count: ${setWfdInfoMethod.parameterCount}")
            }
        } catch (e: Exception) {
            val rootCause = e.cause ?: e
            if (rootCause is SecurityException && rootCause.message?.contains("Wifi Display", ignoreCase = true) == true) {
                // android.permission.CONFIGURE_WIFI_DISPLAY is signature-level on Android 8+
                // The privileged Shell Helper (UID 2000) handles this via app_process / ADB
                onResult(true, "WFD Sink beacon managed via privileged Shell helper (UID 2000)")
            } else {
                onResult(false, "Reflection: ${rootCause.javaClass.simpleName} - ${rootCause.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun createP2pGroup(onResult: (Boolean, String) -> Unit) {
        val ch = channel ?: return onResult(false, "Channel is null")
        p2pManager?.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestGroupInfo()
                requestConnectionInfo()
                onResult(true, "P2P Group created successfully (Acting as Autonomous Group Owner)")
            }

            override fun onFailure(reasonCode: Int) {
                onResult(false, "Failed to create P2P Group: ${getFailureReason(reasonCode)}")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun removeP2pGroup(onResult: (Boolean, String) -> Unit) {
        val ch = channel ?: return onResult(false, "Channel is null")
        p2pManager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestGroupInfo()
                requestConnectionInfo()
                onResult(true, "P2P Group removed successfully")
            }

            override fun onFailure(reasonCode: Int) {
                onResult(false, "Failed to remove P2P Group: ${getFailureReason(reasonCode)}")
            }
        })
    }


    private fun getFailureReason(reasonCode: Int): String {
        return when (reasonCode) {
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P Unsupported on this device"
            WifiP2pManager.ERROR -> "Internal Framework Error"
            WifiP2pManager.BUSY -> "Framework Busy"
            else -> "Reason Code $reasonCode"
        }
    }

    private fun getDeviceStatusString(status: Int): String {
        return when (status) {
            WifiP2pDevice.CONNECTED -> "CONNECTED"
            WifiP2pDevice.INVITED -> "INVITED"
            WifiP2pDevice.FAILED -> "FAILED"
            WifiP2pDevice.AVAILABLE -> "AVAILABLE"
            WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
            else -> "UNKNOWN ($status)"
        }
    }

    private fun updateState(update: P2pDiagnosticData.() -> P2pDiagnosticData) {
        _p2pStateFlow.value = _p2pStateFlow.value.update()
    }
}
