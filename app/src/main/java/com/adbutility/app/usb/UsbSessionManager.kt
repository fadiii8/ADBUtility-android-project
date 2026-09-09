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
    var maxDownloadSize: Int = 4 * 1024 * 1024
        private set

    val isConnected: Boolean
        get() = mode != Mode.NONE

    fun connect(onResult: (success: Boolean, message: String) -> Unit) {
        val candidate = findCandidateInterface()
        if (candidate == null) {
            onResult(false, "No ADB/Fastboot interface found.\n" + buildDiagnostics())
            return
        }

        val (device, iface, detectedMode) = candidate

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

    /**
     * Human-readable dump of every USB device Android currently sees and
     * their interfaces, so a "not found" failure can be diagnosed without
     * needing a debugger attached - this is the #1 thing to check first:
     * if the list is completely EMPTY, the target phone never showed up on
     * the bus at all (a cable/USB-role issue, not an app bug); if devices
     * appear but none match class=0xFF/sub=0x42, the target is attached but
     * not exposing an ADB/Fastboot interface (wrong USB mode or debugging
     * not enabled on the target).
     */
    private fun buildDiagnostics(): String {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            return "No USB device is visible to Android at all right now.\n" +
                "This usually means the TARGET phone never enumerated as a USB " +
                "peripheral on the bus - check:\n" +
                "- Is this phone's USB-C port actually OTG/host-capable? (test: plug " +
                "in a USB flash drive here and see if any app can see it)\n" +
                "- On the TARGET phone, look for a USB notification (often says " +
                "something like \"USB controlled by...\") and make sure it's set so " +
                "THIS phone is the host / the target is the connected device, not " +
                "the other way around\n" +
                "- Try the cable in the other orientation, or a different C-to-C cable"
        }

        val sb = StringBuilder("Found ${devices.size} USB device(s), but none exposed an ADB/Fastboot interface:\n")
        for (device in devices) {
            sb.append("- vendorId=0x${device.vendorId.toString(16)} productId=0x${device.productId.toString(16)} ")
            sb.append("name=${device.deviceName} interfaces=${device.interfaceCount}\n")
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                sb.append(
                    "    iface#$i class=0x${iface.interfaceClass.toString(16)} " +
                        "subclass=0x${iface.interfaceSubclass.toString(16)} " +
                        "protocol=0x${iface.interfaceProtocol.toString(16)}\n"
                )
            }
        }
        sb.append("Expected class=0xff subclass=0x42 protocol=0x01 (ADB) or 0x03 (Fastboot).\n")
        sb.append("If the target is in normal Android (not bootloader), make sure " +
            "Developer Options > USB debugging is ON on the target, and its USB " +
            "connection mode isn't set to \"Charging only\".")
        return sb.toString()
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
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = PendingIntent.getBroadcast(context, 0, Intent(actionUsbPermission), flags)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != actionUsbPermission) return
                context.unregisterReceiver(this)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                callback(granted)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(actionUsbPermission), Context.RECEIVER_EXPORTED)
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

    private fun parseSize(raw: String): Int? {
        val trimmed = raw.trim().removePrefix("0x").removePrefix("0X")
        return trimmed.toIntOrNull(16) ?: trimmed.toIntOrNull()
    }
}
