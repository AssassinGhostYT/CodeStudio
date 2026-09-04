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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Visual Canvas — Phase 2 adapted for the Icon Manager.
 *
 * A phone-shape surface that replaces the editor when the active tab is in [dev.ide.ui.EditorViewMode.Canvas].
 * The lone FAB opens the **Material Icon Manager** (a searchable grid of bundled vector icons from `icons.zip`);
 * tapping one lets the user rename it and copies the `<vector>` XML into the project's `res/drawable/`, so it
 * becomes a project drawable resource immediately.
 *
 * Placed items are drawn inside the phone-frame: a long-press drag repositions them (clamping to the frame),
 * a single tap selects one (highlight border + a small X that appears **at the bottom** of the card, never
 * beside it and never enlarging the card). Position + label are persisted to a JSON manifest under the canvas
 * directory so items survive switching to code/preview and back — the code each item materialised is already
 * on disk (the stub XML), so "going to code" shows real content.
 *
 * Drag does NOT scale the card up (the old scale-on-drag made it look broken); selection is conveyed only by
 * the border and the bottom X row.
 */

private const val CANVAS_DIR = ".platform/canvas"
private const val MANIFEST = "canvas.json"

private data class CanvasItem(
    val id: String,
    val kind: CanvasComponentKind,
    /** Top-left of the item inside the canvas frame, in dp. Clamped to the frame on every drag end. */
    val position: Offset = Offset.Zero,
    /** Workspace-relative path of the XML stub the item materializes to on disk. Captured at add-time so
     *  delete is exact (the layout dir might not exist when removing — Phase 1's CANVAS_DIR is the fallback). */
    val filePath: String,
    /** User-editable display name; defaults to the component kind. */
    val label: String = "",
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

@Composable
fun VisualCanvas(
    backend: IdeBackend,
    modifier: Modifier = Modifier,
) {
    val items = remember { mutableStateListOf<CanvasItem>() }
    var iconManagerOpen by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    // Bounds of the inner phone-frame — used to clamp drag destinations. Pixels.
    var frameSizePx by remember { mutableStateOf(IntOffset.Zero) }
    val scope = rememberCoroutineScope()

    // Persistence: load the manifest when the canvas mounts so items placed earlier (before switching to
    // code/preview and back) come back. Writing happens on every change via [persistCanvas].
    LaunchedEffect(backend) {
        loadPersisted(items, backend)
    }

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
                        val b = coords.boundsInWindow()
                        frameSizePx = IntOffset(b.width.toInt(), b.height.toInt())
                    },
            ) {
                if (items.isEmpty()) {
                    Text(
                        "Toca + para añadir componentes\nEl icono abre el gestor de iconos",
                        color = Color(0xFF888888),
                        style = Ide.type.codeSmall,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                } else {
                    // Free-positioned overlay — items absoluteOffset to their own (x, y).
                    Box(modifier = Modifier.fillMaxSize()) {
                        items.forEach { item ->
                            PlacedCanvasItem(
                                item = item,
                                selected = selectedId == item.id,
                                frameSizePx = frameSizePx,
                                onClick = { selectedId = item.id },
                                onPositionChange = { newPos ->
                                    val idx = items.indexOfFirst { it.id == item.id }
                                    if (idx >= 0) {
                                        items[idx] = item.copy(position = newPos)
                                        persist(items, backend)
                                    }
                                },
                                onDelete = {
                                    items.remove(item)
                                    if (selectedId == item.id) selectedId = null
                                    scope.launch { runCatching { backend.files.deletePath(item.filePath) } }
                                    persist(items, backend)
                                },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { iconManagerOpen = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(CaIcons.plus, contentDescription = "Iconos / añadir")
        }

        if (iconManagerOpen) {
            IconManagerSheet(
                backend = backend,
                resolveDrawableDir = { resolveResDir(backend) },
                onDismiss = { iconManagerOpen = false },
                onIconPlaced = { xml, fileName ->
                    val slot = items.size
                    val col = slot % 3
                    val row = slot / 3
                    val idSuffix = System.currentTimeMillis()
                    val drawableName = fileName.removeSuffix(".xml")
                    // Generate real Android code, not just a drawable side-effect: write a layout XML that
                    // references @drawable/<drawableName> (mirrors CodeAssist's component insertion).
                    val res = resolveResDir(backend)
                    var fp = ""
                    if (res != CANVAS_DIR) {
                        val layoutXml = buildString {
                            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                            append("<androidx.appcompat.widget.AppCompatImageView\n")
                            append("    xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
                            append("    android:layout_width=\"48dp\"\n")
                            append("    android:layout_height=\"48dp\"\n")
                            append("    android:src=\"@drawable/$drawableName\" />\n")
                        }
                        val layoutPath = "$res/layout"
                        runCatching {
                            backend.files.createFile(dirPath = layoutPath, fileName = "$drawableName.xml", content = layoutXml)
                            fp = "$layoutPath/$drawableName.xml"
                        }
                    }
                    // Add a placed item for the freshly-generated ImageView so the user sees it land on the
                    // canvas immediately, at a slot they can then drag into place with their finger.
                    val item = CanvasItem(
                        id = "canvas_icon_${idSuffix}",
                        kind = CanvasComponentKind.Image,
                        position = Offset(x = (12f + col * 84f), y = (12f + row * 44f)),
                        filePath = fp,
                        label = drawableName,
                    )
                    items.add(item)
                    selectedId = item.id
                    persist(items, backend)
                },
            )
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
    var itemSizePx by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    val frameWidthDp = with(density) { frameSizePx.x.toDp() }
    val frameHeightDp = with(density) { frameSizePx.y.toDp() }
    val itemWidthDp = with(density) { itemSizePx.x.toDp() }
    val itemHeightDp = with(density) { itemSizePx.y.toDp() }

    Column(
        modifier = Modifier
            .onGloballyPositioned { itemSizePx = IntOffset(it.size.width, it.size.height) }
            .offset { IntOffset(item.position.x.toInt(), item.position.y.toInt()) }
            .pointerInput(item.id, frameSizePx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val dxDp = with(density) { dragAmount.x.toDp().value }
                        val dyDp = with(density) { dragAmount.y.toDp().value }
                        val next = item.position + Offset(dxDp, dyDp)
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
            .clickable(onClick = onClick),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(2.dp, border),
            shadowElevation = if (dragging) 6.dp else 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.label.ifBlank { item.kind.displayName })
            }
        }

        // The X (delete) affordance lives BELOW the card — never beside it and never enlarging the card.
        // Show only an icon chip so the card stays compact.
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.close, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/**
 * Resolve the Android `res/` root (the directory that contains `drawable`/`layout`/`mipmap`). Returns the
 * workspace-relative path whose child `drawable/` a copied icon lands in, or [CANVAS_DIR] on non-Android
 * projects. Mirrors CodeAssist's `IconCopier` (writes `app/src/main/res/drawable/<name>.xml`).
 */
private fun resolveResDir(backend: IdeBackend): String {
    val root = runCatching { backend.files.fileTree() }.getOrNull() ?: return CANVAS_DIR
    return findResDir(root)?.resDirPath ?: CANVAS_DIR
}

private fun findResDir(node: TreeNode): TreeNode? {
    if (node.kind == NodeKind.Folder && node.name == "res" && node.resDirPath != null) return node
    node.children.forEach { child ->
        val found = findResDir(child)
        if (found != null) return found
    }
    return null
}

// ---------------------------------------------------------------------------
// Persistence (JSON manifest under .platform/canvas/)
// ---------------------------------------------------------------------------

private fun persist(items: List<CanvasItem>, backend: IdeBackend) {
    val sb = StringBuilder("[")
    items.forEachIndexed { i, it ->
        if (i > 0) sb.append(",\n")
        sb.append("""{"id":"${esc(it.id)}","kind":"${it.kind.name}","x":${it.position.x},"y":${it.position.y},"fp":"${esc(it.filePath)}","label":"${esc(it.label)}"}""")
    }
    sb.append("]")
    // Use saveFile (overwrite) not createFile (which silently refuses to update existing files).
    runCatching { backend.files.saveFile("$CANVAS_DIR/$MANIFEST", sb.toString()) }
}

private fun loadPersisted(items: MutableList<CanvasItem>, backend: IdeBackend) {
    runCatching {
        val text = backend.files.readFile("$CANVAS_DIR/$MANIFEST")
        if (text.isBlank()) return
        val entries = parseManifest(text)
        items.clear()
        entries.forEach { e ->
            val kind = runCatching { CanvasComponentKind.valueOf(e.kind) }.getOrElse { CanvasComponentKind.Button }
            items.add(
                CanvasItem(
                    id = e.id,
                    kind = kind,
                    position = Offset(e.x, e.y),
                    filePath = e.fp,
                    label = e.label,
                )
            )
        }
    }
}

private data class ManifestEntry(val id: String, val kind: String, val x: Float, val y: Float, val fp: String, val label: String)

private fun parseManifest(text: String): List<ManifestEntry> {
    val result = mutableListOf<ManifestEntry>()
    val entryRe = Regex("""\{[^{}]*\}""")
    for (m in entryRe.findAll(text)) {
        val s = m.value
        val id = attr(s, "id"); val kind = attr(s, "kind")
        val x = attr(s, "x").toFloatOrNull() ?: 0f
        val y = attr(s, "y").toFloatOrNull() ?: 0f
        val fp = attr(s, "fp"); val label = attr(s, "label")
        result.add(ManifestEntry(id, kind, x, y, fp, label))
    }
    return result
}

private fun attr(s: String, name: String): String {
    val r = Regex("(\"$name\"\\s*:\\s*\")([^\"]*)")
    return r.find(s)?.groupValues?.get(2) ?: ""
}

private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
