package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.deps.impl.HttpArtifactFetcher
import dev.ide.model.impl.format.Json
import dev.ide.ui.backend.PluginStoreService
import dev.ide.ui.backend.UiPluginInstallResult
import dev.ide.ui.backend.UiPluginStoreCatalog
import dev.ide.ui.backend.UiPluginStoreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * [PluginStoreService] over a JSON index published in a public repository. The index is the whole catalogue:
 * one document listing every plugin, its author, its tags, and where its APK lives. That shape is chosen so
 * publishing a plugin is a pull request against a data file — no service to run, no key to manage, and nothing
 * in the IDE has to change when the catalogue grows.
 *
 * Two halves, and the split matters:
 *  - **browsing** ([catalog]/[search]) only reads the index, so the screen is useful offline insofar as the
 *    cached document is, and an unreachable index is reported as a message rather than an empty list;
 *  - **installing** ([install]) downloads the APK and stops there. Handing an APK to the platform installer
 *    is the host's job (it needs a FileProvider + the OS confirmation UI), and *enabling* what gets installed
 *    is the Plugins screen's job, keyed on [dev.ide.core.BackendContext]. So this backend's success means
 *    "a verified archive is on disk", never "the plugin is now running".
 *
 * Every entry is stamped with the version already installed on this device (from the plugin apps the host
 * discovered at launch), which is what lets a card say "Actualizar" instead of offering a duplicate install.
 */
