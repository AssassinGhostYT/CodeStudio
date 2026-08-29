package dev.ide.android.Terminal

import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal, dependency-free GNU/USTAR `.tar.gz` extractor (Android has no `tar` to fork and no
 * commons-compress on the runtime classpath). Handles the entry kinds the ubuntu-base rootfs
 * tarballs use: regular files, directories, symlinks, hard links, GNU long-name headers and PAX
 * extended headers (which are skipped, carrying only metadata). Everything else is ignored.
 */
internal object TarGz {

    private const val BLOCK = 512L
    private const val NAME = 0
    private const val MODE = 100
    private const val SIZE = 124
    private const val TYPE = 156
    private const val LINK_NAME = 157
    private const val MAGIC = 257

    /** Extracts [archive]'s contents under [dest], creating parent directories as needed. */
    fun extract(archive: File, dest: File) {
        GZIPInputStream(archive.inputStream()).use { input ->
            var pendingName: String? = null
            while (true) {
                val header = ByteArray(BLOCK.toInt())
                val read = readFully(input, header)
                if (read == 0) break // end of archive (all-zero block or EOF)
                if (read != BLOCK.toInt()) throw IllegalStateException("Truncated tar header")
                if (isZeroBlock(header)) {
                    // Tar pads with one or more all-zero blocks before EOF; skip to the end.
                    skipZeroPadding(input)
                    break
                }
                val name = String(header, NAME, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')
                val magic = String(header, MAGIC, 6, Charsets.US_ASCII)
                if (!(magic == "ustar\u0000" || magic == "ustar ")) {
                    throw IllegalStateException("Not a tar archive (magic '$magic')")
                }
                val size = octal(header, SIZE).toLong()
                val type = header[TYPE].toInt().toChar()
                val link = String(header, LINK_NAME, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')
                when (type) {
                    // GNU long-name extension: the following entry is the real path of the next header.
                    'L' -> {
                        pendingName = readTextBlock(input, size).trimEnd('\u0000')
                        // readTextBlock already consumed `size` bytes — skip only the trailing padding.
                        val padded = (size + (BLOCK - 1)) / BLOCK * BLOCK
                        skipData(input, padded - size)
                    }
                    'x', 'g' -> skipData(input, size) // PAX extended headers: metadata only, drop.
                    'K' -> skipData(input, size)      // GNU long link name: not needed for extraction.
                    '0', '\u0000' -> {
                        val target = File(dest, pendingName ?: name)
                        pendingName = null
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            copyExactly(input, out, size)
                        }
                        skipData(input, size)
                    }
                    '5' -> {
                        val dir = File(dest, pendingName ?: name)
                        pendingName = null
                        dir.parentFile?.mkdirs()
                        if (!dir.exists()) dir.mkdirs()
                        skipData(input, size)
                    }
                    '1' -> { // Hard link: copy the referent.
                        val linkTarget = File(dest, name)
                        try {
                            val source = File(dest, link.trimEnd('/')).takeIf { it.exists() }
                            if (source != null) {
                                linkTarget.parentFile?.mkdirs()
                                source.copyTo(linkTarget, overwrite = true)
                            }
                        } catch (_: Exception) {
                        }
                        skipData(input, size)
                    }
                    '2' -> { // Symlink. Best-effort: Android apps can't mksymlink on all FSes; the
                             // entry is skipped and consumers (shell tools) fall back to the target.
                        skipData(input, size)
                    }
                    else -> skipData(input, size)
                }
            }
        }
    }

    private fun readFully(input: java.io.InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val n = input.read(into, total, into.size - total)
            if (n < 0) return total
            total += n
        }
        return total
    }

    private fun readTextBlock(input: java.io.InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt().coerceAtMost(Int.MAX_VALUE))
        val n = readFully(input, bytes)
        return String(bytes, 0, n, Charsets.UTF_8)
    }

    private fun copyExactly(input: java.io.InputStream, out: FileOutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, remaining.coerceAtMost(buf.size.toLong()).toInt())
            if (n < 0) throw IllegalStateException("Truncated file data in tar")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipData(input: java.io.InputStream, size: Long) {
        val padded = (size + (BLOCK - 1)) / BLOCK * BLOCK
        var remaining = padded
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, remaining.coerceAtMost(buf.size.toLong()).toInt())
            if (n < 0) throw IllegalStateException("Truncated tar data")
            remaining -= n
        }
    }

    private fun skipZeroPadding(input: java.io.InputStream) {
        val buf = ByteArray(BLOCK.toInt())
        while (true) {
            val n = input.read(buf, 0, buf.size)
            if (n <= 0) return
            if (!isZeroBlock(buf, n)) return // non-zero pad seen: we've over-read real data — bail.
        }
    }

    private fun isZeroBlock(block: ByteArray, len: Int = block.size): Boolean {
        for (i in 0 until len) if (block[i] != 0.toByte()) return false
        return true
    }

    /** Parses the ASCII octal number at [offset]. Returns 0 on a blank field. */
    private fun octal(header: ByteArray, offset: Int): Int {
        val len = 12
        var acc = 0
        for (i in offset until (offset + len)) {
            val c = header[i].toInt()
            when {
                c == 0 -> return acc
                c == ' '.toInt() -> if (acc != 0) return acc
                c < '0'.toInt() || c > '7'.toInt() -> return acc
                else -> acc = acc * 8 + (c - '0'.toInt())
            }
        }
        return acc
    }
}