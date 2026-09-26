package com.example.pad2display.mice

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

private const val TAG = "MiceDiscoveryService"
const val MICE_TCP_PORT = 7250
const val MICE_SERVICE_TYPE_DISPLAY = "_display._tcp"
const val MICE_SERVICE_TYPE_MIRACAST = "_miracast._tcp"

data class MiceDiscoveryState(
    val isAdvertising: Boolean = false,
    val serviceName: String = "OnePlus Pad 2",
    val containerId: String = "",
    val registeredDisplay: Boolean = false,
    val registeredMiracast: Boolean = false,
    val lastError: String? = null
)

/**
 * Implements [MS-MICE] mDNS / DNS-SD service registration according to:
 * - [MS-MICE] Section 3.1.3: Initialization
 * - Service instance name: <instance name>._display._tcp.local
 * - Port: 7250
 * - TXT Record: container_id=<GUID>
 */
class MiceDiscoveryService(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val prefs = context.getSharedPreferences("mice_prefs", Context.MODE_PRIVATE)
    private val containerUuid: String = prefs.getString("container_id", null) ?: run {
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("container_id", newId).apply()
        newId
    }

    private val _discoveryState = MutableStateFlow(
        MiceDiscoveryState(containerId = containerUuid)
    )
    val discoveryState: StateFlow<MiceDiscoveryState> = _discoveryState.asStateFlow()

    private var displayRegistrationListener: NsdManager.RegistrationListener? = null
    private var miracastRegistrationListener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun startAdvertising(deviceName: String = "OnePlus Pad 2") {
        if (_discoveryState.value.isAdvertising) {
            log("Already advertising MS-MICE services")
            return
        }

        // Acquire MulticastLock to ensure mDNS packets are processed even with Wi-Fi power saving
        try {
            multicastLock = wifiManager.createMulticastLock("Pad2MiceMulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            log("Acquired Wi-Fi MulticastLock")
        } catch (e: Exception) {
            log("Warning: Failed to acquire MulticastLock: ${e.message}")
        }

        _discoveryState.value = _discoveryState.value.copy(
            isAdvertising = true,
            serviceName = deviceName,
            containerId = containerUuid,
            lastError = null
        )

        val formattedContainerId = if (containerUuid.startsWith("{") && containerUuid.endsWith("}")) {
            containerUuid
        } else {
            "{$containerUuid}"
        }

        // 1. Register _display._tcp per [MS-MICE] 3.1.3
        val displayServiceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = MICE_SERVICE_TYPE_DISPLAY
            port = MICE_TCP_PORT
            setAttribute("container_id", formattedContainerId)
            setAttribute("wfd_ctrl_port", "7236")
        }

        displayRegistrationListener = createRegistrationListener(MICE_SERVICE_TYPE_DISPLAY) { registered ->
            _discoveryState.value = _discoveryState.value.copy(registeredDisplay = registered)
        }

        try {
            nsdManager.registerService(
                displayServiceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                displayRegistrationListener
            )
            log("Registered mDNS service $deviceName.$MICE_SERVICE_TYPE_DISPLAY on port $MICE_TCP_PORT")
        } catch (e: Exception) {
            log("Failed to register $MICE_SERVICE_TYPE_DISPLAY: ${e.message}")
            _discoveryState.value = _discoveryState.value.copy(lastError = e.message)
        }

        // 2. Also register _miracast._tcp for maximum Windows 10/11 compatibility
        val miracastServiceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = MICE_SERVICE_TYPE_MIRACAST
            port = MICE_TCP_PORT
            setAttribute("container_id", formattedContainerId)
            setAttribute("wfd_ctrl_port", "7236")
        }

        miracastRegistrationListener = createRegistrationListener(MICE_SERVICE_TYPE_MIRACAST) { registered ->
            _discoveryState.value = _discoveryState.value.copy(registeredMiracast = registered)
        }

        try {
            nsdManager.registerService(
                miracastServiceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                miracastRegistrationListener
            )
            log("Registered mDNS service $deviceName.$MICE_SERVICE_TYPE_MIRACAST on port $MICE_TCP_PORT")
        } catch (e: Exception) {
            log("Failed to register $MICE_SERVICE_TYPE_MIRACAST: ${e.message}")
        }
    }

    @Synchronized
    fun stopAdvertising() {
        if (!_discoveryState.value.isAdvertising) return

        displayRegistrationListener?.let {
            try {
                nsdManager.unregisterService(it)
                log("Unregistered $MICE_SERVICE_TYPE_DISPLAY")
            } catch (e: Exception) {
                log("Error unregistering $MICE_SERVICE_TYPE_DISPLAY: ${e.message}")
            }
        }
        displayRegistrationListener = null

        miracastRegistrationListener?.let {
            try {
                nsdManager.unregisterService(it)
                log("Unregistered $MICE_SERVICE_TYPE_MIRACAST")
            } catch (e: Exception) {
                log("Error unregistering $MICE_SERVICE_TYPE_MIRACAST: ${e.message}")
            }
        }
        miracastRegistrationListener = null

        try {
            multicastLock?.release()
        } catch (_: Exception) {}
        multicastLock = null

        _discoveryState.value = _discoveryState.value.copy(
            isAdvertising = false,
            registeredDisplay = false,
            registeredMiracast = false
        )
        log("Stopped MS-MICE mDNS advertising")
    }

    private fun createRegistrationListener(
        serviceType: String,
        onStatusChanged: (Boolean) -> Unit
    ) = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            val port = if (serviceInfo.port > 0) serviceInfo.port else MICE_TCP_PORT
            log("mDNS service registered successfully: ${serviceInfo.serviceName}.$serviceType (port $port)")
            onStatusChanged(true)
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            log("mDNS service registration failed for $serviceType (error code: $errorCode)")
            onStatusChanged(false)
            _discoveryState.value = _discoveryState.value.copy(lastError = "Registration error: $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            log("mDNS service unregistered: ${serviceInfo.serviceName}.$serviceType")
            onStatusChanged(false)
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            log("mDNS service unregistration failed for $serviceType (error code: $errorCode)")
            onStatusChanged(false)
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        println("[MS-MICE Discovery] $msg")
        onLog("[MICE Discovery] $msg")
    }
}