internal class PluginStoreBackend(
    private val ctx: BackendContext,
    /** The index document. A single raw file, so it is cacheable and served over plain HTTPS. */
    private val indexUrl: String = DEFAULT_INDEX_URL,
    private val fetcher: HttpArtifactFetcher = HttpArtifactFetcher(userAgent = USER_AGENT),
) : PluginStoreService {

    override fun storeAvailable(): Boolean = true

    override suspend fun catalog(): UiPluginStoreCatalog = withContext(Dispatchers.IO) {
        val bytes: ByteArray? = try {
            fetcher.fetch(indexUrl)
        } catch (e: Exception) {
            // A 403 / a timeout / an unreachable host is a failure to TELL the user about, not an empty
            // catalogue: the screen names the problem instead of implying nobody has published anything.
            null
        }
        if (bytes == null) {
            return@withContext UiPluginStoreCatalog(error = "No se pudo contactar el catálogo de complementos")
        }
        val items = try {
            parseIndex(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return@withContext UiPluginStoreCatalog(error = "El catálogo de complementos no es válido: ${e.message}")
        }
        UiPluginStoreCatalog(items.map { it.withInstalled() })
    }

    override suspend fun search(query: String): List<UiPluginStoreItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return catalog().items
        return catalog().items.filter { item ->
            item.name.lowercase().contains(q) ||
                item.description.lowercase().contains(q) ||
                item.author.lowercase().contains(q) ||
                item.tags.any { it.lowercase().contains(q) }
        }
    }

    override suspend fun install(id: String): UiPluginInstallResult = withContext(Dispatchers.IO) {
        val item = catalog().items.firstOrNull { it.id == id }
            ?: return@withContext UiPluginInstallResult(false, "El complemento «$id» ya no está en el catálogo")
        val dir = downloadsDir()
            ?: return@withContext UiPluginInstallResult(false, "No hay dónde descargar complementos en este dispositivo")
        // The file name carries the version so two versions never overwrite each other mid-download, and so a
        // failed attempt leaves a recognisable part file rather than a half-written "current" one.
        val dest = dir.resolve("${sanitize(id)}-${sanitize(item.version)}.apk")
        val part = dir.resolve(dest.fileName.toString() + ".part")
        val ok = try {
            fetcher.fetchTo(item.apkUrl, part)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(part) }
            return@withContext UiPluginInstallResult(false, "No se pudo descargar «${item.name}»: ${e.message}")
        }
        if (!ok) {
            runCatching { Files.deleteIfExists(part) }
            return@withContext UiPluginInstallResult(false, "«${item.name}» ya no está disponible para descargar")
        }
        // The publisher's digest is the only thing standing between the user and an arbitrary APK, so it is
        // checked BEFORE the file is moved into place: a mismatching archive is deleted, never offered.
        val expected = item.sha256?.trim()?.lowercase()
        if (!expected.isNullOrEmpty()) {
            val actual = sha256(part)
            if (actual != expected) {
                runCatching { Files.deleteIfExists(part) }
                return@withContext UiPluginInstallResult(
                    false,
                    "El APK de «${item.name}» no coincide con su firma publicada",
                )
            }
        }
        try {
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(part) }
            return@withContext UiPluginInstallResult(false, "No se pudo guardar el APK descargado: ${e.message}")
        }
        UiPluginInstallResult(
            success = true,
            message = "«${item.name}» descargado. Instálalo para activarlo.",
            apkPath = dest.toString(),
        )
    }

    /** `<storage>/plugin-downloads`, created on first use. Null on a host with no project manager (tests). */
    private fun downloadsDir(): Path? {
        val storage = ctx.manager?.storageRoot ?: return null
        val dir = storage.resolve("plugin-downloads")
        Files.createDirectories(dir)
        return dir
    }

    /** The version of [this] plugin already installed on this device, or null when it isn't installed. */
    private fun UiPluginStoreItem.withInstalled(): UiPluginStoreItem {
        val installed = ctx.manager?.env?.installedPlugins?.firstOrNull { it.manifest.id == id }
            ?: return this
        return copy(installedVersion = installed.manifest.version)
    }

    private companion object {
        const val USER_AGENT = "CodeStudio-plugin-store/1.0"

        /**
         * Where the catalogue lives: a raw file in the community repository, so publishing is a pull request.
         * A build that finds nothing here simply has no "Explorar" tab (see [storeAvailable]).
         */
        const val DEFAULT_INDEX_URL =
            "https://raw.githubusercontent.com/AssassinGhostYT/CodeStudio-Plugins/main/plugins.json"

        /** Lowercase hex SHA-256 of [file]. */
        fun sha256(file: Path): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** A filename-safe stand-in for [raw] (a plugin id may legitimately contain dots and dashes). */
        fun sanitize(raw: String): String =
            raw.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
                .joinToString("")
                .ifEmpty { "plugin" }

        /**
         * Read the index. Two shapes are accepted — `{"plugins":[…]}` and a bare `[…]` — because the bare
         * array is what a hand-written file looks like and the wrapper is what leaves room for index metadata
         * later. Entries missing an `apkUrl` are DROPPED rather than listed: there is nothing to install, and
         * a card whose only action fails is worse than no card.
         */
        fun parseIndex(text: String): List<UiPluginStoreItem> {
            val root = Json.parse(text)
            val entries = when (root) {
                is List<*> -> root
                is Map<*, *> -> (root["plugins"] ?: root["plugin"]) as? List<*>
                else -> throw IllegalArgumentException("la raíz debe ser una lista o un objeto")
            } ?: throw IllegalArgumentException("no hay una lista 'plugins'")
            return entries.mapNotNull { entry -> toItem(entry as? Map<*, *> ?: return@mapNotNull null) }
        }

        fun toItem(entry: Map<*, *>): UiPluginStoreItem? {
            val id = string(entry, "id") ?: return null
            val apkUrl = string(entry, "apkUrl") ?: string(entry, "url") ?: return null
            return UiPluginStoreItem(
                id = id,
                name = string(entry, "name") ?: id,
                version = string(entry, "version") ?: "",
                author = string(entry, "author") ?: "",
                description = string(entry, "description") ?: "",
                iconUrl = string(entry, "iconUrl"),
                apkUrl = apkUrl,
                sha256 = string(entry, "sha256"),
                tags = strings(entry, "tags"),
                minHostVersion = string(entry, "minHostVersion"),
                price = string(entry, "price"),
            )
        }

        fun string(entry: Map<*, *>, key: String): String? =
            (entry[key] as? String)?.trim()?.ifEmpty { null }

        fun strings(entry: Map<*, *>, key: String): List<String> = when (val v = entry[key]) {
            is List<*> -> v.mapNotNull { (it as? String)?.trim()?.ifEmpty { null } }
            is String -> v.split(',').mapNotNull { it.trim().ifEmpty { null } }
            else -> emptyList()
        }
    }
}
