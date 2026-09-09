package com.adbutility.app.usb.fastboot

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream

/**
 * Splits a partition image - either already in Android's "sparse" format, or
 * a plain raw image - into a sequence of standalone sparse images that each
 * fit within a bootloader's max-download-size, so they can be flashed to the
 * SAME partition one after another.
 *
 * This is the same technique AOSP's `fastboot` tool uses for images bigger
 * than a single download (e.g. super.img): every split is a fully valid
 * sparse image covering the *entire* partition block range, where blocks not
 * physically present in that split are represented as "don't care" (skip)
 * chunks, so flashing the splits in order reconstructs the whole image
 * without any single USB transfer exceeding the device's limit.
 *
 * Sparse format reference (public, documented in AOSP's libsparse):
 *   file header (28 bytes): magic, major/minor version, header sizes,
 *   block size, total blocks, total chunks, checksum.
 *   chunk header (12 bytes) x total_chunks: type, reserved, chunk size in
 *   blocks, total chunk size in bytes (including this 12-byte header).
 *   Chunk types: 0xCAC1 raw, 0xCAC2 fill, 0xCAC3 don't care, 0xCAC4 crc32.
 */
object SparseSplitter {

    private const val SPARSE_MAGIC = 0xed26ff3a.toInt()
    private const val CHUNK_TYPE_RAW = 0xCAC1
    private const val CHUNK_TYPE_FILL = 0xCAC2
    private const val CHUNK_TYPE_SKIP = 0xCAC3
    private const val CHUNK_TYPE_CRC32 = 0xCAC4
    private const val FILE_HDR_SIZE = 28
    private const val CHUNK_HDR_SIZE = 12
    private const val RAW_BLOCK_SIZE = 4096

    class Split(val file: File, val sizeBytes: Long)

    fun split(source: InputStream, totalSize: Long, maxChunkBytes: Int, tempDir: File): List<Split> {
        val pin = PushbackInputStream(BufferedInputStream(source, 1 shl 16), 4)
        val magicBuf = ByteArray(4)
        val n = readFully(pin, magicBuf)
        val isSparse = n == 4 && leInt(magicBuf, 0) == SPARSE_MAGIC
        if (n > 0) pin.unread(magicBuf, 0, n)

        return if (isSparse) {
            splitSparse(pin, maxChunkBytes, tempDir)
        } else {
            splitRaw(pin, totalSize, maxChunkBytes, tempDir)
        }
    }

    private fun splitRaw(pin: InputStream, totalSize: Long, maxChunkBytes: Int, tempDir: File): List<Split> {
        val paddedTotal = ((totalSize + RAW_BLOCK_SIZE - 1) / RAW_BLOCK_SIZE) * RAW_BLOCK_SIZE
        val padded: InputStream = if (paddedTotal > totalSize) {
            SequenceInputStream(pin, ZeroPadStream(paddedTotal - totalSize))
        } else {
            pin
        }

        val writer = SplitWriter(tempDir, RAW_BLOCK_SIZE, paddedTotal / RAW_BLOCK_SIZE, maxChunkBytes)
        writer.writeRawSpan(padded, paddedTotal)
        writer.finish()
        return writer.splits
    }

