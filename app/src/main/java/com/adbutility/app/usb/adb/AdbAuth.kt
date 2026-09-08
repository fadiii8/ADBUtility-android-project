package com.adbutility.app.usb.adb

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.Signature

/**
 * Handles the ADB authentication handshake.
 *
 * ADB does NOT use standard X.509/PEM for its public key exchange, and its
 * signature scheme is a "raw" RSA-PKCS#1v1.5-SHA1 sign of an already-hashed
 * 20-byte token (i.e. it does not hash again the way `Signature
 * .getInstance("SHA1withRSA")` normally would) - so both the padding and the
 * public-key struct are built by hand here to match what `adbd` expects.
 */
class AdbAuth private constructor(private val keyPair: KeyPair) {

    private val privateKey = keyPair.private as RSAPrivateKey
    private val publicKey = keyPair.public as RSAPublicKey

    /** Fixed ASN.1 DER prefix for a SHA-1 DigestInfo (PKCS#1 constant). */
    private val sha1DigestInfoPrefix = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    /** Signs the 20-byte ADB auth token using SHA1withRSA (PKCS#1 v1.5).
     *
     * ADB gives the host a random 20-byte token. The host signs those bytes
     * with RSA/SHA-1; the 20 bytes are NOT themselves a SHA-1 digest.
     */
    fun signToken(token: ByteArray): ByteArray {
        require(token.size == 20) { "ADB auth token must be 20 bytes" }
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(privateKey)
        signature.update(token)
        return signature.sign()
    }

    /**
     * Builds the ADB "AUTH RSAPUBLICKEY" payload: base64(custom struct) +
     * " " + user@host label + NUL.
     */
    fun encodedPublicKey(label: String): ByteArray {
        val struct = buildAdbPublicKeyStruct()
        val encoded = Base64.encodeToString(struct, Base64.NO_WRAP)
        return "$encoded $label\u0000".toByteArray(Charsets.UTF_8)
    }

    /**
     * ADB's public key wire format (used by adbd's libcrypto-free RSA
     * verifier): a fixed-size struct containing the modulus, Montgomery
     * reduction constants, and the exponent - NOT a standard X.509 key.
     */
    private fun buildAdbPublicKeyStruct(): ByteArray {
        val numWords = 64 // RSA-2048 / 32-bit words
        val rBits = 32 * numWords
        val n = publicKey.modulus
        val r = BigInteger.ONE.shiftLeft(rBits)

        // n0inv = -n^-1 mod 2^32
        val nInvMod32 = n.modInverse(BigInteger.ONE.shiftLeft(32))
        val n0inv = BigInteger.ONE.shiftLeft(32).subtract(nInvMod32).mod(BigInteger.ONE.shiftLeft(32))

        // rr = R^2 mod n
        val rr = r.multiply(r).mod(n)

        val out = ByteArrayOutputStream()
        writeLE32(out, numWords)
        writeLE32(out, n0inv.toLong().toInt())
        writeWords(out, n, numWords)
        writeWords(out, rr, numWords)
        writeLE32(out, publicKey.publicExponent.toInt())
        return out.toByteArray()
    }

    private fun writeWords(out: ByteArrayOutputStream, value: BigInteger, numWords: Int) {
        var v = value
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        for (i in 0 until numWords) {
            val word = v.and(mask).toLong().toInt()
            writeLE32(out, word)
            v = v.shiftRight(32)
        }
    }

    private fun writeLE32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    companion object {
        private const val PREFS_NAME = "adb_utility_keys"
        private const val KEY_PRIVATE = "private_key"
        private const val KEY_PUBLIC = "public_key"

        /** Loads the persisted keypair, generating and saving a new one on first run. */
        fun getOrCreate(context: Context): AdbAuth {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val storedPrivate = prefs.getString(KEY_PRIVATE, null)
            val storedPublic = prefs.getString(KEY_PUBLIC, null)

            val keyPair = if (storedPrivate != null && storedPublic != null) {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(
                    PKCS8EncodedKeySpec(Base64.decode(storedPrivate, Base64.NO_WRAP))
                )
                val pub = kf.generatePublic(
                    X509EncodedKeySpec(Base64.decode(storedPublic, Base64.NO_WRAP))
                )
                KeyPair(pub, priv)
            } else {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(2048)
                val newPair = generator.generateKeyPair()
                prefs.edit()
                    .putString(KEY_PRIVATE, Base64.encodeToString(newPair.private.encoded, Base64.NO_WRAP))
                    .putString(KEY_PUBLIC, Base64.encodeToString(newPair.public.encoded, Base64.NO_WRAP))
                    .apply()
                newPair
            }

            return AdbAuth(keyPair)
        }
    }
}
