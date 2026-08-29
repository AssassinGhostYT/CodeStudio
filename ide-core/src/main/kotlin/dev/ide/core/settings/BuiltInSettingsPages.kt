package dev.ide.core.settings

import dev.ide.platform.settings.SettingControl
import dev.ide.platform.settings.SettingsPage
import dev.ide.platform.settings.SettingsScope

/**
 * The built-in Settings pages, declared against the same [SettingsPage] SPI a plugin uses — so built-ins and
 * plugin pages render through one generic path. These are pure *declarations* (control lists); their effects
 * are applied centrally by the backend (it knows the built-in keys), so the hooks stay empty here. A plugin
 * page, by contrast, carries its own [SettingsPage.onChanged]/[SettingsPage.onAction] logic.
 *
 * Control keys are page-local; the host stores them under `settings.<pageId>.<key>` (app scope) — the exact
 * keys [SettingsStore] reads, so a generic write and the typed [IdeSettings] view stay in sync.
 */
object BuiltInSettingsPages {
    const val APPEARANCE = "appearance"
    const val EDITOR = "editor"
    const val COMPLETION = "completion"
    const val ANALYSIS = "analysis"
    const val BUILD = "build"
    /** App-scoped build-runtime page (distinct from the project-scoped [BUILD] page) — holds the
     *  separate-process toggle, which is app-global. See docs/build-process-isolation.md. */
    const val BUILD_RUNTIME = "buildRuntime"
    const val PRIVACY = "privacy"
    /** Project-scoped Compose Preview page — the interpreter sandbox toggles (see `PreviewSandboxPolicy`). */
    const val PREVIEW = "preview"
    const val ABOUT = "about"

    /** Toggle key on the [BUILD_RUNTIME] page: route builds/runs through the isolated `:build` process. */

    const val SEPARATE_PROCESS = "separateProcess"

    /** Toggle key on the [BUILD_RUNTIME] page: weave the IDE log bridge into DEBUG builds so a running app
     *  forwards its logs to the Logcat tab. Read per build (device only); default on. */
    const val INJECT_APP_LOG = "injectAppLog"

    /** Action key on the [BUILD_RUNTIME] page (separate-process-capable hosts only): re-request the runtime
     *  notification permission the isolated build process needs. Handled UI-side (needs the platform permission
     *  launcher) — the SettingsScreen mirrors this key; there's no engine-side effect here. */
    const val BUILD_NOTIFICATIONS = "buildNotifications"

    /** IntSlider key on the [BUILD_RUNTIME] page: the heap (MB) the on-device R8 (release/minify) pass runs
     *  with in a forked VM — larger than the app's own heap cap. Read by `ForkedR8Shrinker` (:ide-android),
     *  which steps down + warns in the build log if the device can't grant it. Android-only effect. */
    const val R8_MAX_HEAP = "r8MaxHeapMb"
    const val R8_MAX_HEAP_DEFAULT = 1536

    /** Choice key on the [BUILD_RUNTIME] page: where the release/minify R8 pass runs. Read by
     *  `ForkedR8Shrinker`. [R8_MODE_FORKED] (the default) runs R8 in a separate VM with more memory than the
     *  app cap, falling back to in-process if the device can't; [R8_MODE_INPROCESS] always runs in-process. */
    const val R8_MODE = "r8Mode"
    const val R8_MODE_FORKED = "forked"
    const val R8_MODE_INPROCESS = "inprocess"
    const val R8_MODE_DEFAULT = R8_MODE_FORKED

    /** App preference (NOT a user control): the largest heap (MB) a forked VM grants R8 on this device,
     *  measured once per app version in the background (`0` = forking unavailable, absent = not yet measured).
     *  The host (:ide-android) writes it; the settings UI reads it for the slider's MAX and the shrinker for
     *  its default heap, so the user can only scale DOWN from the real device limit. */
    const val R8_CEILING_PREF = "r8.detectedCeilingMb"

    /** IntSlider key on [BUILD_RUNTIME]: input size (MB) at/above which an on-device debug-dex step (the
     *  dexBuilder archive) runs in a separate VM instead of the app heap. Read by `ForkedD8Dexer` (:ide-android),
     *  and only when R8 execution is Forked VM. Android-only. Lower = safer on small heaps but more VM spawns. */
    const val DEX_OFFHEAP_MB = "dexOffHeapMb"
    const val DEX_OFFHEAP_MB_DEFAULT = 8

