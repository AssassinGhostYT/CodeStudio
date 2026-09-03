package dev.ide.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.launch

/**
 * Visual Canvas — Phase 2.
 *
 * A phone-shape surface that replaces the editor when the active tab is in [dev.ide.ui.EditorViewMode.Canvas].
 * The user opens the FAB to pick a component kind (Button, TextField, Image, Container); tapping a kind
 * appends an in-memory [CanvasItem] AND creates an XML stub under `<workspace>/.platform/canvas/` so the file
 * tree refreshes. Tapping a placed item selects it (highlight + delete affordance) — delete removes both the
 * item and the file.
 *
 * Phase 2 adds drag-to-reposition: long-press any placed item, drag it to a new spot inside the phone-frame,
 * release — the item's new position is kept in memory (persistence to disk lands in Phase 3 alongside snap
 * guides + resize). Layout stays in-memory for now; the XML stubs under `.platform/canvas/` are the Phase 1
 * artifact and are unchanged by Phase 2.
 *
 * Phase 3+ plans: persistence, snap guides, resize handles, action-chain editor, AI integration, multi-touch,
 * undo/redo for drags.
 *
 * The choice to write under `.platform/canvas/` rather than `app/src/main/res/layout/` is also Phase 1: it
 * keeps the tree tidy and lets the user wipe the sandbox by deleting one folder. Phase 5 routes output to the
 * correct resource directory per platform.
 */

private const val CANVAS_DIR = ".platform/canvas"

private data class CanvasItem(
    val id: String,
    val kind: CanvasComponentKind,
    /** Top-left of the item inside the canvas frame, in dp. Clamped to the frame on every drag end. */
    val position: Offset = Offset.Zero,
    /** Workspace-relative path of the XML stub the item materializes to on disk. Captured at add-time so
     *  delete is exact (the layout dir might not exist when removing — Phase 1's CANVAS_DIR is the fallback). */
    val filePath: String,
)

