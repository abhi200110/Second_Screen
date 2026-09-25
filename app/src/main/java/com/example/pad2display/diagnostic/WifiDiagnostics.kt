package com.example.pad2display.diagnostic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class WifiDiagnosticData(
    val isWifiEnabled: Boolean,
    val isConnected: Boolean,
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val linkSpeedMbps: Int,
    val frequencyMhz: Int,
    val wifiStandard: String,
    val networkCapabilitiesSummary: String,
    val interfaces: List<NetworkInterfaceData>
)

data class NetworkInterfaceData(
    val name: String,
    val displayName: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isPointToPoint: Boolean,
    val supportsMulticast: Boolean,
    val mtu: Int,
    val ipv4Addresses: List<String>,
    val ipv6Addresses: List<String>
)

object WifiDiagnosticsProvider {

    fun getDiagnostics(context: Context): WifiDiagnosticData {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val isWifiEnabled = wifiManager?.isWifiEnabled ?: false
        val activeNetwork = connectivityManager?.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        val isConnectedToWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        @Suppress("DEPRECATION")
        val wifiInfo: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps != null) {
            caps.transportInfo as? WifiInfo ?: wifiManager?.connectionInfo
        } else {
            wifiManager?.connectionInfo
        }

        val ssid = wifiInfo?.ssid?.replace("\"", "") ?: "N/A"
        val bssid = wifiInfo?.bssid ?: "N/A"
        val rssi = wifiInfo?.rssi ?: -127
        val linkSpeed = wifiInfo?.linkSpeed ?: -1
        val freq = wifiInfo?.frequency ?: 0

        val wifiStandard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifiInfo != null) {
            when (wifiInfo.wifiStandard) {
                ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (802.11ax)"
                ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
                ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
                ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7 (802.11be)"
                ScanResult.WIFI_STANDARD_LEGACY -> "Legacy (802.11a/b/g)"
                else -> "Standard code: ${wifiInfo.wifiStandard}"
            }
        } else {
            if (freq > 5000) "5 GHz / 6 GHz" else if (freq > 2400) "2.4 GHz" else "Unknown"
        }

        val capsSummary = buildString {
            if (caps == null) {
                append("No active network capabilities found")
            } else {
                append("Internet: ").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                append(" | Validated: ").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                append(" | Downstream: ").append(caps.linkDownstreamBandwidthKbps).append(" kbps")
                append(" | Upstream: ").append(caps.linkUpstreamBandwidthKbps).append(" kbps")
            }
        }

        val interfaceList = getNetworkInterfaces()

        return WifiDiagnosticData(
            isWifiEnabled = isWifiEnabled,
            isConnected = isConnectedToWifi,
            ssid = ssid,
            bssid = bssid,
            rssiDbm = rssi,
            linkSpeedMbps = linkSpeed,
            frequencyMhz = freq,
            wifiStandard = wifiStandard,
            networkCapabilitiesSummary = capsSummary,
            interfaces = interfaceList
        )
    }

    private fun getNetworkInterfaces(): List<NetworkInterfaceData> {
        val result = mutableListOf<NetworkInterfaceData>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val ipv4List = mutableListOf<String>()
                val ipv6List = mutableListOf<String>()

                for (addr in Collections.list(intf.inetAddresses)) {
                    if (addr is Inet4Address) {
                        ipv4List.add(addr.hostAddress ?: "")
                    } else {
                        // Strip zone ID from IPv6
                        val host = addr.hostAddress ?: ""
                        val cleaned = host.substringBefore('%')
                        ipv6List.add(cleaned)
                    }
                }

                result.add(
                    NetworkInterfaceData(
                        name = intf.name,
                        displayName = intf.displayName,
                        isUp = try { intf.isUp } catch (_: Exception) { false },
                        isLoopback = try { intf.isLoopback } catch (_: Exception) { false },
                        isPointToPoint = try { intf.isPointToPoint } catch (_: Exception) { false },
                        supportsMulticast = try { intf.supportsMulticast() } catch (_: Exception) { false },
                        mtu = try { intf.mtu } catch (_: Exception) { 0 },
                        ipv4Addresses = ipv4List,
                        ipv6Addresses = ipv6List
                    )
                )
            }
        } catch (_: Exception) {
            // Ignore interface enumeration exceptions
        }
        return result.sortedWith(
            compareByDescending<NetworkInterfaceData> { it.name.startsWith("wlan") }
                .thenByDescending { it.name.startsWith("p2p") }
                .thenBy { it.name }
        )
    }
}