    /** IntSlider key on [BUILD_RUNTIME]: the most classes merged into Dalvik bytecode in one batch on a large
     *  app (debug, native multidex). Read by `DexMergeTask` via the on-device `AndroidDeviceTools.mergeChunkProvider`.
     *  Smaller = lower peak memory + slightly larger APK; larger = tighter packing + more memory. Android-only. */
    const val DEX_MERGE_BATCH = "dexMergeBatch"
    const val DEX_MERGE_BATCH_DEFAULT = 6000

    /** IntSlider key on [BUILD_RUNTIME]: the most forked dexing VMs (the dex merge / off-heap archive) allowed
     *  to run at once. `0` = auto (sized from available device RAM ÷ the forked-VM heap). Read by `ForkedD8Dexer`
     *  (:ide-android), and only when R8 execution is Forked VM. Higher = faster merges on roomy devices but more
     *  RAM committed at once; `0`/lower is safer on tight devices. Android-only. */
    const val DEX_FORK_CONCURRENCY = "dexForkConcurrency"
    const val DEX_FORK_CONCURRENCY_DEFAULT = 0

    /** Toggle key on the [ANALYSIS] page: write per-pass / per-stage editor timings to the log (diagnostic).
     *  Applied by the backend — it flips the shared `PerfTrace` flag. */
    const val PERF_LOGGING = "perfLogging"

    /** Toggle keys on the [PREVIEW] page — `sandbox` + a capitalized `SandboxCategory.id`. Read by
     *  `ComposePreviewService.sandboxCategories()` per preview open; all default ON (restricted). */
    const val SANDBOX_FILE_IO = "sandboxFileIo"
    const val SANDBOX_NETWORK = "sandboxNetwork"
    const val SANDBOX_ANDROID = "sandboxAndroidSystem"
    const val SANDBOX_PROCESS = "sandboxProcessControl"

    /** Render the Compose `@Preview` in the `:preview` OS process (docs/compose-preview-isolation.md). Default
     *  ON: a runaway recomposition or crash then pegs only `:preview`, not the IDE. The isolated path now sizes
     *  wrap-to-content previews to match the in-process host; `@PreviewParameter` / locale previews still fall
     *  back in-process (not covered yet), as does any remote failure. Read by `ComposePreviewService.previewIsolated()`. */
    const val PREVIEW_ISOLATE = "previewIsolate"

    // Keys the backend special-cases (routed to a non-generic-store effect).
    const val CONFLICT_POLICY = "conflictPolicy"
    const val ANALYTICS = "analytics"
    const val CLEAR_CACHES = "clearCaches"
    const val VIEW_LOGS = "viewLogs"
    const val BACKUP = "backup"

    /** The conflict-policy choice values (mirror `dev.ide.deps.ConflictPolicy`). */
    const val CONFLICT_NEWEST = "newest"
    const val CONFLICT_PINNED = "pinned"
    const val CONFLICT_FAIL = "failOnConflict"

    private val d = IdeSettings()

    /** All built-in pages in display order. [analyticsAvailable] gates the analytics toggle on the Privacy page.
     *  Code Style is not here: it has its own dedicated screen (the formatting profiles are per-language). */
    fun all(analyticsAvailable: Boolean): List<SettingsPage> = listOf(
        appearance, editor, completion, analysis, preview, build, buildRuntime, privacy(analyticsAvailable), about,
    )


    private val appearance = page(APPEARANCE, "Apariencia", "eye", 0) {
        listOf(
            SettingControl.Choice(
                "themeMode", "Tema", "Usar un tema fijo o seguir el sistema operativo",
                default = d.themeMode,
                options = listOf(
                    SettingControl.Choice.Option(IdeSettings.THEME_LIGHT, "Claro"),
                    SettingControl.Choice.Option(IdeSettings.THEME_DARK, "Oscuro"),
                    SettingControl.Choice.Option(IdeSettings.THEME_SYSTEM, "Sistema"),
                ),
            ),
            SettingControl.Choice(
                "accent", "Color de acento", "El color de resaltado del que se genera todo el tema",
                default = d.accent,
                options = listOf(
                    SettingControl.Choice.Option(IdeSettings.ACCENT_DYNAMIC, "Dinámico"),
                    SettingControl.Choice.Option(IdeSettings.ACCENT_VIOLET, "Violeta"),
                    SettingControl.Choice.Option(IdeSettings.ACCENT_TEAL, "Verde azulado"),
                    SettingControl.Choice.Option(IdeSettings.ACCENT_ORANGE, "Naranja (antiguo)"),
                    SettingControl.Choice.Option(IdeSettings.ACCENT_CUSTOM, "Personalizado"),
                ),
            ),
            SettingControl.Color(
                "accentColor", "Color personalizado", "Elige cualquier color; el tema se regenera a partir de él",
                default = d.accentColor,
            ),
        )
    }

