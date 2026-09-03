package dev.ide.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import dev.ide.ui.editor.preview.AndroidPathParser
import kotlin.math.min

/** A single parsed `<path>` inside a Material vector icon. */
internal data class UvPath(val data: String, val fill: Color?)

/** The result of parsing a bundled `<vector>` icon's XML. */
internal data class UvIcon(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val paths: List<UvPath>,
)

/**
 * Parses raw Android `<vector android:pathData="…">` XML (Material icons from `icons.zip`) into a
 * render-ready [UvIcon]. Mirrors CodeAssist's `VectorRenderer` (viewport + pathData + fill/fillColor), but
 * produces Compose-native geometry so the Canvas Icon Manager renders without Android VectorDrawable.
 *
 * Uses a small hand-rolled tag scanner rather than a DOM/pull parser so it stays platform-neutral
 * (common code has no `android.util.Xml`). It understands the flattened Material-icon subset:
 * `<vector viewportWidth= viewportHeight= …>` with one-or-more `<path android:pathData="…" android:fillColor=…/>`.
 */
internal fun parseVectorXml(xml: String): UvIcon? {
    var viewportWidth = 24f
    var viewportHeight = 24f
    val paths = mutableListOf<UvPath>()

    // The <vector …> opening tag supplies the viewport.
    val vectorTag = xml.substringAfter("<vector").substringBefore(">")
    attrOf(vectorTag, "viewportWidth")?.toFloatOrNull()?.let { viewportWidth = it }
    attrOf(vectorTag, "viewportHeight")?.toFloatOrNull()?.let { viewportHeight = it }

    // Grab each self-contained <path … /> block (Material icons are self-closing).
    var i = 0
    while (true) {
        val open = xml.indexOf("<path", i)
        if (open < 0) break
        val close = xml.indexOf("/>", open)
        if (close < 0) break
        val block = xml.substring(open, close + 2)
        val data = attrOf(block, "pathData")
        val fill = attrOf(block, "fillColor")
        if (!data.isNullOrBlank()) {
            paths.add(UvPath(data, fill?.let { parseArgb(it) }))
        }
        i = close + 2
    }

    if (paths.isEmpty()) return null
    return UvIcon(viewportWidth, viewportHeight, paths)
}

/** Extract the value of `name="…"` from a tag string, tolerant of `android:` prefixes. */
private fun attrOf(tag: String, name: String): String? {
    val regex = Regex("(?:[a-zA-Z0-9_:.]*\\.)?$name\\s*=\\s*\"([^\"]*)\"")
    return regex.find(tag)?.groupValues?.get(1)
}

private fun parseArgb(v: String): Color {
    var hex = v.removePrefix("#")
    if (hex.length == 3) {
        // 3-digit shorthand #rgb → #aarrggbb
        hex = hex.map { "$it$it" }.joinToString("")
    }
    val argb = when (hex.length) {
        8 -> hex.toLongOrNull(16)
        6 -> hex.toLongOrNull(16)?.let { 0xFF000000L or it }
        else -> null
    } ?: 0xFF000000L
    return Color(argb.toInt())
}

/**
 * Draws a parsed [icon] centred into [size], tinted [tint]. Reuses the project's [AndroidPathParser] so the
 * Material icon grid matches what the resource-preview pane draws.
 */
internal fun DrawScope.drawUvIcon(icon: UvIcon, tint: Color, size: Size) {
    if (icon.viewportWidth <= 0f || icon.viewportHeight <= 0f) return
    val scaleF = min(size.width / icon.viewportWidth, size.height / icon.viewportHeight)
    val drawnW = icon.viewportWidth * scaleF
    val drawnH = icon.viewportHeight * scaleF
    val ox = (size.width - drawnW) / 2f
    val oy = (size.height - drawnH) / 2f
    translate(ox, oy, 0f, 0f) {
        scale(scaleF, scaleF, pivot = Offset.Zero) {
            for (p in icon.paths) {
                val path = AndroidPathParser.parse(p.data)
                drawPath(path, p.fill ?: tint, style = Fill)
            }
        }
    }
}
