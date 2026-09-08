package com.adbutility.app.usb.adb

import com.adbutility.app.usb.transport.UsbBulkTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

data class AdbShellResult(val output: String, val exitCode: Int?)

/**
 * A connected ADB session to a single target device over USB.
 *
 * Only one logical operation (shell exec) runs at a time - the connection is
 * fully synchronized, which keeps the stream state machine simple and avoids
 * having to build a general-purpose concurrent multiplexer that we have no
 * real hardware to test against.
 */
class AdbConnection(
    private val transport: UsbBulkTransport,
    private val auth: AdbAuth,
    private val deviceLabel: String = "adbutility"
) {
    private val nextLocalId = AtomicInteger(1)
    var connected = false
        private set

    /** Performs the CNXN/AUTH handshake. Blocks until connected or throws. */
    @Synchronized
    fun connect(authTimeoutMs: Long = 25_000) {
        sendMessage(AdbProtocol.A_CNXN, AdbProtocol.CONNECT_VERSION, AdbProtocol.CONNECT_MAXDATA, AdbProtocol.cString("host::"))

        val deadline = System.currentTimeMillis() + authTimeoutMs
        var sentPublicKey = false
        var waitingForApproval = false

        while (System.currentTimeMillis() < deadline) {
            val msg = readMessage() ?: continue

            when (msg.command) {
                AdbProtocol.A_CNXN -> {
                    connected = true
                    return
                }

                AdbProtocol.A_AUTH -> {
                    if (msg.arg0 != AdbProtocol.AUTH_TOKEN) continue
                    require(msg.payload.size == 20) { "Invalid ADB auth token size: ${msg.payload.size}" }

                    when {
                        !sentPublicKey -> {
                            // First attempt: authenticate with the persisted key.
                            sendMessage(
                                AdbProtocol.A_AUTH,
                                AdbProtocol.AUTH_SIGNATURE,
                                0,
                                auth.signToken(msg.payload)
                            )
                            sentPublicKey = true
                        }

                        !waitingForApproval -> {
                            // The signature was rejected. Send our public key so
                            // adbd can display the normal "Allow USB debugging"
                            // prompt. The next token must be signed again.
                            sendMessage(
                                AdbProtocol.A_AUTH,
                                AdbProtocol.AUTH_RSAPUBLICKEY,
                                0,
                                auth.encodedPublicKey(deviceLabel)
                            )
                            waitingForApproval = true
                        }

                        else -> {
                            // The user approved the key; authenticate the fresh
                            // token. Older implementations often miss this step.
                            sendMessage(
                                AdbProtocol.A_AUTH,
                                AdbProtocol.AUTH_SIGNATURE,
                                0,
                                auth.signToken(msg.payload)
                            )
                        }
                    }
                }
            }
        }

        throw IOException("ADB handshake timed out - check target device for a debugging authorization prompt")
    }

    /**
     * Runs a single shell command and returns its combined output. The exit
     * code is recovered with a small trailer trick since we don't negotiate
     * the newer "shell v2" service that separates stdout/stderr/exit-code.
     */
    @Synchronized
    fun shell(command: String, timeoutMs: Long = 30_000): AdbShellResult {
        check(connected) { "Not connected" }

        val marker = "__ADBUTILITY_EXIT__"
        val wrapped = "($command); echo $marker:\$?"

        val localId = nextLocalId.getAndIncrement()
        sendMessage(AdbProtocol.A_OPEN, localId, 0, AdbProtocol.cString("shell:$wrapped"))

        val output = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        var remoteId = 0
        var opened = false

        while (System.currentTimeMillis() < deadline) {
            val msg = readMessage() ?: continue

            when (msg.command) {
                AdbProtocol.A_OKAY -> {
                    if (msg.arg1 == localId) {
                        remoteId = msg.arg0
                        opened = true
                    }
                }

                AdbProtocol.A_WRTE -> {
                    if (msg.arg1 == localId) {
                        remoteId = msg.arg0
                        output.write(msg.payload)
                        sendMessage(AdbProtocol.A_OKAY, localId, remoteId, ByteArray(0))
                    }
                }

                AdbProtocol.A_CLSE -> {
                    if (msg.arg1 == localId || !opened) {
                        sendMessage(AdbProtocol.A_CLSE, localId, remoteId, ByteArray(0))
                        return parseResult(output.toString(Charsets.UTF_8.name()), marker)
                    }
                }
            }
        }

        throw IOException("ADB shell command timed out")
    }

    private fun parseResult(raw: String, marker: String): AdbShellResult {
        val idx = raw.lastIndexOf("$marker:")
        if (idx == -1) return AdbShellResult(raw, null)

        val before = raw.substring(0, idx)
        val codeStr = raw.substring(idx + marker.length + 1).trim()
        val code = codeStr.toIntOrNull()
        return AdbShellResult(before, code)
    }

    // ---------------------------------------------------------------

    private fun sendMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val msg = AdbProtocol.Message(command, arg0, arg1, payload)
        transport.write(msg.encodeHeader())
        if (payload.isNotEmpty()) {
            transport.write(payload)
        }
    }

    private fun readMessage(): AdbProtocol.Message? {
        val header = transport.readExact(AdbProtocol.HEADER_LENGTH)
        val fields = AdbProtocol.Message.parseHeader(header)
        val command = fields[0]
        val arg0 = fields[1]
        val arg1 = fields[2]
        val dataLength = fields[3]
        if (dataLength < 0 || dataLength > AdbProtocol.MAX_PAYLOAD) {
            throw IOException("Invalid ADB payload length: $dataLength")
        }
        if (fields[5] != command.inv()) {
            throw IOException("Invalid ADB message magic")
        }
        val payload = if (dataLength > 0) transport.readExact(dataLength) else ByteArray(0)
        if (AdbProtocol.Message.checksum(payload) != fields[4]) {
            throw IOException("ADB payload checksum mismatch")
        }
        return AdbProtocol.Message(command, arg0, arg1, payload)
    }
}
