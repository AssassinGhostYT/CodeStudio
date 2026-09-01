package dev.ide.ui.theme

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
 * Render a tree/file-type icon honoring its [IconTint]. For [IconTint.Original] the tint parameter is
 * omitted entirely so material3's [Icon] does NOT apply `BlendMode.SrcIn` over the baked-in sub-path
 * colors (Kotlin's purple K, XML's cream + orange chevron, Java's blue/orange). For every other tint
 * the resolved theme color is passed and the icons adopt the surrounding palette.
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
        IconTint.Original -> Icon(image, contentDescription, modifier)
        else -> Icon(image, contentDescription, modifier, tint = resolveTint(tint))
    }
}
