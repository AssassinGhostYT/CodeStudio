package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.model.template.ProjectTemplate
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.UiStoreCatalog
import dev.ide.ui.backend.UiStoreInstallResult
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [StoreService] for the home screen's Store tab. This is the UI-shell wiring: the catalog is served from the
 * bundled [ProjectTemplate]s (the real, installable content) plus a curated set of preview sample projects,
 * and the Community shelf is left empty. The interface is exactly what a remote, submission-backed catalog
 * will later implement, so the UI does not change when [catalog]/[search]/[install] move to a backend.
 *
 * Template items route through the normal Create-Project flow (the UI opens the configure form pre-selected
 * to [UiStoreItem.templateId]); only sample/community items would download through [install], which is not
 * wired yet (those items are marked [UiStoreItem.available] = false).
 */
internal class StoreBackend(private val ctx: BackendContext) : StoreService {

    private fun templates(): List<ProjectTemplate> =
        ctx.servicesOrNull?.projectTemplates() ?: ctx.manager?.projectTemplates() ?: emptyList()

    override fun storeAvailable(): Boolean = templates().isNotEmpty()

    override suspend fun catalog(): UiStoreCatalog = withContext(Dispatchers.Default) {
        val (sampleTemplates, starterTemplates) = templates().partition { isSample(it) }
        val starters = starterTemplates.map { toItem(it, UiStoreItemKind.Template) }
        val samples = sampleTemplates.map { toItem(it, UiStoreItemKind.Sample) }
        // Featured = the curated highlights, falling back to the first few starters so the carousel is
        // never empty on a host that contributes its own (uncurated) templates.
        val featured = starters.filter { it.featured }.ifEmpty { starters.take(3) }
        val categories = buildList {
            (starters + samples).map { it.category }.distinct().forEach(::add)
            add(CATEGORY_COMMUNITY)
        }
        val sections = listOf(
            UiStoreSection("templates", "Plantillas iniciales", "Crea un proyecto nuevo a partir de un andamiaje seleccionado", starters),
            UiStoreSection("samples", "Proyectos de ejemplo", "Apps de ejemplo completas y documentadas que puedes compilar y ejecutar", samples),
            UiStoreSection("community", "Comunidad", "Proyectos compartidos por la comunidad", emptyList()),
        )
        UiStoreCatalog(featured = featured, categories = categories, sections = sections)
    }

    override suspend fun search(query: String, category: String?): List<UiStoreItem> = withContext(Dispatchers.Default) {
        val all = templates().map { toItem(it, if (isSample(it)) UiStoreItemKind.Sample else UiStoreItemKind.Template) }
        val q = query.trim().lowercase()
        all.filter { item -> matchesCategory(item, category) && matchesQuery(item, q) }
    }

    override suspend fun install(id: String, args: Map<String, String>): UiStoreInstallResult {
        // Templates AND samples are both created through the Create-Project flow (the UI routes any item with
        // a templateId there); only the future community downloads would land here.
        return UiStoreInstallResult(false, "Los proyectos de la comunidad llegarán pronto")
    }

    /** Sample projects are registered as `sample-`-prefixed templates so they share the create path but list
     *  under "Sample projects" rather than "Starter templates". */
    private fun isSample(t: ProjectTemplate): Boolean = t.id.value.startsWith("sample-")

    private fun matchesCategory(item: UiStoreItem, category: String?): Boolean = when (category) {
        null -> true
        CATEGORY_COMMUNITY -> item.kind == UiStoreItemKind.Community
        else -> item.category.equals(category, ignoreCase = true)
    }

    private fun matchesQuery(item: UiStoreItem, q: String): Boolean = q.isEmpty() ||
        item.title.lowercase().contains(q) ||
        item.summary.lowercase().contains(q) ||
        item.category.lowercase().contains(q) ||
        item.tags.any { it.lowercase().contains(q) }

    private fun toItem(t: ProjectTemplate, kind: UiStoreItemKind): UiStoreItem {
        val meta = CURATION[t.id.value]
        return UiStoreItem(
            id = "${if (kind == UiStoreItemKind.Sample) "sample" else "template"}:${t.id.value}",
            kind = kind,
            title = t.displayName,
            summary = t.description,
            category = t.category.displayName,
            iconId = t.iconId,
            tags = meta?.tags ?: listOf(t.category.displayName),
            featured = meta?.featured ?: false,
            accentColor = meta?.accent,
            installs = meta?.installs ?: -1,
            templateId = t.id.value,
            available = true,
            highlights = meta?.highlights ?: emptyList(),
            language = meta?.language ?: t.category.displayName.takeIf { it == "Java" || it == "Kotlin" },
            previewKey = t.id.value.takeIf { it in PREVIEW_SAMPLES },
        )
    }

