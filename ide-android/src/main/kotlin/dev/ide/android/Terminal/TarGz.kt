package dev.ide.android.Terminal

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Minimal, dependency-free GNU/USTAR `.tar.gz` extractor (Android has no `tar` to fork and no
 * commons-compress on the runtime classpath). Handles the entry kinds the ubuntu-base rootfs
 * tarballs use: regular files, directories, symlinks, hard links, GNU long-name headers and PAX
 * extended headers (which are skipped, carrying only metadata). Symlinks and hard links are
 * recreated via `Files.createSymbolicLink`/`createLink` — the rootfs relies on them (`/bin`
 * is a symlink to `usr/bin`), and `filesDir` on Android sits on an ext4/f2fs data partition that
 * supports both. Everything else is ignored.
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
                val mode = octal(header, MODE)
                val type = header[TYPE].toInt().toChar()
                val link = String(header, LINK_NAME, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')
                // Data field is `size` bytes followed by padding up to the next 512-byte block.
                val padded = (size + (BLOCK - 1)) / BLOCK * BLOCK
                when (type) {
                    // GNU long-name extension: the following entry is the real path of the next header.
                    'L' -> {
                        pendingName = readTextBlock(input, size).trimEnd('\u0000')
                        // readTextBlock already consumed `size` bytes — skip only the trailing padding.
                        skipData(input, padded - size)
                    }
                    'x', 'g', 'K' -> skipData(input, padded) // PAX / GNU-longlink headers: metadata only, drop.
                    '0', '\u0000' -> {
                        val target = File(dest, pendingName ?: name)
                        pendingName = null
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            copyExactly(input, out, size)
                        }
                        applyMode(target, mode)
                        // copyExactly already consumed `size` bytes — skip only the trailing padding.
                        skipData(input, padded - size)
                    }
                    '5' -> {
                        val dir = File(dest, pendingName ?: name)
                        pendingName = null
                        dir.parentFile?.mkdirs()
                        if (!dir.exists()) dir.mkdirs()
                        applyMode(dir, mode)
                        skipData(input, padded)
                    }
                    '1' -> { // Hard link: same inode as the referent.
                        val linkTarget = File(dest, pendingName ?: name)
                        val source = File(dest, link.trimEnd('/'))
                        pendingName = null
try {
                            linkTarget.parentFile?.mkdirs()
                            java.nio.file.Files.createLink(linkTarget.toPath(), source.toPath())
                        } catch (_: Exception) {
                            // Referent not extracted yet or FS lacks hard links — copy as fallback.
                            try {
                                if (source.exists()) {
                                    linkTarget.parentFile?.mkdirs()
                                    source.copyTo(linkTarget, overwrite = true)
                                }
                            } catch (_: Exception) {
                            }
                        }
                        skipData(input, padded)
                    }
                    '2' -> { // Symlink: recreate it — the rootfs depends on `/bin -> usr/bin` etc.
                        val linkTarget = File(dest, pendingName ?: name)
                        val target = link.trimEnd('/')
                        pendingName = null
                        try {
                            linkTarget.parentFile?.mkdirs()
                            Files.createSymbolicLink(linkTarget.toPath(), Paths.get(target))
                        } catch (_: Exception) {
                            // Filesystem without symlinks (rare): copy the referent instead.
                            try {
                                val source = File(dest, target).takeIf { it.exists() }
                                if (source != null) {
                                    linkTarget.parentFile?.mkdirs()
                                    if (source.isDirectory) linkTarget.mkdirs() else source.copyTo(linkTarget, overwrite = true)
                                }
                            } catch (_: Exception) {
                            }
                        }
                        skipData(input, padded)
                    }
                    else -> skipData(input, padded)
                }
            }
        }
    }

    /** Applies the raw tar mode bits (07777) to a real file/dir. The exec bits matter: every binary in
     *  the rootfs (bash, ls, apt…) must come out executable or proot refuses to run them. Symlinks are
     *  skipped — `chmod` on a symlink would follow the link on most platforms. */
    private fun applyMode(target: File, mode: Int) {
        if (mode == 0) return
        try {
            if (target.isFile || target.isDirectory) Os.chmod(target.absolutePath, mode and 0xFFF)
        } catch (_: Exception) {
            // Fallback for non-Linux test hosts.
            try {
                target.setExecutable((mode and 0x49) != 0, true)
                target.setReadable(true, false)
                target.setWritable((mode and 0x92) != 0, false)
            } catch (_: Exception) {
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

    /** Skips EXACTLY [bytes] of the stream (callers pass the fully padded total, or just the padding
 *  part when the data itself was already consumed). Never re-rounds the argument to a block. */
private fun skipData(input: java.io.InputStream, bytes: Long) {
        var remaining = bytes
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