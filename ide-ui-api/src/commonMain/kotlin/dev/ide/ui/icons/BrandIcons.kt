package dev.ide.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Real file-type brand glyphs, converted from the user-supplied SVGs, drawn in
 * opaque black so callers recolor with `Icon(tint = ...)`. Each is normalised to a
 * 24x24 grid via [ImageVector.Builder]. The Java mark uses two fills (blue + orange)
 * matching the official logo; Kotlin fills with EvenOdd so the K reads as cutouts.
 */
object BrandIcons {
    private class Sub(val d: String, val fillType: PathFillType)
    private fun f(d: String, evenOdd: Boolean = false) = Sub(d, if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero)

    private val kotlinP0 = f("M11.82 11.82 C18.33 18.33 23.69 23.63 23.73 23.62 C23.77 23.61 23.85 23.52 23.91 23.43 C24.00 23.28 23.39 22.66 12.37 11.64 L0.73 0.00 L0.37 0.00 L0.00 0.00 L11.82 11.82 Z", evenOdd = true)
    private val kotlinP1 = f("M11.75 11.75 C21.03 21.03 23.51 23.47 23.58 23.40 C23.64 23.36 23.75 23.22 23.84 23.09 L24.00 22.88 L12.56 11.44 L1.12 0.00 L0.56 0.00 L0.00 0.00 L11.75 11.75 Z", evenOdd = true)
    private val kotlinP2 = f("M6.15 6.15 L12.30 12.30 L12.56 12.01 C12.71 11.85 12.91 11.64 13.01 11.54 C13.28 11.26 14.53 9.94 14.89 9.55 C15.07 9.36 15.36 9.05 15.54 8.87 C16.10 8.28 16.17 8.21 16.80 7.55 C17.41 6.90 17.87 6.41 18.54 5.71 C18.74 5.50 19.09 5.12 19.33 4.88 C19.90 4.27 20.33 3.82 20.79 3.34 C21.01 3.12 21.24 2.87 21.31 2.79 C21.87 2.19 22.81 1.21 23.15 0.88 C23.37 0.66 23.55 0.47 23.55 0.45 C23.55 0.43 23.66 0.32 23.78 0.21 L24.00 0.00 L12.42 0.00 L0.85 0.00 L6.78 5.94 L12.71 11.88 L6.76 5.94 L0.81 0.00 L0.40 0.00 L0.00 0.00 L6.15 6.15 Z", evenOdd = true)
    private val kotlinP3 = f("M10.35 12.00 C3.75 18.60 0.00 22.36 2.01 20.35 L5.67 16.69 L9.33 20.35 L12.98 24.00 L13.15 24.00 L13.32 24.00 L9.58 20.26 L5.84 16.52 L5.89 16.47 L5.95 16.42 L9.74 20.21 L13.53 24.00 L13.78 24.00 L14.02 24.00 L10.11 20.08 L6.19 16.17 L6.22 16.14 L6.26 16.11 L10.20 20.06 L14.14 24.00 L14.44 24.00 L14.73 24.00 L10.64 19.91 C8.39 17.66 6.55 15.82 6.55 15.81 C6.55 15.80 8.40 17.64 10.66 19.90 L14.76 24.00 L18.56 24.00 L22.36 24.00 L22.36 12.00 C22.36 5.40 22.36 0.00 22.35 0.00 C22.35 0.00 16.95 5.40 10.35 12.00 Z", evenOdd = true)

    private val javaBlue0 = f("M9.27 5.25 C9.27 5.25 7.44 6.32 10.58 6.68 C14.38 7.11 16.32 7.05 20.51 6.26 C20.51 6.26 21.61 6.95 23.15 7.55 C13.76 11.57 1.90 7.31 9.27 5.25 M8.13 0.00 C8.13 0.00 6.07 1.52 9.21 1.85 C13.27 2.27 16.48 2.30 22.03 1.23 C22.03 1.23 22.79 2.01 24.00 2.44 C12.65 5.76 0.00 2.70 8.13 0.00", evenOdd = true)
    private val javaBlue1 = f("M19.99 9.05 C19.99 9.05 20.72 9.66 19.18 10.13 C16.25 11.01 6.98 11.28 4.40 10.16 C3.48 9.76 5.21 9.20 5.76 9.08 C6.33 8.96 6.65 8.98 6.65 8.98 C5.63 8.26 0.00 10.41 3.80 11.02 C14.15 12.70 22.68 10.27 19.99 9.05 M9.14 1.17 C9.14 1.17 4.42 2.29 7.47 2.70 C8.75 2.87 11.32 2.83 13.70 2.63 C15.66 2.46 17.62 2.11 17.62 2.11 C17.62 2.11 16.93 2.41 16.43 2.75 C11.64 4.01 2.39 3.42 5.05 2.13 C7.31 1.04 9.14 1.17 9.14 1.17 M17.60 5.90 C22.46 3.37 20.21 0.94 18.64 1.26 C18.26 1.34 18.09 1.41 18.09 1.41 C18.09 1.41 18.23 1.19 18.50 1.09 C21.61 0.00 24.00 4.32 17.50 6.02 C17.50 6.02 17.57 5.96 17.60 5.90", evenOdd = true)
    private val javaBlue2 = f("M5.61 3.64 C12.76 4.09 23.74 3.38 24.00 0.00 C24.00 0.00 23.50 1.28 18.09 2.30 C11.99 3.45 4.46 3.32 0.00 2.58 C0.00 2.58 0.91 3.34 5.61 3.64", evenOdd = true)

