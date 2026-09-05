package dev.ide.ui.icons

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** How a tree node is drawn, once its icon id is resolved by [TreeIcons]. */
sealed interface TreeIcon {
    /** A single stroked/filled glyph, tinted by [tint]. */
    data class Glyph(val image: ImageVector, val tint: IconTint = IconTint.Secondary) : TreeIcon
    /** A folder that swaps its glyph open/closed, tinted by [tint]. */
    data class Folder(val closed: ImageVector, val open: ImageVector, val tint: IconTint = IconTint.Secondary) : TreeIcon
    /** A letter-in-rounded-square badge in a fixed [color] (e.g. "J" for Java). */
    data class Badge(val text: String, val color: Color) : TreeIcon
    /** A fully-colored vector (a Material Icon Theme icon) — already carries its own brand colors. */
    data class Vector(val image: ImageVector) : TreeIcon
}

/**
 * A tint resolved against the live theme at render time, or a [Fixed] brand color (e.g. Android green).
 * Icons are registered outside composition, so the theme-backed tints can't be baked in eagerly.
 */
sealed interface IconTint {
    object Accent : IconTint
    object Primary : IconTint
    object Secondary : IconTint
    object Tertiary : IconTint
    object Success : IconTint
    object Warning : IconTint
    object Error : IconTint
    object Info : IconTint
    /** The icon already carries its own brand colors; renderers must not tint it. */
    object Original : IconTint
    /** Same as [Original] — material theme vectors ship their own brand colors. */
    object None : IconTint
    data class Fixed(val color: Color) : IconTint
}

/**
 * The file-tree icon registry: maps an icon id (the string a backend `FileIconProvider` returns) to a
 * renderable [TreeIcon]. Built-ins are registered at init; plugins/launchers may [register] more or
 * override existing ids — the backing map is observable so a late registration recomposes the tree.
 * Unknown ids fall back to a muted file glyph.
 */
object TreeIcons {
    private val fallback = TreeIcon.Glyph(CaIcons.file, IconTint.Tertiary)
    private val registry = mutableStateMapOf<String, TreeIcon>()

    /** Register (or override) the icon for [iconId]. */
    fun register(iconId: String, icon: TreeIcon) { registry[iconId] = icon }

    /**
     * Material Icon Theme names that we ship as hand-tuned [BrandIcons] glyphs instead of the
     * generated `vectorPaths` (the SVG→path converter mangled arcs/relative commands for these).
     * Backend `mat:<name>` ids must resolve to the same brand glyph the legacy id uses.
     */
    private val brandByMaterialName = mapOf(
        "java" to "java",
        "kotlin" to "kotlin",
        "xml" to "xml",
        "dart" to "dart",
        "flutter" to "flutter",
    )

    /** The icon for [iconId], or a muted file glyph if none is registered — material icons are built
     *  lazily from the generated theme data (ids `mat:<icon>` and `mat-folder:<name>`). */
    fun resolve(iconId: String): TreeIcon {
        registry[iconId]?.let { return it }
        if (MaterialIcons.isFile(iconId)) {
            val name = iconId.removePrefix(MaterialIcons.FILE_PREFIX)
            brandByMaterialName[name]?.let { brandId -> registry[brandId]?.let { return it } }
            MaterialIcons.file(name)?.let { return TreeIcon.Vector(it) }
        }
        if (MaterialIcons.isFolder(iconId)) {
            val name = iconId.removePrefix(MaterialIcons.FOLDER_PREFIX)
            MaterialIcons.folder(name, open = false)?.let { closed ->
                MaterialIcons.folder(name, open = true)?.let { open ->
                    return TreeIcon.Folder(closed, open, IconTint.None)
                }
            }
        }
        return fallback
    }

    /** Android brand green — android modules, `res/`, and the manifest. */
    private val androidGreen = Color(0xFF3DDC84)

