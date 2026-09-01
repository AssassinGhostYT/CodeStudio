package dev.ide.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVectorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.runtime.remember
import dev.ide.ui.icons.IconTint

/**
 * Resolve a tree-node [IconTint] against the live theme (or its fixed brand color). Lives in :ide-ui because
 * it reads the theme tokens ([Ca]); the [IconTint] contract itself is neutral and lives in :ide-ui-api, so
 * icons are registered outside composition and the theme-backed tints are resolved here at render time.
 */
@Composable
fun resolveTint(tint: IconTint): Color = when (tint) {
    IconTint.Accent -> Ca.colors.accent
    IconTint.Primary -> Ca.colors.textPrimary
    IconTint.Secondary -> Ca.colors.textSecondary
    IconTint.Tertiary -> Ca.colors.textTertiary
    IconTint.Success -> Ca.colors.success
    IconTint.Warning -> Ca.colors.warning
    IconTint.Error -> Ca.colors.error
    IconTint.Info -> Ca.colors.info
    IconTint.Original -> Color.Unspecified
    is IconTint.Fixed -> tint.color
}

/**
 * Render a tree/file-type icon honoring its [IconTint].
 *
 * For [IconTint.Original] this bypasses the material3 [Icon] entirely and renders via `Image(
 * painter = ImageVectorPainter(...))` with NO `colorFilter` — that path is the only one that leaves the
 * sub-paths' baked-in brand colors intact. material3's `Icon(imageVector, ..., tint = ...)` (or even
 * `Icon(imageVector, ...)` with the default `LocalContentColor.current`) ALWAYS multiplies the vector
 * by a `ColorFilter.tint(..., BlendMode.SrcIn)`, replacing every baked-in fill with the resolved tint
 * color. That is why the Kotlin K (purple), XML chevron (orange), etc. were being rendered in the
 * theme's text color instead of their brand color.
 *
 * For every other tint the resolved color is passed and the icon adopts the surrounding palette.
 *
 * Pass-through `contentDescription` keeps the existing a11y labels at every call site.
 */
@Composable
fun BrandIcon(
    image: ImageVector,
    tint: IconTint,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    when (tint) {
        IconTint.Original -> {
            val painter: Painter = remember(image) { ImageVectorPainter(image) }
            Image(
                painter = painter,
                contentDescription = contentDescription,
                modifier = modifier,
            )
        }
        else -> Icon(image, contentDescription, modifier, tint = resolveTint(tint))
    }
}
