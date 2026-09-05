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
        val paths = MaterialIconThemeData.vectorPaths[name] ?: return null
        val r = MaterialIconThemeData.viewBoxes[name]?.split("x")?.filter { it.isNotBlank() }?.map { it.toFloatOrNull() }
        val vw = r?.getOrNull(0) ?: 32f
        val vh = r?.getOrNull(1) ?: 32f
        val b = ImageVector.Builder(name, vw.dp, vh.dp, vw, vh)
        for (p in paths) {
            val nodes = PathParser().parsePathString(p.path).toNodes()
            b.addPath(nodes, fill = SolidColor(Color(p.color)))
        }
        return b.build()
    }
}