    private val editor = page(EDITOR, "Editor", "code", 10) {
        listOf(
            SettingControl.IntSlider("fontScale", "Tamaño de letra", default = (d.editorFontScale * 100).toInt(), min = 70, max = 200, step = 5, unit = "%"),
            SettingControl.Choice(
                "codeFont", "Fuente de código",
                default = d.codeFont,
                options = listOf(
                    SettingControl.Choice.Option(IdeSettings.CODE_FONT_JETBRAINS, "JetBrains Mono"),
                    SettingControl.Choice.Option(IdeSettings.CODE_FONT_MONOSPACE, "Monoespaciada del sistema"),
                ),
            ),
            SettingControl.Toggle("fontLigatures", "Ligaduras de fuente", "Dibujar ligaduras de programación (-> != >= …) cuando la fuente las tiene", default = d.fontLigatures),
            SettingControl.Toggle("inlayHints", "Sugerencias incrustadas", "Tipos inferidos y nombres de parámetros, mostrados en línea", default = d.inlayHints),
            SettingControl.Toggle("semanticHighlighting", "Resaltado semántico", "Coloreado por tipo superpuesto al analizador", default = d.semanticHighlighting),
            SettingControl.Toggle("codeFolding", "Plegado de código", "Plegar imports, cuerpos y comentarios de bloque", default = d.codeFolding),
            SettingControl.Toggle("wordWrap", "Ajuste de línea", "Ajustar las líneas largas al borde de la vista en vez de desplazarse en horizontal", default = d.wordWrap),
            SettingControl.Toggle("wrapIndent", "Indentar líneas ajustadas", "Alinear las continuaciones de una línea ajustada con su sangría (cuando el ajuste está activo)", default = d.wrapIndent),
            SettingControl.Toggle("horizontalScrollbar", "Barra de desplazamiento horizontal", "Mostrar una barra en el borde inferior cuando una línea supera la vista (el ajuste no deja nada que desplazar)", default = d.horizontalScrollbar),
            SettingControl.Toggle("twoAxisScroll", "Desplazamiento en dos ejes", "Arrastrar en cualquier dirección para desplazar ambos ejes a la vez (táctil)", default = d.twoAxisScroll, group = "Gestos"),
            SettingControl.Toggle("pinchZoom", "Pellizcar para hacer zoom", "Pellizca con dos dedos para cambiar el tamaño de la fuente", default = d.pinchZoom, group = "Gestos"),
            SettingControl.Toggle("softKeyboardSuggestions", "Sugerencias del teclado", "Deja que el teclado autocorrija, sugiera y añada espacios (un teclado normal). Desactívalo para escribir código tal cual, para que un '.' escrito no reciba un espacio añadido automáticamente, a costa de la franja de sugerencias.", default = d.softKeyboardSuggestions, group = "Teclado"),
        )
    }

    private val completion = page(COMPLETION, "Finalización de código", "sparkle", 20) {
        listOf(
            SettingControl.Toggle("autoPopup", "Mostrar sugerencias automáticamente", "Mostrar la lista mientras escribes (off = solo Ctrl-Espacio)", default = d.completionAutoPopup),
            SettingControl.Toggle("postfixTemplates", "Plantillas postfijas", "Ofrecer finalizaciones .val / .if / .notnull / …", default = d.postfixTemplates),
            SettingControl.Toggle("wordCompletion", "Finalización de palabras", "Ofrecer las palabras ya presentes en el archivo como alternativa", default = d.wordCompletion),
            SettingControl.IntSlider("delayMs", "Retardo de aparición", "Cuánto tiempo tras una pulsación aparece la lista", default = d.completionDelayMs, min = IdeSettings.MIN_COMPLETION_DELAY_MS, max = IdeSettings.MAX_COMPLETION_DELAY_MS, step = 10, unit = "ms", advanced = true),
            SettingControl.IntSlider("maxItems", "Máximo de sugerencias", default = d.completionMaxItems, min = IdeSettings.MIN_COMPLETION_MAX_ITEMS, max = IdeSettings.MAX_COMPLETION_MAX_ITEMS, step = 10, advanced = true),
        )
    }