    init {
        register("workspace", TreeIcon.Glyph(CaIcons.layers, IconTint.Accent))
        register("module", TreeIcon.Glyph(CaIcons.layers, IconTint.Accent))
        register("module.android", TreeIcon.Glyph(CaIcons.androidLogo, IconTint.Fixed(androidGreen)))
        register("sourceset.java", TreeIcon.Folder(CaIcons.folder, CaIcons.folderOpen, IconTint.Accent))
        register("sourceset.kotlin", TreeIcon.Folder(CaIcons.folder, CaIcons.folderOpen, IconTint.Fixed(Color(0xFFCD7EE0))))
        register("sourceset.resources", TreeIcon.Glyph(CaIcons.resources, IconTint.Info))
        register("sourceset.android-res", TreeIcon.Glyph(CaIcons.image, IconTint.Fixed(androidGreen)))
        register("sourceset.assets", TreeIcon.Glyph(CaIcons.box, IconTint.Warning))
        register("sourceset.generated", TreeIcon.Folder(CaIcons.folder, CaIcons.folderOpen, IconTint.Tertiary))
        // Derived build output (the curated "build outputs" node + the raw `build/` dir) — IntelliJ marks
        // excluded/output dirs with a warm tint; the row text is additionally muted via `styleHint`.
        register("build-output", TreeIcon.Folder(CaIcons.folder, CaIcons.folderOpen, IconTint.Warning))
        register("package", TreeIcon.Glyph(CaIcons.pkg, IconTint.Secondary))
        register("folder", TreeIcon.Folder(CaIcons.folder, CaIcons.folderOpen, IconTint.Secondary))
        register("manifest", TreeIcon.Glyph(CaIcons.file, IconTint.Fixed(androidGreen)))
        register("file", fallback)
        register("java", TreeIcon.Glyph(BrandIcons.java, IconTint.Original))
        // Kotlin brand K is a single-color glyph baked into the path; registering with IconTint.Fixed
        // matching the baked color makes `Icon(... tint = KOTLIN_PURPLE)` apply ColorFilter.SrcIn against
        // the same purple and preserve the brand visual. IconTint.Original routes through LocalContentColor
        // which overrides the baked color and collapses the K to the theme text color — that was the
        // "K looks like N" report (same shape, same theme color as the surrounding file rows).
        register("kotlin", TreeIcon.Glyph(BrandIcons.kotlin, IconTint.Fixed(Color(0xFF7F52FF))))
        register("xml", TreeIcon.Glyph(BrandIcons.xml, IconTint.Original))
        register("dart", TreeIcon.Glyph(BrandIcons.dart, IconTint.Fixed(Color(0xFF0175C2))))
        register("flutter", TreeIcon.Glyph(BrandIcons.flutter, IconTint.Fixed(Color(0xFF54C5F8))))
        // ProGuard/R8 keep-rule files (`proguard-rules.pro`, `consumer-rules.pro`) — the shrinker config.
        register("proguard", TreeIcon.Badge("R8", Color(0xFF56B6C2)))
        // Data / config formats — colored letter badges, JSON as the braces glyph (it fits perfectly).
        register("json", TreeIcon.Glyph(CaIcons.braces, IconTint.Fixed(Color(0xFFC9A227))))
        register("toml", TreeIcon.Badge("T", Color(0xFFB0703A)))
        register("yaml", TreeIcon.Badge("Y", Color(0xFFCB4B34)))
        register("properties", TreeIcon.Badge("=", Color(0xFF8B8D96)))
        register("editorconfig", TreeIcon.Badge("EC", Color(0xFF8B8D96)))
        // Docs / text.
        register("markdown", TreeIcon.Badge("M", Color(0xFF6C9BD1)))
        register("text", TreeIcon.Glyph(CaIcons.docText, IconTint.Tertiary))
        // Raster/vector images — the image glyph, theme-blue (a plain image, not the android-green res set).
        register("image", TreeIcon.Glyph(CaIcons.image, IconTint.Info))
        // Groovy Gradle scripts (a `.gradle.kts` shows as Kotlin) + VCS metadata.
        register("gradle", TreeIcon.Badge("G", Color(0xFF6BA84F)))
        register("git", TreeIcon.Glyph(CaIcons.gitBranch, IconTint.Fixed(Color(0xFFDE6E43))))
        registerThemeIcons()
    }

