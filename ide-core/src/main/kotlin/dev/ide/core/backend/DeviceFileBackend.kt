package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.ui.backend.DeviceFileService
import dev.ide.ui.backend.UiDeviceEntry
import dev.ide.ui.backend.UiDeviceListing
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * The Device File Explorer backend: browse the filesystem of the device that runs the IDE and move files
 * between it and the open project. There is no ADB and no root here — the "device" IS this process, so only
 * paths the app can already read are listed (its sandbox, `/sdcard` under the shared-storage rules,
 * `/data/local/tmp`, `/`). Everything goes through [java.io.File] so the module stays pure JVM and compiles
 * for both the Android and the desktop launchers (where it browses the host filesystem for testing).
 *
 * Pure host-agnostic code — no [BackendContext] state is needed, but it is kept for the backend wiring
 * pattern (each `*Backend` receives the aggregator).
 */
internal class DeviceFileBackend(private val ctx: BackendContext) : DeviceFileService {

    override fun defaultPath(): String? = homePath()
        ?: KNOWN_ROOTS.firstOrNull { File(it).let { f -> f.isDirectory && f.canRead() } }
        ?: "/"

    /**
     * CodeStudio's own storage home (`/storage/emulated/0/Android/data/<pkg>/files` on Android) — the
     * projects, the SDK and the caches. It is the one place the app can always read, so the explorer opens
     * here and the user's own sources (`MainActivity.kt`, `build.gradle.kts`, …) are right there.
     */
    override fun homePath(): String? = ctx.manager?.storageRoot?.toString()?.takeIf { File(it).isDirectory }

    override fun roots(): List<UiDeviceEntry> {
        val home = homePath()
        return (listOfNotNull(home) + KNOWN_ROOTS)
            .distinct()
            .filter { File(it).exists() }
            .map { toEntry(File(it)) }
            .map { if (it.path == home) it.copy(name = "CodeStudio") else it }
    }

    override fun list(path: String): UiDeviceListing {
        val dir = File(path)
        if (!dir.exists()) return UiDeviceListing(path, error = "not-found")
        if (!dir.isDirectory) return UiDeviceListing(path, error = "not-a-directory")
        if (!dir.canRead()) return UiDeviceListing(path, error = "permission-denied")
        return try {
            val children = dir.listFiles().orEmpty()
                .map { toEntry(it) }
                .sortedWith(compareByDescending<UiDeviceEntry> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
            UiDeviceListing(path, entries = children)
        } catch (e: Exception) {
            UiDeviceListing(path, error = e.message ?: "list-failed")
        }
    }

    override fun pullToProject(devicePath: String, projectDirPath: String): String? = runCatching {
        val src = File(devicePath)
        if (!src.isFile) return null
        val destDir = File(projectDirPath)
        if (!destDir.isDirectory && !destDir.mkdirs()) return null
        val dest = uniqueChild(destDir, src.name)
        src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        ctx.bumpFileSystemEpoch()
        dest.absolutePath
    }.getOrNull()

    override fun pushFromProject(projectFilePath: String, deviceDirPath: String): String? = runCatching {
        val src = File(projectFilePath)
        if (!src.isFile) return null
        val destDir = File(deviceDirPath)
        if (!destDir.isDirectory && !destDir.mkdirs()) return null
        if (!destDir.canWrite()) return null
        val dest = uniqueChild(destDir, src.name)
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        dest.absolutePath
    }.getOrNull()

    override fun createDirectory(path: String): Boolean = runCatching {
        val dir = File(path)
        (dir.isDirectory || dir.mkdirs())
    }.getOrDefault(false)

    override fun delete(path: String): Boolean = runCatching {
        val target = File(path)
        if (!target.exists()) return false
        target.deleteRecursively()
    }.getOrDefault(false)

    override fun isSupported(): Boolean = true

    /** A fresh `parent/name`, or `parent/name (n)` when one already exists, so a pull/push never clobbers. */
    private fun uniqueChild(parent: File, name: String): File {
        var candidate = File(parent, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(parent, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    private fun toEntry(file: File): UiDeviceEntry = UiDeviceEntry(
        name = file.name.ifEmpty { file.absolutePath },
        path = file.absolutePath,
        isDirectory = file.isDirectory,
        sizeBytes = if (file.isFile) file.length() else 0L,
        lastModifiedMs = file.lastModified(),
        readable = file.canRead(),
    )

    private companion object {
        /** Quick links offered at the top of the explorer. Only existing ones are shown. */
        val KNOWN_ROOTS = listOf("/sdcard", "/storage/emulated/0", "/data/local/tmp", "/", "/system")
    }
}
