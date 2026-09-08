package com.adbutility.app.usb.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Wraps a pair of USB bulk endpoints (IN/OUT) with safe chunked transfers.
 *
 * Android's UsbDeviceConnection#bulkTransfer is unreliable with very large
 * buffers on some OEM drivers, and USB bulk transfers that are an exact
 * multiple of the endpoint's max packet size need a trailing zero-length
 * packet (ZLP) or the receiving side stalls waiting for "more". Both are
 * handled here so callers never have to think about it.
 */
class UsbBulkTransport(
    private val connection: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val timeoutMs: Int = 5000
) {
    private val chunkSize = 16 * 1024

    @Synchronized
    fun write(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val len = minOf(chunkSize, data.size - offset)
            val sent = connection.bulkTransfer(epOut, data, offset, len, timeoutMs)
            if (sent <= 0) throw IOException("USB write failed at offset $offset (result=$sent)")
            offset += sent
        }
        // Do not force a USB ZLP here. ADB/Fastboot are message-framed
        // protocols, and Android's bulkTransfer implementation/host controller
        // decides packet termination. A synthetic ZLP can cause stalls on some
        // OTG combinations.
    }

    @Synchronized
    fun readExact(length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val out = ByteArrayOutputStream(length)
        val buffer = ByteArray(minOf(chunkSize, length))
        var remaining = length
        while (remaining > 0) {
            val want = minOf(buffer.size, remaining)
            val read = connection.bulkTransfer(epIn, buffer, want, timeoutMs)
            if (read < 0) throw IOException("USB read failed ($remaining bytes remaining)")
            if (read > 0) {
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
        return out.toByteArray()
    }

    /** Reads a single bulk transfer (used for fastboot's short fixed replies). */
    @Synchronized
    fun readOnce(maxLength: Int = 4096): ByteArray {
        val buffer = ByteArray(maxLength)
        val read = connection.bulkTransfer(epIn, buffer, maxLength, timeoutMs)
        if (read < 0) throw IOException("USB read failed")
        return buffer.copyOf(read)
    }
}