    private val javaOrange0 = f("M10.34 17.33 C13.39 20.84 9.54 24.00 9.54 24.00 C9.54 24.00 17.29 20.00 13.73 14.99 C10.41 10.32 7.86 8.00 21.66 0.00 C21.66 0.00 0.00 5.41 10.34 17.33", evenOdd = true)
    private val javaOrange1 = f("M11.50 0.00 C11.50 0.00 16.05 4.55 7.19 11.54 C0.09 17.15 5.57 20.35 7.19 24.00 C3.04 20.26 0.00 16.97 2.04 13.90 C5.04 9.40 13.34 7.22 11.50 0.00", evenOdd = true)

    private val xmlP0 = f("M0.00 12.00 L0.00 24.00 L12.00 24.00 L24.00 24.00 L24.00 12.00 L24.00 0.00 L12.00 0.00 L0.00 0.00 L0.00 12.00 Z M16.16 2.58 C16.17 3.74 16.19 4.15 16.27 4.26 C16.37 4.38 16.55 4.40 17.99 4.40 L19.60 4.40 L19.60 8.20 L19.60 12.00 L12.00 12.00 L4.40 12.00 L4.40 6.57 C4.40 2.30 4.42 1.13 4.50 1.10 C4.55 1.07 7.20 1.05 10.38 1.05 L16.16 1.04 L16.16 2.58 Z M18.02 2.64 L18.95 3.60 L17.99 3.60 L17.04 3.60 L17.04 2.64 C17.04 2.11 17.05 1.68 17.06 1.68 C17.08 1.68 17.50 2.11 18.02 2.64 Z M21.68 16.24 L21.68 19.60 L11.96 19.60 L2.24 19.60 L2.24 16.24 L2.24 12.88 L11.96 12.88 L21.68 12.88 L21.68 16.24 Z M19.60 21.72 C19.60 22.94 19.60 22.96 19.42 23.00 C19.32 23.02 15.91 23.03 11.84 23.02 L4.44 23.00 L4.42 21.74 L4.39 20.48 L12.00 20.48 L19.60 20.48 L19.60 21.72 Z", evenOdd = true)

    private fun build(name: String, vararg subs: Sub): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (sub in subs) {
            val nodes = PathParser().parsePathString(sub.d).toNodes()
            b.addPath(nodes, pathFillType = sub.fillType, fill = SolidColor(Color.Black))
        }
        return b.build()
    }

    /** Kotlin logo — the rhombus with the stylised K cut out. */
    val kotlin = build("brand-kotlin", kotlinP0, kotlinP1, kotlinP2, kotlinP3)

    /** Java logo — coffee cup mark, blue + orange. */
    val java = build("brand-java", javaBlue0, javaBlue1, javaBlue2, javaOrange0, javaOrange1)

    /** XML tag mark. */
    val xml = build("brand-xml", xmlP0)

    /** Assistant / AI glyph — a minimal robot head with antenna, mono so it tints over the badge. */
    val assistant = build(
        "assistant",
        f("M12 2c1.1 0 2 .9 2 2v.4h.6c1.9 0 3.4 1.5 3.4 3.4v6.2c0 1.9-1.5 3.4-3.4 3.4H9.4c-1.9 0-3.4-1.5-3.4-3.4V7.8c0-1.9 1.5-3.4 3.4-3.4h.6V4c0-1.1.9-2 2-2z"),
        f("M4.5 10.5c-1.1 0-2 .9-2 2v2c0 1.1.9 2 2 2s2-.9 2-2v-2c0-1.1-.9-2-2-2z"),
        f("M19.5 10.5c-1.1 0-2 .9-2 2v2c0 1.1.9 2 2 2s2-.9 2-2v-2c0-1.1-.9-2-2-2z"),
        f("M12 4.5a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8z"),
        f("M8 12.5a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8z"),
        f("M16 12.5a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8z"),
    )
}
