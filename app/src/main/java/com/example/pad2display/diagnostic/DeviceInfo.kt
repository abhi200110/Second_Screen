package com.example.pad2display.diagnostic

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import java.io.BufferedReader
import java.io.InputStreamReader

data class DeviceDiagnosticData(
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val androidVersion: String,
    val apiLevel: Int,
    val securityPatch: String,
    val deviceName: String,
    val oxygenOsVersion: String,
    val displayMetrics: DisplayMetricsData,
    val videoCodecCapabilities: List<CodecInfoData>
)

data class DisplayMetricsData(
    val widthPixels: Int,
    val heightPixels: Int,
    val refreshRateHz: Float,
    val supportedRefreshRates: List<Float>,
    val densityDpi: Int,
    val isHdrSupported: Boolean
)

data class CodecInfoData(
    val codecName: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFramerate: Int
)

object DeviceInfoProvider {

    fun getDiagnostics(context: Context): DeviceDiagnosticData {
        val deviceName = try {
            Settings.Global.getString(context.contentResolver, "device_name")
                ?: Settings.System.getString(context.contentResolver, "device_name")
                ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        } catch (_: Exception) {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        }

        val oxygenOsVersion = detectOxygenOsVersion()
        val displayMetrics = getDisplayMetrics(context)
        val codecCapabilities = getVideoCodecCapabilities()

        return DeviceDiagnosticData(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            deviceName = deviceName,
            oxygenOsVersion = oxygenOsVersion,
            displayMetrics = displayMetrics,
            videoCodecCapabilities = codecCapabilities
        )
    }

    private fun detectOxygenOsVersion(): String {
        val candidateProps = listOf(
            "ro.build.version.oplusrom",
            "ro.rom.version",
            "ro.build.version.ota",
            "ro.oxygen.version",
            "ro.build.display.id"
        )

        for (prop in candidateProps) {
            val value = getSystemProperty(prop)
            if (value.isNotBlank()) {
                return "$prop: $value"
            }
        }
        return "Not detected (Standard Android Build: ${Build.DISPLAY})"
    }

    private fun getSystemProperty(key: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readLine()?.trim() ?: ""
            }
        } catch (_: Exception) {
            try {
                val clazz = Class.forName("android.os.SystemProperties")
                val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
                getMethod.invoke(null, key, "") as? String ?: ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun getDisplayMetrics(context: Context): DisplayMetricsData {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay
        }

        val width = display?.mode?.physicalWidth ?: context.resources.displayMetrics.widthPixels
        val height = display?.mode?.physicalHeight ?: context.resources.displayMetrics.heightPixels
        val refreshRate = display?.mode?.refreshRate ?: 60f
        val supportedRates = display?.supportedModes?.map { it.refreshRate }?.distinct()?.sorted()
            ?: listOf(refreshRate)
        val densityDpi = context.resources.displayMetrics.densityDpi

        val isHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            display?.isHdr == true
        } else {
            false
        }

        return DisplayMetricsData(
            widthPixels = width,
            heightPixels = height,
            refreshRateHz = refreshRate,
            supportedRefreshRates = supportedRates,
            densityDpi = densityDpi,
            isHdrSupported = isHdr
        )
    }

    private fun getVideoCodecCapabilities(): List<CodecInfoData> {
        val list = mutableListOf<CodecInfoData>()
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val targetMimes = listOf("video/avc", "video/hevc")

        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            for (mime in targetMimes) {
                if (info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) {
                    try {
                        val caps = info.getCapabilitiesForType(mime)
                        val videoCaps = caps.videoCapabilities

                        val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            info.isHardwareAccelerated
                        } else {
                            !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                        }

                        val maxWidth = videoCaps?.supportedWidths?.upper ?: 0
                        val maxHeight = videoCaps?.supportedHeights?.upper ?: 0
                        val maxFps = videoCaps?.supportedFrameRates?.upper?.toInt() ?: 0

                        list.add(
                            CodecInfoData(
                                codecName = info.name,
                                mimeType = mime,
                                isHardwareAccelerated = isHw,
                                maxWidth = maxWidth,
                                maxHeight = maxHeight,
                                maxFramerate = maxFps
                            )
                        )
                    } catch (_: Exception) {
                        // Ignore codecs that fail to query capabilities
                    }
                }
            }
        }
        return list.sortedWith(compareByDescending<CodecInfoData> { it.isHardwareAccelerated }.thenBy { it.codecName })
    }
}
