package com.adbutility.app.usb.adb

import java.io.ByteArrayOutputStream

/**
 * Raw ADB wire protocol (the same protocol AOSP's `adb`/`adbd` speak to each
 * other over USB or TCP). This is a public, well documented protocol - this
 * is an original implementation, not copied from any existing client.
 *
 * Every message is a 24-byte little-endian header optionally followed by a
 * payload of `dataLength` bytes.
 */
object AdbProtocol {

    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val CONNECT_VERSION = 0x01000000
    /** Conservative payload size that works reliably on every USB2.0 device. */
    const val CONNECT_MAXDATA = 256 * 1024

    const val HEADER_LENGTH = 24
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray = ByteArray(0)
    ) {
        fun encodeHeader(): ByteArray {
            val checksum = checksum(payload)
            val magic = command.inv()
            val out = ByteArrayOutputStream(HEADER_LENGTH)
            writeInt(out, command)
            writeInt(out, arg0)
            writeInt(out, arg1)
            writeInt(out, payload.size)
            writeInt(out, checksum)
            writeInt(out, magic)
            return out.toByteArray()
        }

        companion object {
            fun checksum(data: ByteArray): Int {
                var sum = 0
                for (b in data) {
                    sum += (b.toInt() and 0xFF)
                }
                return sum
            }

            private fun writeInt(out: ByteArrayOutputStream, value: Int) {
                out.write(value and 0xFF)
                out.write((value ushr 8) and 0xFF)
                out.write((value ushr 16) and 0xFF)
                out.write((value ushr 24) and 0xFF)
            }

            fun parseHeader(header: ByteArray): IntArray {
                require(header.size == HEADER_LENGTH)
                return IntArray(6) { i -> readInt(header, i * 4) }
            }

            private fun readInt(buf: ByteArray, offset: Int): Int {
                return (buf[offset].toInt() and 0xFF) or
                    ((buf[offset + 1].toInt() and 0xFF) shl 8) or
                    ((buf[offset + 2].toInt() and 0xFF) shl 16) or
                    ((buf[offset + 3].toInt() and 0xFF) shl 24)
            }
        }
    }

    fun cString(s: String): ByteArray = (s + "\u0000").toByteArray(Charsets.UTF_8)
}
