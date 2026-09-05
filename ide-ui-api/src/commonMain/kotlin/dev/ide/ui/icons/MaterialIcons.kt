package dev.ide.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Runtime bridge over the generated [MaterialIconThemeData] (PKief/material-icon-theme, MIT). The
 * theme's SVGs are flattened into per-path ARGB colors at generation time, so here we just parse the
 * path strings back into nodes and compose a multi-color [ImageVector] per icon. Vectors are built
 * lazily and cached; ids the backend returns (`mat:<icon>`, `mat-folder:<name>`) are resolved by
 * [TreeIcons] through [file] and [folder].
 */
internal object MaterialIcons {
    const val FILE_PREFIX = "mat:"
    const val FOLDER_PREFIX = "mat-folder:"

    private val cache = HashMap<String, ImageVector?>()

    fun isFile(id: String) = id.startsWith(FILE_PREFIX)

    fun isFolder(id: String) = id.startsWith(FOLDER_PREFIX)

    /** The brand icon for [iconName] (a Material Icon Theme icon name), or null if not representable. */
    fun file(iconName: String): ImageVector? = vector(iconName)

    /** The themed folder icon for [folderName] (open/closed), falling back to the generic folder. */
    fun folder(folderName: String, open: Boolean): ImageVector? {
        val map = if (open) MaterialIconThemeData.folderNamesExpanded else MaterialIconThemeData.folderNames
        val icon = map[folderName] ?: return vector(if (open) "folder-open" else "folder")
        return vector(icon)
    }

    private fun vector(name: String): ImageVector? {
        cache[name]?.let { return it }
        val built = build(name)
        cache[name] = built
        return built
    }

    private fun build(name: String): ImageVector? {
        val paths = pathOverrides[name] ?: MaterialIconThemeData.vectorPaths[name] ?: return null
        val box = viewBoxOverrides[name] ?: MaterialIconThemeData.viewBoxes[name]
        val r = box?.split("x")?.filter { it.isNotBlank() }?.map { it.toFloatOrNull() }
        val vw = r?.getOrNull(0) ?: 32f
        val vh = r?.getOrNull(1) ?: 32f
        val b = ImageVector.Builder(name, vw.dp, vh.dp, vw, vh)
        for (p in paths) {
            val nodes = PathParser().parsePathString(p.path).toNodes()
            b.addPath(nodes, fill = SolidColor(Color(p.color)))
        }
        return b.build()
    }

    /**
     * Hand-corrected path data from upstream PKief SVGs. The generated [MaterialIconThemeData.vectorPaths]
     * mangled relative commands (`h`/`v`/`a`/`m`) into broken cubics, so icons like toml/swift/xml render
     * as empty blobs or wrong shapes. Compose's [PathParser] accepts the original SVG `d` strings as-is.
     */
    private val pathOverrides: Map<String, List<MaterialIconThemeData.PathData>> = mapOf(
        "toml" to listOf(
            MaterialIconThemeData.PathData(0xFFCFD8DC, "M4 6V4h8v2H9v7H7V6z"),
            MaterialIconThemeData.PathData(0xFFEF5350, "M4 1v1H2v12h2v1H1V1zm8 0v1h2v12h-2v1h3V1z"),
        ),
        "swift" to listOf(
            MaterialIconThemeData.PathData(
                0xFFFF6E40,
                "M17.087 19.721c-2.36 1.36-5.59 1.5-8.86.1a13.8 13.8 0 0 1-6.23-5.32c.67.55 1.46 1 2.3 1.4 " +
                    "3.37 1.57 6.73 1.46 9.1 0-3.37-2.59-6.24-5.96-8.37-8.71-.45-.45-.78-1.01-1.12-1.51 " +
                    "8.28 6.05 7.92 7.59 2.41-1.01 4.89 4.94 9.43 7.74 9.43 7.74.16.09.25.16.36.22.1-.25" +
                    ".19-.51.26-.78.79-2.85-.11-6.12-2.08-8.81 4.55 2.75 7.25 7.91 6.12 12.24-.03.11-.06.22" +
                    "-.05.39 2.24 2.83 1.64 5.78 1.35 5.22-1.21-2.39-3.48-1.65-4.62-1.17",
            ),
        ),
        "xml" to listOf(
            MaterialIconThemeData.PathData(
                0xFF8BC34A,
                "M13 9h5.5L13 3.5zM6 2h8l6 6v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4c0-1.11.89-2 2-2" +
                    "m.12 13.5 3.74 3.74 1.42-1.41-2.33-2.33 2.33-2.33-1.42-1.41z" +
                    "m11.16 0-3.74-3.74-1.42 1.41 2.33 2.33-2.33 2.33 1.42 1.41z",
            ),
        ),
        "java" to listOf(
            MaterialIconThemeData.PathData(
                0xFFF44336,
                "M4 26h24v2H4zM28 4H7a1 1 0 0 0-1 1v13a4 4 0 0 0 4 4h10a4 4 0 0 0 4-4v-4h4a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2m0 8h-4V6h4Z",
            ),
        ),
    )

    private val viewBoxOverrides: Map<String, String> = mapOf(
        "toml" to "16x16",
        "swift" to "24x24",
        "xml" to "24x24",
        "java" to "32x32",
    )
}