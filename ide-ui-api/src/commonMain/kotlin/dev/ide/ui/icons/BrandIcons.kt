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

    /** The Kotlin K letterform — the angular "K" the rhombus logo is built around, drawn in brand purple. */
    private val kotlinK = f(
        "M3.4 3.6 L6.0 3.6 L6.0 20.6 L3.4 20.6 Z" +
            "M6.3 4.4 L21.0 1.6 L21.0 6.4 L6.3 8.8 Z" +
            "M6.3 10.6 L21.0 17.4 L21.0 12.8 L6.3 9.2 Z" +
            "M6.3 20.6 L9.0 20.6 L9.0 23.0 L3.4 23.0 L3.4 20.6 Z",
    )

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

    /** Kotlin — the angular K letterform, so the purple paints the K (never the rhombus backdrop). */
    val kotlin = build("brand-kotlin", kotlinK)

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

    /** Dart logo — the D outline with the diagonal slash, from the user's `Dart-logo` SVG. */
    private val dartP0 = f("M0 12 L0 24 L5.351 23.995 C8.292 23.993 10.662 23.988 10.615 23.984 C10.535 23.974 10.345 23.791 8.395 21.848 C6.073 19.533 6.152 19.62 5.812 19.008 C5.524 18.49 5.231 17.702 5.128 17.168 L5.077 16.91 L5.07 10.945 L5.06 4.98 L8.67 2.59 C10.655 1.275 12.333 0.164 12.401 0.124 C12.469 0.082 12.567 0.037 12.619 0.026 C12.677 0.014 10.287 0.005 6.359 0.002 L0 0 L0 12 Z", evenOdd = true)
    private val dartP1 = f("M13.163 0.035 C13.24 0.052 13.385 0.098 13.481 0.138 C13.856 0.295 13.943 0.37 15.352 1.765 C16.08 2.487 16.751 3.152 16.842 3.244 L17.006 3.41 L17.309 4.27 L17.609 5.13 L17.74 5.168 C18.469 5.37 19.378 5.841 19.898 6.286 C19.995 6.37 20.937 7.308 21.991 8.372 L23.906 10.308 L23.902 14.873 L23.895 19.439 L22.256 19.962 C21.356 20.25 20.611 20.482 20.599 20.48 C20.59 20.475 20.885 19.788 21.255 18.949 L21.933 17.426 L21.853 17.203 C21.438 16.015 17.599 5.147 17.592 5.14 C17.562 5.107 17.091 5.034 16.793 5.016 C16.584 5.002 14.128 4.992 10.758 4.992 L5.074 4.992 L12.827 12.745 L20.58 20.498 L20.02 22.249 L19.46 24 L21.729 24 L24 24 L24 12 L24 0 L18.511 0.002 C13.891 0.002 13.043 0.009 13.163 0.035 Z", evenOdd = true)
    val dart = build("brand-dart", dartP0, dartP1)

    /** Flutter logo mark, from the user's `free-flutter-logo` SVG. */
    private val flutterP0 = f("M0 12 L0 24 L12 24 L24 24 L24 12 L24 0 L22.856 0 L21.703 0 L13.856 7.847 L6 15.703 L4.153 13.847 L2.297 12 L8.297 6 L14.297 0 L7.144 0 L0 0 L0 12 Z M21.562 11.137 C21.562 11.175 19.312 13.453 16.566 16.2 L11.578 21.188 L9.75 19.359 L7.922 17.531 L11.156 14.297 L14.391 11.062 L17.972 11.062 C19.95 11.062 21.562 11.091 21.562 11.137 Z", evenOdd = true)
    val flutter = build("brand-flutter", flutterP0)

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