private enum class CanvasComponentKind(
    val displayName: String,
    val xmlStub: String,
) {
    Button(
        "Button",
        "<Button\n    android:id=\"@+id/canvas_button\"\n    android:layout_width=\"wrap_content\"\n" +
            "    android:layout_height=\"wrap_content\"\n    android:text=\"@string/canvas_button\" />",
    ),
    TextField(
        "TextField",
        "<EditText\n    android:id=\"@+id/canvas_text\"\n    android:layout_width=\"match_parent\"\n" +
            "    android:layout_height=\"wrap_content\"\n    android:hint=\"@string/canvas_text_hint\" />",
    ),
    Image(
        "Image",
        "<ImageView\n    android:id=\"@+id/canvas_image\"\n    android:layout_width=\"100dp\"\n" +
            "    android:layout_height=\"100dp\"\n    android:src=\"@android:drawable/ic_menu_gallery\" />",
    ),
    Container(
        "Container",
        "<LinearLayout\n    android:id=\"@+id/canvas_container\"\n    android:layout_width=\"match_parent\"\n" +
            "    android:layout_height=\"wrap_content\"\n    android:orientation=\"vertical\" />",
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualCanvas(
    backend: IdeBackend,
    modifier: Modifier = Modifier,
) {
    val items = remember { mutableStateListOf<CanvasItem>() }
    var paletteOpen by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    // Bounds of the inner phone-frame — used to clamp drag destinations. Pixels.
    var frameSizePx by remember { mutableStateOf(IntOffset.Zero) }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.background(Ide.colors.editorBg)) {
        // Phone-shape frame, centered.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(280.dp)
                .height(560.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF101014))
                .padding(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1A1A1F))
                    .onGloballyPositioned { coords ->
                        // boundsInWindow is in raw pixels relative to the window origin; the drag clamp
                        // converts both frame and item size to dp before subtracting.
                        val b = coords.boundsInWindow()
                        frameSizePx = IntOffset(b.width.toInt(), b.height.toInt())
                    },
            ) {
                if (items.isEmpty()) {
                    Text(
                        "Toca + para añadir componentes",
                        color = Color(0xFF888888),
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                } else {
                    // Free-positioned overlay (not a Column) — items enumerate inside this Box so each one
                    // can absoluteOffset to its own (x, y) without disturbing the others.
                    Box(modifier = Modifier.fillMaxSize()) {
                        items.forEach { item ->
                            PlacedCanvasItem(
                                item = item,
                                selected = selectedId == item.id,
                                frameSizePx = frameSizePx,
                                onClick = { selectedId = item.id },
                                onPositionChange = { newPos ->
                                    val idx = items.indexOfFirst { it.id == item.id }
                                    if (idx >= 0) items[idx] = item.copy(position = newPos)
                                },
                                onDelete = {
                                    items.remove(item)
                                    if (selectedId == item.id) selectedId = null
                                    scope.launch { runCatching { backend.files.deletePath(item.filePath) } }
                                },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { paletteOpen = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(CaIcons.plus, contentDescription = "Add component")
        }

        if (paletteOpen) {
            ComponentPalette(
                onPick = { kind ->
                    // Stagger initial positions in a small grid so two components don't land on top of
                    // each other when they're added back-to-back. Purely cosmetic — the user can drag.
                    val slot = items.size
                    val col = slot % 3
                    val row = slot / 3
                    val idSuffix = System.currentTimeMillis()
                    val fileName = "canvas_${kind.name.lowercase()}_${idSuffix}.xml"
                    // Re-resolve the target dir on every add — cheap, and lets a project that just got
                    // an Android module start writing layouts there without remounting the canvas.
                    val dir = resolveCanvasDir(backend)
                    val item = CanvasItem(
                        id = "canvas_${kind.name.lowercase()}_${idSuffix}",
                        kind = kind,
                        position = Offset(x = (12f + col * 84f), y = (12f + row * 44f)),
                        filePath = "$dir/$fileName",
                    )
                    items.add(item)
                    selectedId = item.id
                    paletteOpen = false
                    scope.launch {
                        runCatching {
                            backend.files.createFile(
                                dirPath = dir,
                                fileName = fileName,
                                content = kind.xmlStub,
                            )
                        }
                    }
                },
                onDismissRequest = { paletteOpen = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComponentPalette(
    onPick: (CanvasComponentKind) -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Componentes", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            CanvasComponentKind.entries.forEach { kind ->
                ListItem(
                    headlineContent = { Text(kind.displayName) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(kind) },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PlacedCanvasItem(
    item: CanvasItem,
    selected: Boolean,
    frameSizePx: IntOffset,
    onClick: () -> Unit,
    onPositionChange: (Offset) -> Unit,
    onDelete: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    var dragging by remember { mutableStateOf(false) }
    // Self-measured size so the drag clamp can keep the whole item on screen, not just the touch point.
    var itemSizePx by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    val frameWidthDp = with(density) { frameSizePx.x.toDp() }
    val frameHeightDp = with(density) { frameSizePx.y.toDp() }
    val itemWidthDp = with(density) { itemSizePx.x.toDp() }
    val itemHeightDp = with(density) { itemSizePx.y.toDp() }

    Surface(
        modifier = Modifier
            .onGloballyPositioned { itemSizePx = IntOffset(it.size.width, it.size.height) }
            .offset { IntOffset(item.position.x.toInt(), item.position.y.toInt()) }
            // Scale-up feedback while dragging — confirms to the user that the long-press was caught and
            // the item is now under their finger (not a static card they have to chase).
            .scale(if (dragging) 1.05f else 1f)
            // pointerInput FIRST so its long-press detector runs before clickable claims the down event.
            // Tap = clickable (selection); long-press + drag = pointerInput (move).
            .pointerInput(item.id, frameSizePx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // dragAmount is in pixels (offset from the previous frame); convert to dp via the
                        // captured density so position stays in the same unit space as item.position.
                        val dxDp = with(density) { dragAmount.x.toDp().value }
                        val dyDp = with(density) { dragAmount.y.toDp().value }
                        val next = item.position + Offset(dxDp, dyDp)
                        // Clamp inside the phone-frame in dp: 0 ≤ x ≤ frameW − itemW (same for y).
                        val maxX = (frameWidthDp.value - itemWidthDp.value).coerceAtLeast(0f)
                        val maxY = (frameHeightDp.value - itemHeightDp.value).coerceAtLeast(0f)
                        onPositionChange(
                            Offset(
                                x = next.x.coerceIn(0f, maxX),
                                y = next.y.coerceIn(0f, maxY),
                            )
                        )
                    },
                )
            }
            // Width is intrinsic — items size to their content. The drag gesture lives on the Surface so
            // long-pressing ANYWHERE on the card lifts it; a regular tap falls through to clickable.
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(2.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.kind.displayName)
            if (selected) {
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onDelete) {
                    Icon(CaIcons.close, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * Walk the project tree once and locate the Android `res/layout/` folder. Returns its `resDirPath` (the
 * workspace-relative dir a new XML resource would be created in) — the same value the new-file dialog uses
 * (see [dev.ide.ui.components.NewFileDialog]). Falls back to [CANVAS_DIR] when the project isn't an Android
 * one (Compose-only, plain JVM, etc.) so the canvas still works as a sandbox.
 */
private fun resolveCanvasDir(backend: IdeBackend): String {
    val root = runCatching { backend.files.fileTree() }.getOrNull() ?: return CANVAS_DIR
    return findLayoutDir(root)?.resDirPath ?: CANVAS_DIR
}

private fun findLayoutDir(node: TreeNode): TreeNode? {
    if (node.kind == NodeKind.Folder && node.name == "layout" && node.resDirPath != null) return node
    node.children.forEach { child ->
        val found = findLayoutDir(child)
        if (found != null) return found
    }
    return null
}

