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
 * Render a tree/file-type icon honoring its [IconTint]. For every tint (including [IconTint.Original]
 * after resolving via the live theme) the composable just delegates to material3 [Icon] with the resolved
 * color. Single-color brand icons (Kotlin K, Dart, Flutter) should be registered with
 * [IconTint.Fixed] holding their brand color — that way the `ColorFilter.tint(t, SrcIn)` step in
 * [Icon] multiplies the baked-in color by the same brand color, preserving the brand visual.
 *
 * The [BrandIcon] composable wraps the icon-tint resolution in one place so call sites don't have to
 * repeat the `Icon(..., tint = resolveTint(ic.tint))` boilerplate.
 */
@Composable
fun BrandIcon(
    image: ImageVector,
    tint: IconTint,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Icon(image, contentDescription, modifier, tint = resolveTint(tint))
}
