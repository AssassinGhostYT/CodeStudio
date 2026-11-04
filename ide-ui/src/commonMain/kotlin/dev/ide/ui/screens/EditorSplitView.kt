package dev.ide.ui.screens

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.components.CaDropdownMenu
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.split_change_file
import dev.ide.ui.generated.resources.split_close
import dev.ide.ui.generated.resources.split_empty
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/**
 * The Split layout: the code editor and its live preview together, with a draggable divider to rebalance.
 * Both slots get the SAME surfaces the single-pane modes use, so editing flows straight through to the
 * (debounced) preview.
 *
 * [stacked] (a phone) puts the **preview on top and the editor on the bottom** — the editor sits against the
 * soft keyboard (natural text-input position) while the result stays visible above it. The app root already
 * insets `safeDrawing` (which includes the IME), so when the keyboard is up the usable height is *already*
 * reduced; splitting that proportionally would crush the preview to a sliver, so we cap the editor's share
 * and let the preview keep the larger part — you only need a band of code around the caret while typing, and
 * the editor scrolls to keep it visible. A wider screen places them side by side (editor left, preview right).
 */
@Composable
internal fun SplitEditorPreview(
    stacked: Boolean,
    editor: @Composable (Modifier) -> Unit,
    preview: @Composable (Modifier) -> Unit,
    modifier: Modifier,
) {
    // The editor's share of the split; the divider drags it within [0.2, 0.8].
    var fraction by remember { mutableStateOf(0.5f) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BoxWithConstraints(modifier) {
        if (stacked) {
            // While typing, keep the preview as the larger pane (cap editor ≤ half of the reduced height).
            val editorShare = if (keyboardOpen) fraction.coerceIn(0.3f, 0.5f) else fraction
            val extentPx = with(LocalDensity.current) { maxHeight.toPx() }
            Column(Modifier.fillMaxSize()) {
                preview(Modifier.fillMaxWidth().weight(1f - editorShare))
                // Editor is the bottom pane, so dragging the handle DOWN shrinks it.
                SplitHandle(stacked = true) { delta -> fraction = (fraction - delta / extentPx).coerceIn(0.2f, 0.8f) }
                editor(Modifier.fillMaxWidth().weight(editorShare))
            }
        } else {
            val extentPx = with(LocalDensity.current) { maxWidth.toPx() }
            Row(Modifier.fillMaxSize()) {
                editor(Modifier.fillMaxHeight().weight(fraction))
                SplitHandle(stacked = false) { delta -> fraction = (fraction + delta / extentPx).coerceIn(0.2f, 0.8f) }
                preview(Modifier.fillMaxHeight().weight(1f - fraction))
            }
        }
    }
}

/** The draggable divider between the Split panes — a thin bar with a centered grip handle. */
@Composable
private fun SplitHandle(stacked: Boolean, onDrag: (Float) -> Unit) {
    val drag = Modifier.draggable(
        orientation = if (stacked) androidx.compose.foundation.gestures.Orientation.Vertical else androidx.compose.foundation.gestures.Orientation.Horizontal,
        state = androidx.compose.foundation.gestures.rememberDraggableState { onDrag(it) },
    )
    val grip = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
    if (stacked) {
        Box(Modifier.fillMaxWidth().height(12.dp).then(drag).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Box(Modifier.width(34.dp).height(3.dp).clip(grip).background(MaterialTheme.colorScheme.outline))
        }
    } else {
        Box(Modifier.fillMaxHeight().width(12.dp).then(drag).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Box(Modifier.height(34.dp).width(3.dp).clip(grip).background(MaterialTheme.colorScheme.outline))
        }
    }
}

/** Shared height for the secondary pane's compact header. */
private val SplitHeaderHeight = 34.dp

/**
 * The **code + code** split: two independent editors with a draggable divider. Unlike [SplitEditorPreview]
 * (one file's editor + its preview), both slots are real [dev.ide.ui.editor.CodeEditor]s on different tabs, so
 * each keeps its own caret, scroll and undo stack. [stacked] puts the primary on top and the secondary on the
 * bottom on a phone; a wider screen places them side by side. The fraction lives in [IdeUiState] so it
 * survives recomposition and pane swaps.
 */
@Composable
internal fun SplitCodePanes(
    stacked: Boolean,
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    primary: @Composable (Modifier) -> Unit,
    secondary: @Composable (Modifier) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        if (stacked) {
            val extentPx = with(LocalDensity.current) { maxHeight.toPx() }
            Column(Modifier.fillMaxSize()) {
                primary(Modifier.fillMaxWidth().weight(fraction))
                SplitHandle(stacked = true) { delta ->
                    if (extentPx > 0f) onFractionChange(fraction + delta / extentPx)
                }
                secondary(Modifier.fillMaxWidth().weight(1f - fraction))
            }
        } else {
            val extentPx = with(LocalDensity.current) { maxWidth.toPx() }
            Row(Modifier.fillMaxSize()) {
                primary(Modifier.fillMaxHeight().weight(fraction))
                SplitHandle(stacked = false) { delta ->
                    if (extentPx > 0f) onFractionChange(fraction + delta / extentPx)
                }
                secondary(Modifier.fillMaxHeight().weight(1f - fraction))
            }
        }
    }
}

/**
 * The secondary code pane's compact header: which file it shows, a picker for another open tab, and a close
 * button for the split. The global tab strip keeps driving the primary pane, so this header is the only way
 * to change the secondary one.
 */
@Composable
internal fun SplitSecondaryHeader(
    state: IdeUiState,
    file: OpenFile,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val candidates = state.openFiles.filter { it.path != state.active?.path }
    Column(modifier.background(Ide.colors.editorBg)) {
        Row(
            Modifier.fillMaxWidth().height(SplitHeaderHeight).padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CaIcons.file, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                file.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.modified) {
                Box(
                    Modifier.padding(start = 8.dp).size(7.dp)
                        .background(Ide.colors.gitModified, RoundedCornerShape(Ca.radius.pill))
                )
            }
            Box {
                Box(
                    Modifier.padding(start = 6.dp).size(28.dp)
                        .clip(RoundedCornerShape(Ca.radius.xs))
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        CaIcons.chevronDown,
                        stringResource(Res.string.split_change_file),
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CaDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (candidates.isEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(Res.string.split_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            onClick = { menuOpen = false },
                            enabled = false,
                        )
                    } else {
                        candidates.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.name, style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(
                                        CaIcons.file,
                                        null,
                                        Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    state.openInSplit(candidate)
                                },
                            )
                        }
                    }
                }
            }
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(Ca.radius.xs))
                    .clickable { state.closeSplit() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    CaIcons.close,
                    stringResource(Res.string.split_close),
                    Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}