    /** Per-template curation layered over the template's own metadata (featured flag, brand accent, tags,
     *  a soft usage count, "what you get" highlights, and the primary language) — all shown in the store. */
    private data class Curation(
        val featured: Boolean = false,
        val accent: Long? = null,
        val tags: List<String> = emptyList(),
        val installs: Int = -1,
        val highlights: List<String> = emptyList(),
        val language: String? = null,
    )

    private companion object {
        const val CATEGORY_COMMUNITY = "Comunidad"

        /** Sample template ids that ship a built-in preview screenshot ([UiStoreItem.previewKey]). */
        val PREVIEW_SAMPLES = setOf("sample-snake", "sample-tictactoe", "sample-memory", "sample-2048")

        val CURATION: Map<String, Curation> = mapOf(
            "compose-app" to Curation(
                featured = true, accent = 0xFF3FBDD9, tags = listOf("Jetpack Compose", "Material 3", "Kotlin"), installs = 12800, language = "Kotlin",
                highlights = listOf("Interfaz con Jetpack Compose", "Tema Material 3", "Una Activity lista para ejecutar", "Compila a un APK instalable"),
            ),
            "android-material-you" to Curation(
                featured = true, accent = 0xFFB487F7, tags = listOf("Material You", "Vistas", "Kotlin"), installs = 6400, language = "Kotlin",
                highlights = listOf("Material You (color dinámico)", "Plantillas XML + vistas", "Una Activity lista para ejecutar"),
            ),
            "android-app" to Curation(
                featured = true, accent = 0xFF3DDC84, tags = listOf("Android", "Actividad", "Plantillas XML"), installs = 21500, language = "Kotlin",
                highlights = listOf("Una Activity + plantilla XML", "Recursos conectados", "Compila a un APK instalable"),
            ),
            "kotlin-console" to Curation(tags = listOf("Kotlin", "Consola"), installs = 9300, language = "Kotlin", highlights = listOf("Un main() de primer nivel", "Inteligencia completa del editor", "Se ejecuta en la consola")),
            "java-console" to Curation(tags = listOf("Java", "Consola"), installs = 8100, language = "Java", highlights = listOf("Un punto de entrada main()", "Inteligencia completa del editor", "Se ejecuta en la consola")),
            "android-library" to Curation(tags = listOf("Android", "AAR"), installs = 3200, language = "Kotlin", highlights = listOf("Un módulo android-lib reutilizable", "Se publica como AAR")),
            // Sample projects (complete, runnable examples).
            "sample-calculator" to Curation(
                accent = 0xFFF89820, tags = listOf("Java", "Analizador", "REPL"), installs = 4200, language = "Java",
                highlights = listOf("Un bucle interactivo leer-evaluar-imprimir", "Un analizador de expresiones descendente", "Precedencia de operadores y paréntesis", "Sin dependencias y muy documentado"),
            ),
            "sample-notes" to Curation(
                accent = 0xFF7F52FF, tags = listOf("Kotlin", "CRUD", "CLI"), installs = 5600, language = "Kotlin",
                highlights = listOf("Un bucle de comandos: add/list/done/rm/find", "Lee la entrada línea a línea", "Modelo separado de la vista (probable)", "Sin dependencias y muy documentado"),
            ),
            "sample-weather" to Curation(
                accent = 0xFF3FBDD9, tags = listOf("Kotlin", "CLI", "Datos"), installs = 3100, language = "Kotlin",
                highlights = listOf("Escribe una ciudad y obtén su pronóstico", "Lee la entrada en un bucle", "Muestra cómo cambiar a una API real", "Sin dependencias"),
            ),
            // Jetpack Compose sample games (complete, runnable Compose apps).
            "sample-snake" to Curation(
                accent = 0xFF00E676, tags = listOf("Jetpack Compose", "Juego", "Canvas"), installs = 7400, language = "Kotlin",
                highlights = listOf("Dibujo en Canvas + bucle de juego", "Controles por gestos", "Marcador en vivo y récord", "Estética Material 3 neón"),
            ),
            "sample-tictactoe" to Curation(
                accent = 0xFF22D3EE, tags = listOf("Jetpack Compose", "Juego", "Material 3"), installs = 5200, language = "Kotlin",
                highlights = listOf("Lógica de juego para dos jugadores", "Fichas animadas y línea ganadora resaltada", "Estado elevado correctamente", "Tema Material 3"),
            ),
            "sample-memory" to Curation(
                accent = 0xFF7C3AED, tags = listOf("Jetpack Compose", "Juego", "Animación"), installs = 4300, language = "Kotlin",
                highlights = listOf("Animación de volteo 3D", "Lógica de parejas + contadores", "Interfaz con degradado de color", "Buena introducción a la animación con Compose"),
            ),
            "sample-2048" to Curation(
                accent = 0xFFEDC22E, tags = listOf("Jetpack Compose", "Juego", "Puzle"), installs = 6100, language = "Kotlin",
                highlights = listOf("Lógica de combinación con deslizamiento", "Colores de fichas animados", "Marcador y récord", "Modelado limpio del estado de la cuadrícula"),
            ),
        )
    }
}
