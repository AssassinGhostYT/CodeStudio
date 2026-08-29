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
    private val JAVA_BLUE = Color(0xFF5382A1)
    private val JAVA_ORANGE = Color(0xFFE76F00)

    private class Sub(val d: String, val fillType: PathFillType, val color: Color = Color.Black)
    private fun f(d: String, evenOdd: Boolean = false, color: Color = Color.Black) =
        Sub(d, if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero, color)

    private val kotlinP0 = f("M11.82 11.82 C18.33 18.33 23.69 23.63 23.73 23.62 C23.77 23.61 23.85 23.52 23.91 23.43 C24.00 23.28 23.39 22.66 12.37 11.64 L0.73 0.00 L0.37 0.00 L0.00 0.00 L11.82 11.82 Z", evenOdd = true)
    private val kotlinP1 = f("M11.75 11.75 C21.03 21.03 23.51 23.47 23.58 23.40 C23.64 23.36 23.75 23.22 23.84 23.09 L24.00 22.88 L12.56 11.44 L1.12 0.00 L0.56 0.00 L0.00 0.00 L11.75 11.75 Z", evenOdd = true)
    private val kotlinP2 = f("M6.15 6.15 L12.30 12.30 L12.56 12.01 C12.71 11.85 12.91 11.64 13.01 11.54 C13.28 11.26 14.53 9.94 14.89 9.55 C15.07 9.36 15.36 9.05 15.54 8.87 C16.10 8.28 16.17 8.21 16.80 7.55 C17.41 6.90 17.87 6.41 18.54 5.71 C18.74 5.50 19.09 5.12 19.33 4.88 C19.90 4.27 20.33 3.82 20.79 3.34 C21.01 3.12 21.24 2.87 21.31 2.79 C21.87 2.19 22.81 1.21 23.15 0.88 C23.37 0.66 23.55 0.47 23.55 0.45 C23.55 0.43 23.66 0.32 23.78 0.21 L24.00 0.00 L12.42 0.00 L0.85 0.00 L6.78 5.94 L12.71 11.88 L6.76 5.94 L0.81 0.00 L0.40 0.00 L0.00 0.00 L6.15 6.15 Z", evenOdd = true)
    private val kotlinP3 = f("M10.35 12.00 C3.75 18.60 0.00 22.36 2.01 20.35 L5.67 16.69 L9.33 20.35 L12.98 24.00 L13.15 24.00 L13.32 24.00 L9.58 20.26 L5.84 16.52 L5.89 16.47 L5.95 16.42 L9.74 20.21 L13.53 24.00 L13.78 24.00 L14.02 24.00 L10.11 20.08 L6.19 16.17 L6.22 16.14 L6.26 16.11 L10.20 20.06 L14.14 24.00 L14.44 24.00 L14.73 24.00 L10.64 19.91 C8.39 17.66 6.55 15.82 6.55 15.81 C6.55 15.80 8.40 17.64 10.66 19.90 L14.76 24.00 L18.56 24.00 L22.36 24.00 L22.36 12.00 C22.36 5.40 22.36 0.00 22.35 0.00 C22.35 0.00 16.95 5.40 10.35 12.00 Z", evenOdd = true)

    private val javaBlue0 = f("M8.848 18.553s-0.915 0.532 0.652 0.713c1.898 0.217 2.869 0.186 4.961-0.21c0 0 0.55 0.345 1.318 0.644c-4.69 2.01-10.614-0.116-6.93-1.146", evenOdd = true, color = JAVA_BLUE)
    private val javaBlue1 = f("M8.275 15.93s-1.027 0.76 0.541 0.922c2.028 0.209 3.629 0.226 6.401-0.307c0 0 0.383 0.389 0.986 0.601c-5.67 1.658-11.986 0.131-7.928-1.216", evenOdd = true, color = JAVA_BLUE)
    private val javaOrange0 = f("M13.106 11.481c1.156 1.33-0.304 2.528-0.304 2.528s2.934-1.515 1.587-3.412c-1.259-1.769-2.224-2.648 3.001-5.678c0 0-8.201 2.048-4.284 6.562", evenOdd = true, color = JAVA_ORANGE)
    private val javaBlue2 = f("M19.308 20.493s0.677 0.558-0.746 0.99c-2.707 0.82-11.267 1.068-13.645 0.033c-0.855-0.372 0.748-0.888 1.252-0.996c0.526-0.114 0.826-0.093 0.826-0.093c-0.951-0.67-6.144 1.315-2.638 1.883c9.562 1.551 17.431-0.698 14.95-1.817", evenOdd = true, color = JAVA_BLUE)
    private val javaBlue3 = f("M9.288 13.212s-4.354 1.034-1.542 1.41c1.187 0.159 3.554 0.123 5.759-0.062c1.802-0.152 3.611-0.475 3.611-0.475s-0.635 0.272-1.095 0.586c-4.422 1.163-12.963 0.622-10.504-0.568c2.08-1.005 3.77-0.891 3.77-0.891", evenOdd = true, color = JAVA_BLUE)
    private val javaBlue4 = f("M17.099 17.578c4.495-2.336 2.417-4.58 0.966-4.278c-0.356 0.074-0.514 0.138-0.514 0.138s0.132-0.207 0.384-0.296c2.87-1.009 5.077 2.976-0.926 4.554c0 0 0.07-0.062 0.09-0.118", evenOdd = true, color = JAVA_BLUE)
    private val javaOrange1 = f("M14.389 0.026s2.489 2.49-2.361 6.319c-3.889 3.072-0.887 4.823-0.002 6.824c-2.27-2.048-3.936-3.852-2.819-5.53C10.848 5.175 15.393 3.981 14.389 0.026", evenOdd = true, color = JAVA_ORANGE)
    private val javaBlue5 = f("M9.73 23.907c4.314 0.276 10.94-0.153 11.096-2.195c0 0-0.302 0.774-3.566 1.389c-3.682 0.693-8.224 0.612-10.918 0.168c0 0 0.551 0.456 3.387 0.638", evenOdd = true, color = JAVA_BLUE)

    private val xmlP0 = f("M0.00 12.00 L0.00 24.00 L12.00 24.00 L24.00 24.00 L24.00 12.00 L24.00 0.00 L12.00 0.00 L0.00 0.00 L0.00 12.00 Z M16.16 2.58 C16.17 3.74 16.19 4.15 16.27 4.26 C16.37 4.38 16.55 4.40 17.99 4.40 L19.60 4.40 L19.60 8.20 L19.60 12.00 L12.00 12.00 L4.40 12.00 L4.40 6.57 C4.40 2.30 4.42 1.13 4.50 1.10 C4.55 1.07 7.20 1.05 10.38 1.05 L16.16 1.04 L16.16 2.58 Z M18.02 2.64 L18.95 3.60 L17.99 3.60 L17.04 3.60 L17.04 2.64 C17.04 2.11 17.05 1.68 17.06 1.68 C17.08 1.68 17.50 2.11 18.02 2.64 Z M21.68 16.24 L21.68 19.60 L11.96 19.60 L2.24 19.60 L2.24 16.24 L2.24 12.88 L11.96 12.88 L21.68 12.88 L21.68 16.24 Z M19.60 21.72 C19.60 22.94 19.60 22.96 19.42 23.00 C19.32 23.02 15.91 23.03 11.84 23.02 L4.44 23.00 L4.42 21.74 L4.39 20.48 L12.00 20.48 L19.60 20.48 L19.60 21.72 Z", evenOdd = true)

    private fun build(name: String, vararg subs: Sub): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (sub in subs) {
            val nodes = PathParser().parsePathString(sub.d).toNodes()
            b.addPath(nodes, pathFillType = sub.fillType, fill = SolidColor(sub.color))
        }
        return b.build()
    }

    /** Kotlin logo — the rhombus with the stylised K cut out. */
    val kotlin = build("brand-kotlin", kotlinP0, kotlinP1, kotlinP2, kotlinP3)

    /** Java logo — coffee cup mark with the steam rising above, blue + orange. */
    val java = build(
        "brand-java",
        javaOrange1,
        javaOrange0,
        javaBlue3,
        javaBlue0,
        javaBlue1,
        javaBlue4,
        javaBlue5,
        javaBlue2,
    )

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
