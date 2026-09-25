package com.example.pad2display.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

object DiagnosticExporter {

    fun generateReport(
        deviceData: DeviceDiagnosticData,
        wifiData: WifiDiagnosticData,
        p2pData: P2pDiagnosticData,
        permissionsMap: Map<String, Boolean>
    ): String {
        return buildString {
            appendLine("=======================================================")
            appendLine("  ONEPLUS PAD 2 WIRELESS DISPLAY - DIAGNOSTIC REPORT  ")
            appendLine("=======================================================")
            appendLine("Generated At: ${java.util.Date()}")
            appendLine()

            appendLine("--- [1. DEVICE & OS HARDWARE] ---")
            appendLine("Manufacturer: ${deviceData.manufacturer}")
            appendLine("Model: ${deviceData.model} (Device: ${deviceData.device}, Board: ${deviceData.board}, Hardware: ${deviceData.hardware})")
            appendLine("Device Name: ${deviceData.deviceName}")
            appendLine("Android Version: ${deviceData.androidVersion} (API Level: ${deviceData.apiLevel})")
            appendLine("Security Patch: ${deviceData.securityPatch}")
            appendLine("OxygenOS/ColorOS Build: ${deviceData.oxygenOsVersion}")
            appendLine()

            appendLine("--- [2. DISPLAY METRICS] ---")
            appendLine("Resolution: ${deviceData.displayMetrics.widthPixels} x ${deviceData.displayMetrics.heightPixels}")
            appendLine("Current Refresh Rate: ${deviceData.displayMetrics.refreshRateHz} Hz")
            appendLine("Supported Refresh Rates: ${deviceData.displayMetrics.supportedRefreshRates.joinToString(", ")} Hz")
            appendLine("Screen Density: ${deviceData.displayMetrics.densityDpi} dpi")
            appendLine("HDR Supported: ${deviceData.displayMetrics.isHdrSupported}")
            appendLine()

            appendLine("--- [3. HARDWARE VIDEO DECODERS (MediaCodec)] ---")
            if (deviceData.videoCodecCapabilities.isEmpty()) {
                appendLine("No AVC/HEVC decoders found.")
            } else {
                for (codec in deviceData.videoCodecCapabilities) {
                    appendLine("• ${codec.codecName} [${codec.mimeType}]")
                    appendLine("    Hardware Accelerated: ${codec.isHardwareAccelerated}")
                    appendLine("    Max Resolution: ${codec.maxWidth} x ${codec.maxHeight} @ ${codec.maxFramerate} fps")
                }
            }
            appendLine()

            appendLine("--- [4. WI-FI SUBSYSTEM & NETWORK INTERFACES] ---")
            appendLine("Wi-Fi Enabled: ${wifiData.isWifiEnabled}")
            appendLine("Wi-Fi Connected: ${wifiData.isConnected}")
            appendLine("Current SSID: ${wifiData.ssid}")
            appendLine("BSSID: ${wifiData.bssid}")
            appendLine("Signal RSSI: ${wifiData.rssiDbm} dBm")
            appendLine("Link Speed: ${wifiData.linkSpeedMbps} Mbps")
            appendLine("Frequency: ${wifiData.frequencyMhz} MHz (${wifiData.wifiStandard})")
            appendLine("Active Capabilities: ${wifiData.networkCapabilitiesSummary}")
            appendLine("Network Interfaces:")
            for (intf in wifiData.interfaces) {
                appendLine("  [${intf.name}] Up: ${intf.isUp}, Multicast: ${intf.supportsMulticast}, MTU: ${intf.mtu}")
                if (intf.ipv4Addresses.isNotEmpty()) {
                    appendLine("    IPv4: ${intf.ipv4Addresses.joinToString(", ")}")
                }
                if (intf.ipv6Addresses.isNotEmpty()) {
                    appendLine("    IPv6: ${intf.ipv6Addresses.joinToString(", ")}")
                }
            }
            appendLine()

            appendLine("--- [5. WI-FI DIRECT (P2P) SUBSYSTEM] ---")
            appendLine("Feature PackageManager.FEATURE_WIFI_DIRECT: ${p2pData.hasWifiDirectFeature}")
            appendLine("WifiP2pManager Available: ${p2pData.isP2pManagerAvailable}")
            appendLine("P2P State: ${p2pData.p2pState}")
            appendLine("Discovery Active: ${p2pData.isDiscoveryActive}")
            appendLine("This Device Name: ${p2pData.thisDeviceName}")
            appendLine("This Device MAC/Address: ${p2pData.thisDeviceAddress}")
            appendLine("This Device Status: ${p2pData.thisDeviceStatus}")
            appendLine("Discovered Peer Count: ${p2pData.discoveredPeerCount}")
            if (p2pData.peers.isEmpty()) {
                appendLine("  No peers currently discovered.")
            } else {
                for (peer in p2pData.peers) {
                    appendLine("  • ${peer.deviceName} (${peer.deviceAddress}) [Type: ${peer.primaryDeviceType}, Status: ${peer.status}, IsGO: ${peer.isGroupOwner}]")
                }
            }
            appendLine("Connection Info: ${p2pData.connectionInfoSummary}")
            appendLine("Group Info: ${p2pData.groupInfoSummary}")
            appendLine()

            appendLine("--- [6. WI-FI DISPLAY (WFD) FRAMEWORK INSPECTION] ---")
            val wfd = p2pData.wfdFrameworkInspection
            appendLine("Class 'android.net.wifi.p2p.WifiP2pWfdInfo' Present: ${wfd.isWifiP2pWfdInfoClassPresent}")
            appendLine("Method 'setWfdInfo' on WifiP2pManager: ${wfd.isSetWfdInfoMethodPresent}")
            appendLine("WfdInfo Methods: ${wfd.wfdInfoClassMethods.joinToString(", ")}")
            appendLine("Has 'android.permission.CONFIGURE_WIFI_DISPLAY': ${wfd.hasConfigureWifiDisplayPermission}")
            appendLine("Permission Note: ${wfd.permissionProtectionLevelNote}")
            appendLine("Framework Probe Result: ${wfd.setWfdInfoTestResult}")
            appendLine()

            appendLine("--- [7. RUNTIME PERMISSIONS STATUS] ---")
            for ((perm, isGranted) in permissionsMap) {
                appendLine("  $perm: ${if (isGranted) "GRANTED" else "DENIED"}")
            }
            appendLine("=======================================================")
        }
    }

    fun copyToClipboard(context: Context, report: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Pad2_Diagnostics", report)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, "Diagnostic report copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    fun shareReport(context: Context, report: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, report)
            putExtra(Intent.EXTRA_SUBJECT, "OnePlus Pad 2 WFD Diagnostic Report")
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Export Diagnostic Report")
        context.startActivity(shareIntent)
    }
}