    private fun splitSparse(pin: InputStream, maxChunkBytes: Int, tempDir: File): List<Split> {
        val header = ByteArray(FILE_HDR_SIZE)
        readFullyOrThrow(pin, header)
        if (leInt(header, 0) != SPARSE_MAGIC) throw IOException("Not a sparse image")

        val blkSize = leInt(header, 12)
        val totalBlocks = leIntU(header, 16)
        val totalChunks = leInt(header, 20)

        val writer = SplitWriter(tempDir, blkSize, totalBlocks, maxChunkBytes)

        repeat(totalChunks) {
            val chdr = ByteArray(CHUNK_HDR_SIZE)
            readFullyOrThrow(pin, chdr)
            val type = leShortU(chdr, 0)
            val chunkBlocks = leIntU(chdr, 4)
            val totalSz = leIntU(chdr, 8)
            val dataLen = totalSz - CHUNK_HDR_SIZE

            when (type) {
                CHUNK_TYPE_RAW -> writer.writeRawSpan(pin, dataLen)
                CHUNK_TYPE_FILL -> {
                    val fill = ByteArray(4)
                    readFullyOrThrow(pin, fill)
                    writer.writeFillSpan(chunkBlocks, fill)
                }
                CHUNK_TYPE_SKIP -> writer.writeSkipSpan(chunkBlocks)
                CHUNK_TYPE_CRC32 -> readFullyOrThrow(pin, ByteArray(4)) // dropped, deprecated field
                else -> throw IOException("Unknown sparse chunk type 0x${type.toString(16)}")
            }
        }

        writer.finish()
        return writer.splits
    }

