package com.adbutility.app.usb.fastboot

import com.adbutility.app.usb.transport.UsbBulkTransport
import java.io.File
import java.io.IOException
import java.io.InputStream

data class FastbootResult(val okay: Boolean, val message: String, val infoLines: List<String>)

/**
 * Minimal fastboot protocol client.
 *
 * The wire protocol is very simple compared to ADB: the host sends a raw
 * ASCII command as a single bulk-OUT packet, and the device replies with one
 * or more 4-byte-prefixed packets:
 *   "OKAY<msg>" - success, <msg> may be empty
 *   "FAIL<msg>" - failure, <msg> is a human-readable reason
 *   "INFO<msg>" - progress/log line, more replies follow
 *   "DATA<hex8>" - device is ready to receive <hex8> bytes (used for download)
 */
class FastbootConnection(private val transport: UsbBulkTransport) {

    @Synchronized
    fun command(cmd: String, timeoutMs: Long = 15_000): FastbootResult {
        transport.write(cmd.toByteArray(Charsets.US_ASCII))
        return readResponses(timeoutMs)
    }

    /** Sends the DOWNLOAD command followed by the raw bytes of [data]. */
    @Synchronized
    fun download(data: ByteArray, timeoutMs: Long = 60_000): FastbootResult {
        val header = "download:%08x".format(data.size)
        transport.write(header.toByteArray(Charsets.US_ASCII))
        readResponses(timeoutMs) // "DATA........" readiness reply

        transport.write(data)
        return readResponses(timeoutMs)
    }

    fun getVar(name: String): String? {
        val result = command("getvar:$name")
        return if (result.okay) result.message else null
    }

    fun rebootBootloader() = command("reboot-bootloader")
    fun reboot() = command("reboot")
    fun continueBoot() = command("continue")
    fun erase(partition: String) = command("erase:$partition")

    /** Modern devices (Android 5+): `fastboot flashing unlock` / `flashing lock`. */
    fun flashingUnlock() = command("flashing unlock")
    fun flashingLock() = command("flashing lock")

    /** Legacy fallback for older bootloaders that only support `oem unlock`/`oem lock`. */
    fun oemUnlock() = command("oem unlock")
    fun oemLock() = command("oem lock")

    /**
     * Flashes [input] (a raw or Android-sparse image, any size) to [partition].
     *
     * Images larger than [maxDownloadSize] are automatically split into
     * multiple standalone sparse chunks (same technique AOSP's `fastboot`
     * uses for oversized images like super.img) and flashed to the same
     * partition sequentially. [tempDir] is used to stage the split chunks on
     * disk (pass the app's cache directory) - they're deleted afterwards.
     */
    fun flashLargeImage(
        partition: String,
        input: InputStream,
        totalSize: Long,
        maxDownloadSize: Int,
        tempDir: File,
        onProgress: (partDone: Int, partsTotal: Int) -> Unit = { _, _ -> }
    ): FastbootResult {
        val safeMax = (maxDownloadSize - 8192).coerceAtLeast(64 * 1024)

        val splits = try {
            SparseSplitter.split(input, totalSize, safeMax, tempDir)
        } catch (e: Exception) {
            return FastbootResult(false, "Failed to prepare image for flashing: ${e.message}", emptyList())
        }

        try {
            splits.forEachIndexed { index, split ->
                onProgress(index, splits.size)

                val bytes = try {
                    split.file.readBytes()
                } catch (e: Exception) {
                    return FastbootResult(false, "Failed to read staged image part ${index + 1}/${splits.size}: ${e.message}", emptyList())
                }

                val downloadResult = download(bytes)
                if (!downloadResult.okay) {
                    return FastbootResult(
                        false,
                        "Download failed on part ${index + 1}/${splits.size}: ${downloadResult.message}",
                        emptyList()
                    )
                }

                val flashResult = command("flash:$partition")
                if (!flashResult.okay) {
                    return FastbootResult(
                        false,
                        "Flash failed on part ${index + 1}/${splits.size}: ${flashResult.message}",
                        emptyList()
                    )
                }
            }

            onProgress(splits.size, splits.size)
            return FastbootResult(true, "Flashed \"$partition\" successfully (${splits.size} part(s)).", emptyList())
        } finally {
            splits.forEach { it.file.delete() }
        }
    }

    // ---------------------------------------------------------------

    private fun readResponses(timeoutMs: Long): FastbootResult {
        val infoLines = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val raw = transport.readOnce(4096)
            if (raw.isEmpty()) continue

            val text = String(raw, Charsets.US_ASCII)
            if (text.length < 4) continue

            val prefix = text.substring(0, 4)
            val rest = text.substring(4)

            when (prefix) {
                "OKAY" -> return FastbootResult(true, rest, infoLines)
                "FAIL" -> return FastbootResult(false, rest, infoLines)
                "DATA" -> return FastbootResult(true, rest, infoLines)
                "INFO" -> infoLines.add(rest)
                else -> return FastbootResult(false, "Unexpected response: $text", infoLines)
            }
        }

        throw IOException("Fastboot command timed out")
    }
}