    private val analysis = page(ANALYSIS, "Análisis e inspecciones", "lightbulb", 30) {
        listOf(
            SettingControl.Toggle("onTheFly", "Analizar al instante", "Mostrar diagnósticos mientras escribes (off = solo al compilar)", default = d.analyzeOnTheFly),
            SettingControl.IntSlider("reparseDelayMs", "Retardo de reanálisis", "Periodo de espera tras una pulsación antes de reanalizar", default = d.reparseDelayMs, min = IdeSettings.MIN_REPARSE_DELAY_MS, max = IdeSettings.MAX_REPARSE_DELAY_MS, step = 50, unit = "ms", advanced = true),
            SettingControl.Toggle(PERF_LOGGING, "Registrar tiempos de análisis", "Diagnóstico: escribe al registro los tiempos por pasada (semántica / diagnóstico / pliegues / incrustados / vistas previas) y por etapa para localizar qué hace lento un archivo. Léelos en Privacidad → Ver registros. Desactivado por defecto.", default = d.analysisPerfLogging, advanced = true),
        )
    }

    // Per-project: whether preview code may escape the sandbox is a property of the project you're editing
    // (your own app vs. an untrusted sample), not of the device. Applies to previews opened after a change.
    private val preview = page(PREVIEW, "Vista previa", "image", 35, scope = SettingsScope.PROJECT) {
        listOf(
            SettingControl.Toggle(
                SANDBOX_FILE_IO, "Bloquear acceso a archivos",
                "Evitar que el código en vista previa lea o escriba archivos (java.io / java.nio / kotlin.io). Las llamadas bloqueadas devuelven null y aparecen en el indicador de la vista previa. Se aplica a las vistas previas abiertas después del cambio.",
                default = true, group = "Aislar en vista previa",
            ),
            SettingControl.Toggle(
                SANDBOX_NETWORK, "Bloquear acceso a la red",
                "Evitar que el código en vista previa abra sockets o conexiones HTTP (java.net, OkHttp, Ktor).",
                default = true, group = "Aislar en vista previa",
            ),
            SettingControl.Toggle(
                SANDBOX_ANDROID, "Bloquear llamadas de sistema de Android",
                "Evitar que el código en vista previa lance actividades/servicios, envíe broadcasts, use servicios del sistema, ContentResolver o SharedPreferences. Las lecturas de recursos y densidad siguen disponibles.",
                default = true, group = "Aislar en vista previa",
            ),
            SettingControl.Toggle(
                SANDBOX_PROCESS, "Bloquear procesos y reflexión",
                "Evitar que el código en vista previa ejecute procesos, llame a System.exit, cargue librerías nativas o invoque miembros por reflexión.",
                default = true, group = "Aislar en vista previa",
            ),
            SettingControl.Toggle(
                PREVIEW_ISOLATE, "Renderizar en un proceso separado",
                "Renderizar el @Preview en el proceso :preview del SO en lugar del IDE, para que una recomposición descontrolada o un fallo no pueda tumbar el IDE. Vuelve al renderizador en proceso para vistas previas con @PreviewParameter / locales y ante cualquier fallo remoto. Desactívalo para renderizar siempre en proceso (más interactivo, pero un fallo de la vista previa puede afectar al IDE).",
                default = true, group = "Proceso de la vista previa",
            ),
        )
    }

    private val build = page(BUILD, "Compilación y dependencias", "hammer", 40, scope = SettingsScope.PROJECT) {
        listOf(
            SettingControl.Choice(
                CONFLICT_POLICY, "Conflictos de dependencias", "Qué versión gana cuando se piden dos en el grafo",
                default = CONFLICT_NEWEST,
                options = listOf(
                    SettingControl.Choice.Option(CONFLICT_NEWEST, "La más reciente"),
                    SettingControl.Choice.Option(CONFLICT_PINNED, "Gana la directa"),
                    SettingControl.Choice.Option(CONFLICT_FAIL, "Fallo ante conflicto"),
                ),
            ),
        )
    }

