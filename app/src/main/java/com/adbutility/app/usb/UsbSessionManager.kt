package com.adbutility.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.adbutility.app.usb.adb.AdbAuth
import com.adbutility.app.usb.adb.AdbConnection
import com.adbutility.app.usb.adb.AdbShellResult
import com.adbutility.app.usb.fastboot.FastbootConnection
import com.adbutility.app.usb.fastboot.FastbootResult
import com.adbutility.app.usb.transport.UsbBulkTransport
import java.io.InputStream

/**
 * Owns the current USB session with a target Android device: finds a device
 * exposing an ADB or Fastboot interface, requests permission, claims the
 * interface, and hands out either an [AdbConnection] or [FastbootConnection].
 */
class UsbSessionManager(private val context: Context) {

    enum class Mode { NONE, ADB, FASTBOOT }

    // Interface class/subclass/protocol used by Android's adbd and bootloader
    // (documented in AOSP; not vendor-specific to any one client).
    private val USB_CLASS_ADB = 0xFF
    private val ADB_SUBCLASS = 0x42
    private val ADB_PROTOCOL = 0x01
    private val FASTBOOT_PROTOCOL = 0x03

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val actionUsbPermission = "${context.packageName}.USB_PERMISSION"

    private var deviceConnection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null

    var mode: Mode = Mode.NONE
        private set
    var adbConnection: AdbConnection? = null
        private set
    var fastbootConnection: FastbootConnection? = null
        private set
    var maxDownloadSize: Long = 4L * 1024 * 1024
        private set

    val isConnected: Boolean
        get() = mode != Mode.NONE

    fun connect(onResult: (success: Boolean, message: String) -> Unit) {
        val candidate = findCandidateInterface()
        if (candidate == null) {
            onResult(false, "No ADB or Fastboot device found on USB. Check the OTG cable and target device.")
            return
        }

        val (device, iface, detectedMode) = candidate

        if (isConnected) disconnect()

        if (usbManager.hasPermission(device)) {
            openSession(device, iface, detectedMode, onResult)
        } else {
            requestPermission(device) { granted ->
                if (granted) {
                    openSession(device, iface, detectedMode, onResult)
                } else {
                    onResult(false, "USB permission was denied")
                }
            }
        }
    }

    @Synchronized
    fun disconnect() {
        try {
            claimedInterface?.let { deviceConnection?.releaseInterface(it) }
            deviceConnection?.close()
        } catch (_: Exception) {
            // best-effort cleanup
        }
        deviceConnection = null
        claimedInterface = null
        adbConnection = null
        fastbootConnection = null
        mode = Mode.NONE
    }

    fun status(): Map<String, Any> = mapOf(
        "connected" to isConnected,
        "mode" to mode.name.lowercase()
    )

    // ---------------------------------------------------------------

    private data class Candidate(val device: UsbDevice, val iface: UsbInterface, val mode: Mode)

    private fun findCandidateInterface(): Candidate? {
        for (device in usbManager.deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass != USB_CLASS_ADB || iface.interfaceSubclass != ADB_SUBCLASS) continue

                when (iface.interfaceProtocol) {
                    ADB_PROTOCOL -> return Candidate(device, iface, Mode.ADB)
                    FASTBOOT_PROTOCOL -> return Candidate(device, iface, Mode.FASTBOOT)
                }
            }
        }
        return null
    }

    private fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = PendingIntent.getBroadcast(context, device.deviceId, Intent(actionUsbPermission), flags)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != actionUsbPermission) return
                context.unregisterReceiver(this)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                callback(granted)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(actionUsbPermission), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(actionUsbPermission))
        }

        usbManager.requestPermission(device, intent)
    }

    private fun openSession(device: UsbDevice, iface: UsbInterface, detectedMode: Mode, onResult: (Boolean, String) -> Unit) {
        try {
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
            }

            if (epIn == null || epOut == null) {
                onResult(false, "Device interface has no usable bulk endpoints")
                return
            }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                onResult(false, "Could not open USB device")
                return
            }
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                onResult(false, "Could not claim USB interface")
                return
            }

            deviceConnection = connection
            claimedInterface = iface
            val transport = UsbBulkTransport(connection, epIn, epOut)

            when (detectedMode) {
                Mode.ADB -> {
                    val auth = AdbAuth.getOrCreate(context)
                    val adb = AdbConnection(transport, auth)
                    adb.connect()
                    adbConnection = adb
                    mode = Mode.ADB
                    onResult(true, "Connected (ADB)")
                }

                Mode.FASTBOOT -> {
                    val fb = FastbootConnection(transport)
                    fastbootConnection = fb
                    mode = Mode.FASTBOOT
                    fb.getVar("max-download-size")?.let { raw ->
                        parseSize(raw)?.let { maxDownloadSize = it }
                    }
                    onResult(true, "Connected (Fastboot)")
                }

                Mode.NONE -> onResult(false, "Unknown device mode")
            }
        } catch (e: Exception) {
            disconnect()
            onResult(false, e.message ?: "Failed to open USB session")
        }
    }

    private fun parseSize(raw: String): Long? {
        val trimmed = raw.trim().removePrefix("0x").removePrefix("0X")
        return trimmed.toLongOrNull(16) ?: trimmed.toLongOrNull()
    }
}
