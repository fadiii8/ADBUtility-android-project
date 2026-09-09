package com.adbutility.app

import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.adbutility.app.usb.UsbSessionManager
import com.adbutility.app.usb.fastboot.FastbootConnection
import com.adbutility.app.usb.fastboot.FastbootResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.Executors

/**
 * Bridges assets/bridge.js (window.ADBBridge / window.FastbootBridge) to:
 *  - root (`su`) operations on THIS (host) device [legacy methods, unchanged]
 *  - real ADB / Fastboot protocol sessions with a TARGET device connected
 *    over USB OTG, via [UsbSessionManager].
 *
 * Every nativeXxx() is called synchronously from JS but does its work on a
 * background thread and reports back through window.__adbResolve /
 * window.__adbReject.
 */
class NativeBridge(
    private val webView: WebView,
    private val usbSessions: UsbSessionManager,
    private val filePicker: FilePicker
) {
    interface FilePicker {
        fun pickFile(onPicked: (Uri?) -> Unit)
    }

    private val executor = Executors.newCachedThreadPool()

    @JavascriptInterface
    fun nativeOnReady() {}

    // ================= HOST DEVICE (root / su) =================

    @JavascriptInterface
    fun nativeGetDeviceInfo(argsJson: String, callbackId: String) {
        executor.execute {
            try {
                val info = JSONObject().apply {
                    put("connected", hasRoot())
                    put("model", Build.MODEL ?: "")
                    put("manufacturer", Build.MANUFACTURER ?: "")
                    put("android", Build.VERSION.RELEASE ?: "")
                    put("build", Build.DISPLAY ?: "")
                    put("serial", getSerial())
                }
                resolve(callbackId, info.toString())
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "Failed to read device info")
            }
        }
    }

    @JavascriptInterface
    fun nativeReboot(argsJson: String, callbackId: String) = runRootCommandAsync(callbackId, "reboot")

    @JavascriptInterface
    fun nativeRebootBootloader(argsJson: String, callbackId: String) = runRootCommandAsync(callbackId, "reboot bootloader")

    @JavascriptInterface
    fun nativeRebootRecovery(argsJson: String, callbackId: String) = runRootCommandAsync(callbackId, "reboot recovery")

    @JavascriptInterface
    fun nativeExecuteCommand(argsJson: String, callbackId: String) {
        executor.execute {
            try {
                val command = JSONObject(argsJson).optString("command", "")
                if (command.isBlank()) {
                    reject(callbackId, "Empty command")
                    return@execute
                }
                resolve(callbackId, runShell(command).toString())
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "Command execution failed")
            }
        }
    }

    // ================= TARGET DEVICE (USB OTG) =================

    @JavascriptInterface
    fun nativeUsbConnect(argsJson: String, callbackId: String) {
        executor.execute {
            usbSessions.connect { success, message ->
                if (success) {
                    resolve(
                        callbackId,
                        JSONObject()
                            .put("connected", true)
                            .put("mode", usbSessions.mode.name.lowercase())
                            .put("message", message)
                            .toString()
                    )
                } else {
                    reject(callbackId, message)
                }
            }
        }
    }

    @JavascriptInterface
    fun nativeUsbDisconnect(argsJson: String, callbackId: String) {
        executor.execute {
            usbSessions.disconnect()
            resolve(callbackId, JSONObject().put("connected", false).toString())
        }
    }

    @JavascriptInterface
    fun nativeUsbStatus(argsJson: String, callbackId: String) {
        executor.execute {
            resolve(callbackId, JSONObject(usbSessions.status()).toString())
        }
    }

    /** Runs a shell command on the OTG-connected TARGET device via real ADB. */
    @JavascriptInterface
    fun nativeUsbShell(argsJson: String, callbackId: String) {
        executor.execute {
            try {
                val adb = usbSessions.adbConnection
                if (adb == null || !adb.connected) {
                    reject(callbackId, "Not connected to a target device over ADB")
                    return@execute
                }
                val command = JSONObject(argsJson).optString("command", "")
                if (command.isBlank()) {
                    reject(callbackId, "Empty command")
                    return@execute
                }
                val result = adb.shell(command)
                val json = JSONObject()
                    .put("stdout", result.output)
                    .put("stderr", "")
                    .apply { result.exitCode?.let { put("exitCode", it) } }
                resolve(callbackId, json.toString())
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "ADB command failed")
            }
        }
    }

    /** Sends a raw fastboot command body (e.g. "getvar:product", "reboot-bootloader"). */
    @JavascriptInterface
    fun nativeFastbootCommand(argsJson: String, callbackId: String) {
        executor.execute {
            try {
                val fb = usbSessions.fastbootConnection
                if (fb == null) {
                    reject(callbackId, "Not connected to a target device over Fastboot")
                    return@execute
                }
                val command = JSONObject(argsJson).optString("command", "")
                if (command.isBlank()) {
                    reject(callbackId, "Empty command")
                    return@execute
                }
                val result = fb.command(command)
                resolve(callbackId, fastbootResultJson(result).toString())
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "Fastboot command failed")
            }
        }
    }

    @JavascriptInterface
    fun nativeFastbootUnlock(argsJson: String, callbackId: String) = fastbootAction(callbackId) { it.flashingUnlock() }

    @JavascriptInterface
    fun nativeFastbootLock(argsJson: String, callbackId: String) = fastbootAction(callbackId) { it.flashingLock() }

    @JavascriptInterface
    fun nativeFastbootRebootBootloader(argsJson: String, callbackId: String) = fastbootAction(callbackId) { it.rebootBootloader() }

    @JavascriptInterface
    fun nativeFastbootReboot(argsJson: String, callbackId: String) = fastbootAction(callbackId) { it.reboot() }

    @JavascriptInterface
    fun nativeFastbootErase(argsJson: String, callbackId: String) {
        executor.execute {
            try {
                val fb = usbSessions.fastbootConnection ?: run {
                    reject(callbackId, "Not connected over Fastboot"); return@execute
                }
                val partition = JSONObject(argsJson).optString("partition", "")
                if (partition.isBlank()) { reject(callbackId, "Empty partition name"); return@execute }
                resolve(callbackId, fastbootResultJson(fb.erase(partition)).toString())
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "Erase failed")
            }
        }
    }

    /**
     * Flashes a user-picked image file to [partition] on the fastboot target.
     * Opens the system file picker; only works for images that fit within
     * the bootloader's max-download-size (see FastbootConnection docs for
     * the current limitation on very large images like some super.img files).
     */
    @JavascriptInterface
    fun nativeFastbootFlash(argsJson: String, callbackId: String) {
        val partition = JSONObject(argsJson).optString("partition", "")
        if (partition.isBlank()) {
            reject(callbackId, "Empty partition name")
            return
        }

        filePicker.pickFile { uri ->
            if (uri == null) {
                reject(callbackId, "No file selected")
                return@pickFile
            }

            executor.execute {
                try {
                    val fb = usbSessions.fastbootConnection ?: run {
                        reject(callbackId, "Not connected over Fastboot"); return@execute
                    }

                    val resolver = webView.context.contentResolver
                    val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    if (size <= 0) {
                        reject(callbackId, "Could not determine a valid file size")
                        return@execute
                    }

                    resolver.openInputStream(uri).use { input ->
                        if (input == null) {
                            reject(callbackId, "Could not open selected file")
                            return@execute
                        }
                        // Images bigger than maxDownloadSize (e.g. super.img) are
                        // automatically split into multiple sparse chunks and
                        // flashed in sequence - see FastbootConnection.flashLargeImage.
                        val result = fb.flashLargeImage(
                            partition,
                            input,
                            size,
                            usbSessions.maxDownloadSize,
                            webView.context.cacheDir
                        )
                        resolve(callbackId, fastbootResultJson(result).toString())
                    }
                } catch (e: Exception) {
                    reject(callbackId, e.message ?: "Flash failed")
                }
            }
        }
    }

    // ---------------------------------------------------------------

    private fun fastbootAction(callbackId: String, action: (FastbootConnection) -> FastbootResult) {
        executor.execute {
            try {
                val fb = usbSessions.fastbootConnection
                if (fb == null) {
                    reject(callbackId, "Not connected to a target device over Fastboot")
                    return@execute
                }
                resolve(callbackId, fastbootResultJson(action(fb)).toString())
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "Fastboot operation failed")
            }
        }
    }

    private fun fastbootResultJson(result: FastbootResult): JSONObject {
        return JSONObject()
            .put("okay", result.okay)
            .put("message", result.message)
            .put("info", JSONArray(result.infoLines))
    }

    private fun runRootCommandAsync(callbackId: String, command: String) {
        executor.execute {
            try {
                resolve(callbackId, runShell(command).toString())
            } catch (e: Exception) {
                reject(callbackId, e.message ?: "Operation failed")
            }
        }
    }

    private fun runShell(command: String): JSONObject {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()
        val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
        val exitCode = process.waitFor()
        return JSONObject().put("stdout", stdout).put("stderr", stderr).put("exitCode", exitCode)
    }

    private fun hasRoot(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id").start()
            val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    private fun getSerial(): String {
        return try {
            @Suppress("DEPRECATION")
            Build.SERIAL ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // ---------------------------------------------------------------

    private fun resolve(callbackId: String, resultJson: String) {
        val escaped = JSONObject.quote(resultJson)
        webView.post { webView.evaluateJavascript("window.__adbResolve('$callbackId', $escaped);", null) }
    }

    private fun reject(callbackId: String, message: String) {
        val escapedMsg = JSONObject.quote(message)
        webView.post { webView.evaluateJavascript("window.__adbReject('$callbackId', $escapedMsg);", null) }
    }
}
