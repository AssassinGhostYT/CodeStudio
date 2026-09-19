package dev.ide.ui.probe

import androidx.compose.runtime.Composable
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.brand_logo
import dev.ide.ui.generated.resources.preview_snake
import dev.ide.ui.generated.resources.quick_recent
import dev.ide.ui.generated.resources.storage_files
import dev.ide.ui.generated.resources.tile_new
import dev.ide.ui.generated.resources.tile_new_art
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun ProbeResourceRefs() {
    painterResource(Res.drawable.tile_new)
    painterResource(Res.drawable.tile_new_art)
    painterResource(Res.drawable.storage_files)
    painterResource(Res.drawable.brand_logo)
    painterResource(Res.drawable.preview_snake)
    painterResource(Res.drawable.quick_recent)
}