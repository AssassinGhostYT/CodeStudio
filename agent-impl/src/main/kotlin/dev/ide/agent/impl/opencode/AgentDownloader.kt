package dev.ide.agent.impl.opencode

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class DownloadResult(
    val success: Boolean,
    val reason: String,
    val downloadedBytes: Long = 0L,
    val calculatedSha256: String? = null
)

data class StagingExtractionResult(
    val success: Boolean,
    val reason: String,
    val extractedCount: Int = 0,
    val totalBytes: Long = 0L,
    val calculatedSha256: String? = null
)

object AgentDownloader {

    private const val MAX_ENTRIES = 10000
    private const val MAX_SINGLE_FILE_BYTES = 300 * 1024 * 1024L // 300MB
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 800 * 1024 * 1024L // 800MB

    fun computeStreamSha256(inputStream: InputStream): Pair<String, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        val copyStream = java.io.ByteArrayOutputStream()
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
            copyStream.write(buffer, 0, read)
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return Pair(hash, copyStream.toByteArray())
    }

    fun downloadUrlToFile(
        urlString: String,
        targetFile: File,
        expectedSha256: String,
        expectedSizeBytes: Long = 0L,
        connectTimeoutMs: Int = 15000,
        readTimeoutMs: Int = 30000
    ): DownloadResult {
        targetFile.parentFile?.mkdirs()
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return DownloadResult(false, "HTTP error response code: $responseCode")
            }

            connection.inputStream.use { input ->
                val (calculatedSha, bytes) = computeStreamSha256(input)
                if (expectedSizeBytes > 0 && bytes.size.toLong() != expectedSizeBytes) {
                    return DownloadResult(false, "Downloaded size mismatch: expected $expectedSizeBytes, got ${bytes.size}", bytes.size.toLong(), calculatedSha)
                }
                if (expectedSha256.isNotBlank() && !calculatedSha.equals(expectedSha256, ignoreCase = true)) {
                    return DownloadResult(false, "SHA-256 mismatch: expected $expectedSha256, calculated $calculatedSha", bytes.size.toLong(), calculatedSha)
                }
                targetFile.writeBytes(bytes)
                return DownloadResult(true, "Download verified and completed successfully", bytes.size.toLong(), calculatedSha)
            }
        } catch (e: Exception) {
            return DownloadResult(false, "Download failed: ${e.message}")
        }
    }

    fun extractTarGzToStaging(
        archiveStream: InputStream,
        expectedSha256: String,
        expectedSizeBytes: Long,
        stagingDir: File,
        requirePreVerification: Boolean = true
    ): StagingExtractionResult {
        if (!stagingDir.exists()) stagingDir.mkdirs()

        val (calculatedSha, rawBytes) = computeStreamSha256(archiveStream)

        if (expectedSizeBytes > 0 && rawBytes.size.toLong() != expectedSizeBytes) {
            return StagingExtractionResult(false, "Size mismatch: expected $expectedSizeBytes, got ${rawBytes.size}", calculatedSha256 = calculatedSha)
        }

        if (requirePreVerification && (expectedSha256.length != 64 || !calculatedSha.equals(expectedSha256, ignoreCase = true))) {
            return StagingExtractionResult(false, "Checksum mismatch: expected $expectedSha256, calculated $calculatedSha", calculatedSha256 = calculatedSha)
        }

        val tarBytesInput = rawBytes.inputStream()
        val canonicalStagingPath = stagingDir.canonicalPath

        var entryCount = 0
        var totalExtractedBytes = 0L

        try {
            GZIPInputStream(tarBytesInput).use { gzis ->
                val headerBuf = ByteArray(512)
                var zeroBlockCount = 0

                while (true) {
                    val readHeader = readFully(gzis, headerBuf)
                    if (readHeader < 512) {
                        if (entryCount == 0) return StagingExtractionResult(false, "Truncated TAR archive header", calculatedSha256 = calculatedSha)
                        break
                    }

                    if (isZeroBlock(headerBuf)) {
                        zeroBlockCount++
                        if (zeroBlockCount >= 2) break
                        continue
                    } else {
                        zeroBlockCount = 0
                    }

                    val nameRaw = String(headerBuf, 0, 100, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                    val sizeRaw = String(headerBuf, 124, 12, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                    val typeFlag = headerBuf[156]

                    val size = runCatching { sizeRaw.trim().toLong(8) }.getOrDefault(0L)

                    if (typeFlag != '0'.code.toByte() && typeFlag != '\u0000'.code.toByte() && typeFlag != '5'.code.toByte()) {
                        return StagingExtractionResult(false, "Unsupported TAR entry type: flag '$typeFlag' for '$nameRaw'", calculatedSha256 = calculatedSha)
                    }

                    val normalizedPath = File(nameRaw).normalize().path
                    if (normalizedPath.startsWith("/") || normalizedPath.startsWith("..") || normalizedPath.contains("../") || normalizedPath.contains("..\\")) {
                        cleanStaging(stagingDir)
                        return StagingExtractionResult(false, "Path traversal attempt detected in entry: $nameRaw", calculatedSha256 = calculatedSha)
                    }

                    val targetFile = File(stagingDir, normalizedPath)
                    val canonicalTarget = targetFile.canonicalPath
                    if (!canonicalTarget.startsWith(canonicalStagingPath)) {
                        cleanStaging(stagingDir)
                        return StagingExtractionResult(false, "Path traversal target escape: $canonicalTarget", calculatedSha256 = calculatedSha)
                    }

                    entryCount++
                    if (entryCount > MAX_ENTRIES) {
                        cleanStaging(stagingDir)
                        return StagingExtractionResult(false, "Exceeded maximum entry count limit ($MAX_ENTRIES)", calculatedSha256 = calculatedSha)
                    }

                    if (typeFlag == '5'.code.toByte() || nameRaw.endsWith("/")) {
                        targetFile.mkdirs()
                    } else {
                        if (size > MAX_SINGLE_FILE_BYTES) {
                            cleanStaging(stagingDir)
                            return StagingExtractionResult(false, "Single file size limit exceeded ($size bytes)", calculatedSha256 = calculatedSha)
                        }

                        totalExtractedBytes += size
                        if (totalExtractedBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            cleanStaging(stagingDir)
                            return StagingExtractionResult(false, "Total uncompressed size limit exceeded", calculatedSha256 = calculatedSha)
                        }

                        targetFile.parentFile?.mkdirs()
                        targetFile.outputStream().use { out ->
                            val copied = copyBytes(gzis, out, size)
                            if (copied != size) {
                                cleanStaging(stagingDir)
                                return StagingExtractionResult(false, "Truncated TAR content for entry '$nameRaw'", calculatedSha256 = calculatedSha)
                            }
                        }
                    }

                    val padding = (512 - (size % 512)) % 512
                    if (padding > 0) {
                        skipFully(gzis, padding)
                    }
                }
            }
        } catch (e: Exception) {
            cleanStaging(stagingDir)
            return StagingExtractionResult(false, "Extraction error: ${e.message}", calculatedSha256 = calculatedSha)
        }

        return StagingExtractionResult(true, "Extracted to staging successfully", entryCount, totalExtractedBytes, calculatedSha)
    }

    fun promoteStagingToActive(stagingDir: File, activeDir: File): Boolean {
        if (!stagingDir.exists() || !stagingDir.isDirectory) return false
        activeDir.parentFile?.mkdirs()
        if (activeDir.exists()) {
            activeDir.deleteRecursively()
        }
        return stagingDir.renameTo(activeDir)
    }

    private fun cleanStaging(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var bytesRead = 0
        while (bytesRead < buffer.size) {
            val count = input.read(buffer, bytesRead, buffer.size - bytesRead)
            if (count == -1) break
            bytesRead += count
        }
        return bytesRead
    }

    private fun skipFully(input: InputStream, amount: Long) {
        var remaining = amount
        val buf = ByteArray(1024)
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, toRead)
            if (read == -1) break
            remaining -= read
        }
    }

    private fun copyBytes(input: InputStream, output: java.io.OutputStream, count: Long): Long {
        val buffer = ByteArray(8192)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return count - remaining
    }

    private fun isZeroBlock(block: ByteArray): Boolean {
        for (b in block) {
            if (b != 0.toByte()) return false
        }
        return true
    }
}