    // App-global (not per-project): running the build in its own process is about this device's memory
    // headroom + your robustness preference, the same for every project. Default ON. The effect is applied
    // by the backend (it reads `settings.buildRuntime.separateProcess`); see docs/build-process-isolation.md.
    private val buildRuntime = page(BUILD_RUNTIME, "Entorno de compilación", "hammer", 45) {
        listOf(
            SettingControl.Toggle(
                SEPARATE_PROCESS, "Compilar en un proceso separado",
                "Ejecutar las compilaciones y tu programa en un proceso aislado para que un fallo de memoria no pueda tumbar el IDE. Off = compilar en el mismo proceso (menos memoria, sin aislamiento). Tiene efecto la próxima vez que abras un proyecto.",
                default = true,
            ),
            SettingControl.Toggle(
                INJECT_APP_LOG, "Reenviar registros de la app",
                "En una compilación de depuración, inyectar un pequeño puente de registros en tu app para que sus registros (logcat, println, fallos) lleguen a la pestaña Logcat. Solo compilaciones de depuración — las de publicación nunca se modifican. Se aplica en la próxima compilación.",
                default = true,
            ),
            // The Build Runtime page's R8 controls are rendered dynamically by SettingsBackend (the slider's
            // max is this device's measured forked-VM limit, and it's hidden in In-process mode), so these
            // static descriptors only supply keys / scope / defaults — their descriptions aren't shown.
            SettingControl.Choice(
                R8_MODE, "Ejecución de R8", null,
                default = R8_MODE_DEFAULT,
                options = listOf(
                    SettingControl.Choice.Option(R8_MODE_FORKED, "Máquina separada"),
                    SettingControl.Choice.Option(R8_MODE_INPROCESS, "En proceso"),
                ),
            ),
            SettingControl.IntSlider(
                R8_MAX_HEAP, "Memoria de la máquina R8", null,
                default = R8_MAX_HEAP_DEFAULT, min = 768, max = 4096, step = 128, unit = "MB",
            ),
            // Rendered dynamically by SettingsBackend (rich descriptions); these descriptors only carry the
            // key / default / scope for the write path. Debug-build dexing memory knobs (R8 above = release).
            SettingControl.IntSlider(
                DEX_OFFHEAP_MB, "Umbral de dexing fuera de memoria", null,
                default = DEX_OFFHEAP_MB_DEFAULT, min = 2, max = 64, step = 2, unit = "MB", advanced = true,
            ),
            SettingControl.IntSlider(
                DEX_MERGE_BATCH, "Tamaño de lote de fusión de dex", null,
                default = DEX_MERGE_BATCH_DEFAULT, min = 1000, max = 20000, step = 1000, advanced = true,
            ),
            SettingControl.IntSlider(
                DEX_FORK_CONCURRENCY, "Máx. de procesos dex concurrentes", null,
                default = DEX_FORK_CONCURRENCY_DEFAULT, min = 0, max = 4, step = 1, advanced = true,
            ),
        )
    }

    private fun privacy(analyticsAvailable: Boolean) = page(PRIVACY, "Privacidad y datos", "info", 50) {
        buildList {
            if (analyticsAvailable) {
                add(SettingControl.Toggle(ANALYTICS, "Compartir análisis de rendimiento", "Solo métricas de rendimiento anónimas, nunca tu código ni nombres de archivo", default = false, group = "Privacidad"))
            }
            add(SettingControl.Action(CLEAR_CACHES, "Limpiar cachés", "Liberar cachés regenerables de dependencias / lenguaje / vistas previas (nunca el código fuente)", buttonLabel = "Limpiar", group = "Almacenamiento"))
            add(SettingControl.Action(VIEW_LOGS, "Ver registros", "Actividad reciente del editor, análisis y compilación", buttonLabel = "Abrir", group = "Almacenamiento"))
            add(SettingControl.Action(BACKUP, "Respaldar proyectos", "Exportar cada proyecto a un único zip", buttonLabel = "Respaldar", group = "Almacenamiento"))
        }
    }

    private val about = page(ABOUT, "Acerca de CodeStudio", "info", 60) {
        listOf(
            SettingControl.Action("appVersion", "IDE CodeStudio", "v1.0.0 (Compilación 2026.08)", buttonLabel = "v1.0.0", group = "Aplicación"),
            SettingControl.Action("author", "Desarrollador", "Creado por AssassinGhostYT", buttonLabel = "@AssassinGhostYT", group = "Aplicación"),
            SettingControl.Action("repository", "Código fuente", "https://github.com/AssassinGhostYT/CodeStudio", buttonLabel = "GitHub", group = "Enlaces"),
            SettingControl.Action("sponsor", "Patrocinar este proyecto", "Apoya a AssassinGhostYT en GitHub Sponsors", buttonLabel = "Patrocinar", group = "Enlaces"),
        )
    }

    /** Small builder for an anonymous built-in [SettingsPage] (empty hooks; effects are applied by the backend). */

    private fun page(
        id: String, title: String, iconId: String, order: Int,
        scope: SettingsScope = SettingsScope.APPLICATION,
        controlsProvider: () -> List<SettingControl>,
    ): SettingsPage = object : SettingsPage {
        override val id = id
        override val title = title
        override val iconId = iconId
        override val scope = scope
        override val order = order
        override fun controls() = controlsProvider()
    }

    /** Whether [page] is the built-in Analysis page that wants the inspection list appended. */
    fun isInspectionsPage(page: SettingsPage): Boolean = page.id == ANALYSIS
}
