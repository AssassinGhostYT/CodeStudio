package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.ui.backend.ApkAnalyzerEntry
import dev.ide.ui.backend.ApkAnalyzerResult
import dev.ide.ui.backend.ApkAnalyzerService
import dev.ide.ui.backend.ApkAnalyzerSummary
import java.util.zip.ZipFile

/**
 * Read-only APK analyzer using [ZipFile]. No external deps — reuses the same ZIP handling the build engine
 * and installer use ([FrameworkResourceScanner], [AndroidTasks], [ApkInstallerImpl]).
 */
internal class ApkAnalyzerBackend(private val ctx: BackendContext) : ApkAnalyzerService {

    override fun analyze(apkPath: String): ApkAnalyzerResult {
        return ZipFile(apkPath).use { zip ->
            val entries = mutableListOf<ApkAnalyzerEntry>()
            var totalCompressed = 0L
            var totalUncompressed = 0L
            val byCategory = mutableMapOf<String, ApkAnalyzerSummary.CategoryTotal>()

            zip.entries().forEachRemaining { e ->
                val path = e.name
                val compressed = e.compressedSize
                val uncompressed = e.size
                totalCompressed += compressed
                totalUncompressed += uncompressed

                val category = categorize(path)
                val methodCount = if (path.endsWith(".dex")) estimateMethodCount(zip, e) else null

                val entry = ApkAnalyzerEntry(
                    path = path,
                    compressedSize = compressed,
                    uncompressedSize = uncompressed,
                    category = category,
                    methodCount = methodCount,
                )
                entries += entry

                byCategory.merge(category,
                    ApkAnalyzerSummary.CategoryTotal(compressed, uncompressed, 1),
                    { old, _ -> old.copy(
                        compressed = old.compressed + compressed,
                        uncompressed = old.uncompressed + uncompressed,
                        count = old.count + 1,
                    ) })
            }

            // Sort: largest uncompressed first for quick scanning
            entries.sortByDescending { it.uncompressedSize }

            return ApkAnalyzerResult(
                entries = entries,
                summary = ApkAnalyzerSummary(totalCompressed, totalUncompressed, byCategory.toMap()),
            )
        }
    }

    override fun isSupported() = true

    /** Classify a ZIP entry into a human-readable category. */
    private fun categorize(name: String): String = when {
        name.endsWith(".dex") -> "dex"
        name == "resources.arsc" -> "resources"
        name.startsWith("res/") -> "res"
        name.startsWith("assets/") -> "assets"
        name == "AndroidManifest.xml" -> "manifest"
        name.startsWith("lib/") -> "native"
        name.startsWith("META-INF/") -> "manifest" // signatures, services
        else -> "other"
    }

    /** Rough method count for a DEX file — reads the header (no full parse). */
    private fun estimateMethodCount(zip: ZipFile, entry: java.util.zip.ZipEntry): Int? {
        return try {
            val is = zip.getInputStream(entry)
            val header = ByteArray(112) // DEX header size
            if (is.read(header) == 112) {
                // method_ids_off at offset 92 (4 bytes), method_ids_size at 96 (4 bytes)
                // Actually: header[92..95] = method_ids_off, header[96..99] = method_ids_size
                val sizeBytes = header.copyOfRange(96, 100)
                (sizeBytes[3].toInt() shl 24) or ((sizeBytes[2] & 0xFF) shl 16) or
                    ((sizeBytes[1] & 0xFF) shl 8) or (sizeBytes[0] & 0xFF)
            } else null
        } catch (_: Exception) {
            null
        }
    }
}