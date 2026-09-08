package com.adbutility.app.usb.fastboot

import com.adbutility.app.usb.transport.UsbBulkTransport
import java.io.IOException
import java.io.InputStream
import java.util.Locale

data class FastbootResult(val okay: Boolean, val message: String, val infoLines: List<String>)

/** Minimal USB fastboot protocol client. */
class FastbootConnection(private val transport: UsbBulkTransport) {

    @Synchronized
    fun command(cmd: String, timeoutMs: Long = 15_000): FastbootResult {
        require(cmd.isNotEmpty()) { "Fastboot command is empty" }
        require(cmd.length <= 64) { "Fastboot command is too long" }
        transport.write(cmd.toByteArray(Charsets.US_ASCII))
        return readResponses(timeoutMs)
    }

    /** Sends one DOWNLOAD transaction. The caller must keep data <= max-download-size. */
    @Synchronized
    fun download(data: ByteArray, timeoutMs: Long = 60_000): FastbootResult {
        val header = "download:%08x".format(Locale.US, data.size)
        transport.write(header.toByteArray(Charsets.US_ASCII))

        val ready = readResponses(timeoutMs)
        if (!ready.okay || ready.message.length != 8 || !ready.message.all { it in "0123456789abcdefABCDEF" }) {
            return if (!ready.okay) ready
            else FastbootResult(false, "Bootloader did not accept download: ${ready.message}", ready.infoLines)
        }

        transport.write(data)
        return readResponses(timeoutMs)
    }

    /**
     * Flashes a file which fits in one fastboot download. Android sparse images
     * are accepted as-is: the bootloader/fastbootd performs the sparse expansion.
     *
     * Important protocol limitation: standard USB fastboot has one DOWNLOAD
     * buffer followed by one FLASH command; there is no portable host-side
     * "append this next download at partition offset" operation. Therefore a
     * sparse file whose *encoded file size* exceeds max-download-size cannot be
     * safely split into independent downloads by a generic client.
     */
    fun flashFromStream(partition: String, input: InputStream, totalSize: Long, maxDownloadSize: Long): FastbootResult {
        require(partition.isNotBlank()) { "Empty partition name" }
        if (totalSize <= 0L) return FastbootResult(false, "Image is empty", emptyList())
        if (totalSize > maxDownloadSize) {
            return FastbootResult(
                false,
                "Image is $totalSize bytes but bootloader max-download-size is $maxDownloadSize bytes. " +
                    "A standard fastboot session cannot split one flash transaction safely; use fastbootd/a larger download buffer or a device-specific streaming protocol.",
                emptyList()
            )
        }
        if (totalSize > Int.MAX_VALUE) {
            return FastbootResult(false, "Image is too large for this Android client buffer", emptyList())
        }

        val buffer = ByteArray(totalSize.toInt())
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            if (n == 0) continue
            read += n
        }
        if (read != buffer.size) {
            return FastbootResult(false, "Failed to read image (got $read of ${buffer.size} bytes)", emptyList())
        }

        val downloadResult = download(buffer)
        if (!downloadResult.okay) return downloadResult
        return command("flash:$partition")
    }

    fun getVar(name: String): String? {
        val result = command("getvar:$name")
        return if (result.okay) result.message else null
    }

    fun rebootBootloader() = command("reboot-bootloader")
    fun reboot() = command("reboot")
    fun continueBoot() = command("continue")
    fun erase(partition: String) = command("erase:$partition")
    fun flashingUnlock() = command("flashing unlock")
    fun flashingLock() = command("flashing lock")
    fun oemUnlock() = command("oem unlock")
    fun oemLock() = command("oem lock")

    private fun readResponses(timeoutMs: Long): FastbootResult {
        val infoLines = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val packet = readResponsePacket(deadline)
            if (packet == null) continue
            val text = String(packet, Charsets.US_ASCII)
            if (text.length < 4) throw IOException("Short fastboot response: ${text.length} bytes")

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

    /**
     * Fastboot status packets are short (the protocol reserves at most 64
     * bytes for the reply). Reading one USB transfer at that size avoids
     * accidentally consuming an INFO packet and the following OKAY packet in
     * one oversized Android bulkTransfer call.
     */
    private fun readResponsePacket(deadline: Long): ByteArray? {
        if (System.currentTimeMillis() >= deadline) throw IOException("Fastboot response timed out")
        val packet = transport.readOnce(64)
        return if (packet.isEmpty()) null else packet
    }

}
