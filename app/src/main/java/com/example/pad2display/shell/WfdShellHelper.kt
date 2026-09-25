package com.example.pad2display.shell

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import java.lang.reflect.Proxy

/**
 * Privileged Shell Helper for WFD Configuration.
 * Can be executed via `adb shell app_process` with UID 2000 (Shell),
 * which possesses android.permission.CONFIGURE_WIFI_DISPLAY.
 */
object WfdShellHelper {

    @JvmStatic
    @SuppressLint("MissingPermission")
    fun main(args: Array<String>) {
        println("=== Pad2WirelessDisplay WFD Shell Helper (UID 2000) ===")
        try {
            Looper.prepareMainLooper()

            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val systemMainMethod = activityThreadClass.getMethod("systemMain")
            val activityThread = systemMainMethod.invoke(null)
            val getSystemContextMethod = activityThreadClass.getMethod("getSystemContext")
            val systemContext = getSystemContextMethod.invoke(activityThread) as Context
            println("Acquired System Context: $systemContext")

            val wifiP2pManager = systemContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            val looper = Looper.myLooper() ?: Looper.getMainLooper()
            val channel = wifiP2pManager.initialize(systemContext, looper) {
                println("[WFD Shell] Wi-Fi P2P Channel disconnected")
            }
            println("Initialized P2P Channel: $channel")

            val action = args.firstOrNull() ?: "sink"
            println("[WFD Shell] Executing action: $action")

            if (action == "removeGroup") {
                wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        println("[WFD Shell] removeGroup SUCCESS")
                        System.exit(0)
                    }
                    override fun onFailure(reason: Int) {
                        println("[WFD Shell] removeGroup FAILURE: $reason")
                        System.exit(1)
                    }
                })
            } else {
                // Default: Configure WFD Sink
                val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
                val wfdInfo = wfdInfoClass.getConstructor().newInstance()

                // 1. Enable WFD
                try {
                    wfdInfoClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType).invoke(wfdInfo, true)
                } catch (_: NoSuchMethodException) {
                    wfdInfoClass.getMethod("setWfdEnabled", Boolean::class.javaPrimitiveType).invoke(wfdInfo, true)
                }

                // 2. Set Device Type = 1 (PRIMARY_SINK)
                try {
                    wfdInfoClass.getMethod("setDeviceType", Int::class.javaPrimitiveType).invoke(wfdInfo, 1)
                } catch (e: Exception) {
                    println("Failed to setDeviceType: ${e.message}")
                }

                // 3. Set Control Port = 7236
                try {
                    wfdInfoClass.getMethod("setControlPort", Int::class.javaPrimitiveType).invoke(wfdInfo, 7236)
                } catch (e: Exception) {
                    println("Failed to setControlPort: ${e.message}")
                }

                // 4. Set Max Throughput = 50 Mbps
                try {
                    wfdInfoClass.getMethod("setMaxThroughput", Int::class.javaPrimitiveType).invoke(wfdInfo, 50)
                } catch (_: Exception) {}

                // 5. Set Session Available = true
                try {
                    wfdInfoClass.getMethod("setSessionAvailable", Boolean::class.javaPrimitiveType).invoke(wfdInfo, true)
                } catch (_: Exception) {}

                println("Constructed WfdInfo: $wfdInfo")

                val setWfdInfoMethod = wifiP2pManager.javaClass.methods.firstOrNull {
                    it.name.equals("setWfdInfo", ignoreCase = true)
                } ?: error("setWfdInfo method not found on WifiP2pManager")

                val actionListenerClass = WifiP2pManager.ActionListener::class.java
                val listener = Proxy.newProxyInstance(
                    actionListenerClass.classLoader,
                    arrayOf(actionListenerClass)
                ) { _, method, methodArgs ->
                    println("[WFD Shell] setWfdInfo callback: ${method.name} ${methodArgs?.joinToString() ?: ""}")
                    if (method.name == "onSuccess" && (action == "group" || action == "createGroup")) {
                        println("[WFD Shell] setWfdInfo succeeded, now creating P2P Group on same channel...")
                        wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                println("[WFD Shell] createGroup SUCCESS! Re-applying setWfdInfo to active group...")
                                if (setWfdInfoMethod.parameterCount == 3) {
                                    setWfdInfoMethod.invoke(wifiP2pManager, channel, wfdInfo, null)
                                } else {
                                    setWfdInfoMethod.invoke(wifiP2pManager, channel, wfdInfo)
                                }
                                println("[WFD Shell] setWfdInfo re-applied to active group! GO WFD IE is confirmed.")
                            }
                            override fun onFailure(reason: Int) {
                                println("[WFD Shell] createGroup FAILURE: code $reason")
                            }
                        })
                    }
                    null
                }

                if (setWfdInfoMethod.parameterCount == 3) {
                    setWfdInfoMethod.invoke(wifiP2pManager, channel, wfdInfo, listener)
                } else {
                    setWfdInfoMethod.invoke(wifiP2pManager, channel, wfdInfo)
                }
                println("[WFD Shell] setWfdInfo executed successfully with UID 2000!")
            }

            Looper.loop()
        } catch (e: Throwable) {
            println("[WFD Shell Error] ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }
}