    /**
     * The Material Icon Theme (Philipp Kief) brand icons replace the letter badges/glyphs for every
     * language this IDE knows — a tree, tab, breadcrumb or template card that resolves a legacy language
     * id gets the real icon (`kotlin`, `java`, `xml`, `dart`, …). The engine's default provider already
     * returns `mat:<icon>` ids for arbitrary files; this only upgrades the ids the UI itself still emits
     * (templates, curated nodes). Unavailable icons keep their existing fallback.
     */
    private fun registerThemeIcons() {
        // Keep hand-tuned BrandIcons for these ids — do not replace with generated theme vectors.
        val keepBrand = brandByMaterialName.keys
        for ((iconId, ext) in mapOf(
            "java" to "java", "kotlin" to "kt", "xml" to "xml", "json" to "json", "toml" to "toml",
            "yaml" to "yml", "gradle" to "gradle", "markdown" to "md", "properties" to "properties",
            "dart" to "dart", "ai" to "ai", "ts" to "ts", "js" to "js", "html" to "html", "css" to "css",
            "scss" to "scss", "go" to "go", "rust" to "rs", "python" to "py", "sql" to "sql",
            "swift" to "swift", "objective-c" to "m", "php" to "php", "sh" to "sh", "bat" to "bat",
            "zip" to "zip", "apk" to "apk", "dockerfile" to "dockerfile", "c" to "c", "h" to "h",
            "cpp" to "cpp", "hpp" to "hpp", "csharp" to "cs", "fsharp" to "fs", "r" to "r",
            "julia" to "julia", "haskell" to "hs", "elixir" to "ex", "erlang" to "erl", "lua" to "lua",
            "clojure" to "clj", "scala" to "scala", "groovy" to "groovy", "ruby" to "rb",
            "perl" to "pl", "zig" to "zig", "vim" to "vim", "vue" to "vue", "svelte" to "svelte",
            "react" to "jsx", "react_ts" to "tsx", "powershell" to "ps1", "terra" to "tf",
            "hcl" to "hcl", "tex" to "tex", "bib" to "bib", "fortran" to "f", "visualstudio" to "vb",
            "settings" to "ini", "console" to "cmd", "database" to "sql", "jar" to "jar",
            "dll" to "dll", "exe" to "exe", "svg" to "svg", "image" to "png", "android" to "apk",
        )) {
            if (iconId in keepBrand) continue
            val name = MaterialIconThemeData.fileExtensions[ext] ?: continue
            MaterialIcons.file(name)?.let { register(iconId, TreeIcon.Vector(it)) }
        }
        MaterialIconThemeData.fileNames[".editorconfig"]?.let { n ->
            MaterialIcons.file(n)?.let { register("editorconfig", TreeIcon.Vector(it)) }
        }
        MaterialIconThemeData.fileNames[".gitignore"]?.let { n ->
            MaterialIcons.file(n)?.let { register("git", TreeIcon.Vector(it)) }
        }
        MaterialIcons.file("folder")?.let { closed ->
            MaterialIcons.file("folder-open")?.let { open ->
                register("folder", TreeIcon.Folder(closed, open, IconTint.None))
            }
        }
        MaterialIcons.file("file")?.let { register("file", TreeIcon.Vector(it)) }
    }
}

/**
 * The icon id for a file BY NAME — a pure-UI mirror of the engine's file-icon providers (the built-in
 * `DefaultFileIconProvider` + Android's `AndroidFileIconProvider`, file targets only), so a tab or
 * breadcrumb can show the SAME icon the file tree does without needing a `TreeNode` or a backend round-trip
 * (a file opens from many origins — tree, go-to-symbol, console, session restore). The Android rules
 * (manifest, ProGuard) are checked first, matching the providers' priority order. Resolve the returned id
 * through [TreeIcons.resolve].
 */
fun fileIconId(fileName: String): String = when {
    // Android brand rules first — they match AndroidFileIconProvider (priority 100) beating the themes below.
    fileName == "AndroidManifest.xml" -> "manifest"
    fileName.endsWith(".pro") -> "proguard"
    // The Material Icon Theme wins for everything it maps: exact names (Dockerfile, .gitignore...) first,
    // then extensions (.kt → kotlin, .dart → dart, .xml → xml, ...), else the legacy typed ids.
    MaterialIconThemeData.fileNames.containsKey(fileName) -> MaterialIcons.FILE_PREFIX + MaterialIconThemeData.fileNames.getValue(fileName)
    fileName.lastIndexOf('.') > 0 && MaterialIconThemeData.fileExtensions.containsKey(fileName.substringAfterLast('.')) ->
        MaterialIcons.FILE_PREFIX + MaterialIconThemeData.fileExtensions.getValue(fileName.substringAfterLast('.'))
    else -> when {
        // Legacy fallbacks, only for formats the theme doesn't cover (generic text/binary sorts, flutter).
        fileName == ".editorconfig" -> "editorconfig"
        fileName.endsWith(".dart") -> "dart"
        fileName.endsWith(".flutter") -> "flutter"
        fileName.endsWith(".gradle") -> "gradle"
        fileName.endsWith(".txt") || fileName.endsWith(".log") -> "text"
        fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
            fileName.endsWith(".gif") || fileName.endsWith(".webp") || fileName.endsWith(".svg") -> "image"
        else -> "file"
    }
}
