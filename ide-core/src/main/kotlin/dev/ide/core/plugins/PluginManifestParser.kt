package dev.ide.core.plugins

import dev.ide.model.impl.format.Json
import dev.ide.model.impl.format.Toml
import dev.ide.plugin.PLUGIN_API_VERSION
import dev.ide.plugin.PluginManifest

/**
 * Reads an installed plugin's packaged manifest. Built-ins carry a [PluginManifest] as a Kotlin literal on
 * their entry point; a plugin built outside the IDE ships the same shape as a data file the IDE reads with
 * no code loaded, and this is the only place that text becomes a manifest.
 *
 * **Two formats, one shape.** The file may be TOML (`res/raw/codestudio_plugin.toml`) or JSON
 * (`res/raw/codestudio_plugin.json`); the host picks by the first non-space character, `{` meaning JSON. The
 * two are the same document, so an author writes whichever they prefer and a project can switch without the
 * loader knowing.
 *
 * TOML:
 * ```toml
 * [plugin]
 * id = "com.example.hola"
 * name = "Hola"
 * version = "1.0.0"
 * apiVersion = 1
 * description = "Añade una ventana de herramientas Hola."
 * entryPoints = ["com.example.hola.HolaPlugin"]
 * uiEntryPoints = ["com.example.hola.HolaUiPlugin"]
 * dependsOn = ["kotlin-language"]
 * capabilities = ["ui.toolWindow"]
 * minHostVersion = "3.11.0"
 * ```
 *
 * JSON:
 * ```json
 * {
 *   "id": "com.example.hola",
 *   "name": "Hola",
 *   "version": "1.0.0",
 *   "apiVersion": 1,
 *   "description": "Añade una ventana de herramientas Hola.",
 *   "entryPoints": ["com.example.hola.HolaPlugin"],
 *   "uiEntryPoints": ["com.example.hola.HolaUiPlugin"],
 *   "dependsOn": ["kotlin-language"],
 *   "capabilities": ["ui.toolWindow"],
 *   "minHostVersion": "3.11.0"
 * }
 * ```
 *
 * Two fields in [PluginManifest] are the host's to decide and are ignored here whatever the file says:
 * `essential` (a plugin cannot make itself undisablable) and `trusted` (which follows from the origin's
 * signature, not from a self-declaration).
 */
object PluginManifestParser {

    /** Parse [text], TOML or JSON. Throws [IllegalArgumentException] with a user-facing message. */
    fun parse(text: String): PluginManifest {
        // JSON objects open with '{'; TOML opens with a table header or a bare key. Nothing in either grammar
        // starts a document with '{', so the first non-space character is an unambiguous format switch.
        val root = if (text.trimStart().startsWith("{")) parseJson(text) else parseToml(text)
        // Accept both a `[plugin]` / `"plugin"` wrapper and bare top-level keys.
        val table = (root["plugin"] as? Map<*, *>) ?: root

        val id = string(table, "id") ?: throw IllegalArgumentException("el manifiesto no tiene 'id'")
        require(ID.matches(id)) { "el id de plugin '$id' debe ser letras, dígitos, '.', '-' o '_'" }
        val entryPoints = strings(table, "entryPoints")
        val uiEntryPoints = strings(table, "uiEntryPoints")
        // Either list alone is a complete plugin: engine-only, UI-only, or both. Neither is nothing.
        require(entryPoints.isNotEmpty() || uiEntryPoints.isNotEmpty()) {
            "el plugin '$id' no declara ni 'entryPoints' ni 'uiEntryPoints'"
        }

        return PluginManifest(
            id = id,
            name = string(table, "name") ?: id,
            version = string(table, "version") ?: "1.0.0",
            apiVersion = int(table, "apiVersion") ?: PLUGIN_API_VERSION,
            dependsOn = strings(table, "dependsOn"),
            description = string(table, "description") ?: "",
            essential = false,
            entryPoints = entryPoints,
            uiEntryPoints = uiEntryPoints,
            capabilities = strings(table, "capabilities"),
            minHostVersion = string(table, "minHostVersion"),
            trusted = false,
        )
    }

    private fun parseToml(text: String): Map<*, *> = try {
        Toml.parse(text)
    } catch (e: Exception) {
        throw IllegalArgumentException("no es TOML válido: ${e.message}")
    }

    private fun parseJson(text: String): Map<*, *> = try {
        Json.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("la raíz de JSON no es un objeto")
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("no es JSON válido: ${e.message}")
    }

    /**
     * The shape of a plugin id. Deliberately as permissive as an Android `applicationId` or a Java package,
     * since that is what a plugin id is normally derived from. Case is part of the id, so it must be written
     * the same way wherever another plugin names it in `dependsOn`.
     */
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    private fun string(table: Map<*, *>, key: String): String? =
        (table[key] as? String)?.trim()?.ifEmpty { null }

    /** An integer field: TOML gives a `Long`, JSON gives a `Long` or a `Double`, and a string is accepted. */
    private fun int(table: Map<*, *>, key: String): Int? = when (val v = table[key]) {
        is Long -> v.toInt()
        is Int -> v
        is Double -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun strings(table: Map<*, *>, key: String): List<String> = when (val v = table[key]) {
        is List<*> -> v.mapNotNull { (it as? String)?.trim()?.ifEmpty { null } }
        is String -> v.split(',').mapNotNull { it.trim().ifEmpty { null } }
        else -> emptyList()
    }
}