    /** Produces zero bytes forever (bounded by [remaining]) - used to pad a raw image to a block boundary. */
    private class ZeroPadStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            remaining--
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            java.util.Arrays.fill(b, off, off + n, 0.toByte())
            remaining -= n
            return n
        }
    }

    /** Streams input chunks into one or more on-disk split sparse images, each under the byte budget. */
    private class SplitWriter(
        private val tempDir: File,
        private val blkSize: Int,
        private val totalBlocks: Long,
        maxChunkBytes: Int
    ) {
        val splits = mutableListOf<Split>()

        private val maxBytes = maxChunkBytes.toLong()
        private val safetyMargin = 4096L

        private var raf: RandomAccessFile? = null
        private var currentFile: File? = null
        private var globalBlock = 0L
        private var chunkCountInSplit = 0
        private var bytesInSplit = 0L

        private fun ensureOpen() {
            if (raf != null) return
            val f = File.createTempFile("split_", ".sparse", tempDir)
            val r = RandomAccessFile(f, "rw")
            writeHeader(r)
            currentFile = f
            raf = r
            bytesInSplit = FILE_HDR_SIZE.toLong()
            chunkCountInSplit = 0

            if (globalBlock > 0) {
                writeChunkHeader(r, CHUNK_TYPE_SKIP, globalBlock, CHUNK_HDR_SIZE.toLong())
                chunkCountInSplit++
                bytesInSplit += CHUNK_HDR_SIZE
            }
        }

        private fun writeHeader(r: RandomAccessFile) {
            r.seek(0)
            val b = ByteArray(FILE_HDR_SIZE)
            putLeInt(b, 0, SPARSE_MAGIC)
            putLeShort(b, 4, 1)
            putLeShort(b, 6, 0)
            putLeShort(b, 8, FILE_HDR_SIZE)
            putLeShort(b, 10, CHUNK_HDR_SIZE)
            putLeInt(b, 12, blkSize)
            putLeInt(b, 16, totalBlocks.toInt())
            putLeInt(b, 20, 0) // total_chunks placeholder, fixed up in closeCurrent()
            putLeInt(b, 24, 0) // checksum, unused/deprecated
            r.write(b)
        }

        private fun writeChunkHeader(r: RandomAccessFile, type: Int, blocks: Long, totalSz: Long) {
            val b = ByteArray(CHUNK_HDR_SIZE)
            putLeShort(b, 0, type)
            putLeShort(b, 2, 0)
            putLeInt(b, 4, blocks.toInt())
            putLeInt(b, 8, totalSz.toInt())
            r.write(b)
        }

        fun writeSkipSpan(blocks: Long) {
            if (blocks <= 0) return
            ensureOpen()
            writeChunkHeader(raf!!, CHUNK_TYPE_SKIP, blocks, CHUNK_HDR_SIZE.toLong())
            chunkCountInSplit++
            bytesInSplit += CHUNK_HDR_SIZE
            globalBlock += blocks
        }

        fun writeFillSpan(blocks: Long, fillWord: ByteArray) {
            ensureOpen()
            val r = raf!!
            writeChunkHeader(r, CHUNK_TYPE_FILL, blocks, (CHUNK_HDR_SIZE + 4).toLong())
            r.write(fillWord)
            chunkCountInSplit++
            bytesInSplit += CHUNK_HDR_SIZE + 4
            globalBlock += blocks
        }

        fun writeRawSpan(source: InputStream, byteLength: Long) {
            var remaining = byteLength
            val copyBuf = ByteArray(1 shl 16)

            while (remaining > 0) {
                ensureOpen()
                val budget = (maxBytes - safetyMargin - bytesInSplit).coerceAtLeast(0)
                val blocksAvailable = budget / blkSize

                if (blocksAvailable <= 0) {
                    closeCurrent()
                    continue
                }

                val remainingBlocks = remaining / blkSize
                val blocksToWrite = minOf(blocksAvailable, remainingBlocks)
                if (blocksToWrite <= 0) {
                    // A single required block doesn't fit even a fresh split; force it in anyway
                    // rather than looping forever (only happens if maxChunkBytes is unreasonably small).
                    closeCurrent()
                    ensureOpen()
                }

                val finalBlocks = if (blocksToWrite > 0) blocksToWrite else remainingBlocks.coerceAtMost(1)
                val bytesToWrite = finalBlocks * blkSize

                val r = raf!!
                writeChunkHeader(r, CHUNK_TYPE_RAW, finalBlocks, (CHUNK_HDR_SIZE + bytesToWrite))
                var toCopy = bytesToWrite
                while (toCopy > 0) {
                    val n = source.read(copyBuf, 0, minOf(copyBuf.size.toLong(), toCopy).toInt())
                    if (n < 0) throw IOException("Unexpected end of image data")
                    r.write(copyBuf, 0, n)
                    toCopy -= n
                }

                chunkCountInSplit++
                bytesInSplit += CHUNK_HDR_SIZE + bytesToWrite
                globalBlock += finalBlocks
                remaining -= bytesToWrite
            }
        }

        private fun closeCurrent() {
            val r = raf ?: return
            val f = currentFile ?: return

            // IMPORTANT: this trailing "don't care" padding makes THIS split's
            // own chunk list sum to totalBlocks (required for it to be a valid
            // standalone sparse image) - it must NOT advance the shared
            // `globalBlock` counter, since that counter tracks true progress
            // through the SOURCE image and drives where the *next* split's
            // leading skip starts. Advancing it here would make every split
            // after the first believe the whole image was already done.
            if (globalBlock < totalBlocks) {
                val trailing = totalBlocks - globalBlock
                writeChunkHeader(r, CHUNK_TYPE_SKIP, trailing, CHUNK_HDR_SIZE.toLong())
                chunkCountInSplit++
                bytesInSplit += CHUNK_HDR_SIZE
            }

            r.seek(20)
            val b = ByteArray(4)
            putLeInt(b, 0, chunkCountInSplit)
            r.write(b)
            r.close()

            splits.add(Split(f, f.length()))
            raf = null
            currentFile = null
        }

        fun finish() {
            if (raf != null) {
                closeCurrent()
            } else if (splits.isEmpty()) {
                ensureOpen()
                closeCurrent()
            }
        }

        private fun putLeInt(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v ushr 8) and 0xFF).toByte()
            b[off + 2] = ((v ushr 16) and 0xFF).toByte()
            b[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }

        private fun putLeShort(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }
    }

    // ---- little-endian read helpers ----
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun readFullyOrThrow(input: InputStream, buf: ByteArray) {
        if (readFully(input, buf) != buf.size) throw IOException("Unexpected end of sparse image")
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leIntU(b: ByteArray, off: Int): Long = leInt(b, off).toLong() and 0xFFFFFFFFL

    private fun leShortU(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
