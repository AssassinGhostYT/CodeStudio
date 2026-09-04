package dev.ide.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiCatalogIcon
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.launch

/**
 * Visual Canvas — Multi-screen visual editor with Action Flow navigation.
 *
 * Each screen is a phone-frame with components positioned at user-chosen dp coordinates.
 * Screen tabs appear at the top (tap to switch, long-press to rename, small × to delete).
 * The FAB opens a picker organised like CodeAssist: **Project / Libraries / Compose** tabs,
 * with the Material Symbols content visible directly (no loose "+" add buttons) and their
 * counts (e.g. 300 / 4277) shown. A second FAB opens the Action Flow (connection) editor.
 *
 * Components stay where they are placed, drag with the finger across the full frame, and open
 * a properties dialog (name / size / colors) when tapped.
 *
 * Generates real Android code:
 *  - `activity_<screen>.xml` per screen (FrameLayout with positioned components)
 *  - `MainActivity.kt` with navigation intents for each connection
 */

// ── Constants ──────────────────────────────────────────────────────────────────
private const val CANVAS_DIR = ".platform/canvas"
private const val MANIFEST = "canvas.json"
private const val MAIN_ACTIVITY = "MainActivity.kt"
private val XML_HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"

// ── Data model ─────────────────────────────────────────────────────────────────

private data class CanvasItem(
    val id: String,
    val kind: CanvasComponentKind,
    val position: Offset = Offset.Zero,
    val label: String = "",
    val widthDp: Float = 0f,
    val heightDp: Float = 0f,
    val bgColor: String = "",
    val textColor: String = "",
    val textSizeSp: Float = 0f,
)

private data class CanvasConnection(
    val fromScreenId: String,
    val fromItemId: String,
    val toScreenId: String,
)

private data class Screen(
    val id: String,
    var name: String,
    val items: MutableList<CanvasItem> = mutableStateListOf(),
)

private data class CanvasState(
    val screens: MutableList<Screen> = mutableStateListOf(Screen(id = "main", name = "Main")),
    var activeScreenId: String = "main",
    val connections: MutableList<CanvasConnection> = mutableStateListOf(),
) {
    val activeScreen: Screen? get() = screens.find { it.id == activeScreenId }

    fun addScreen(): Screen {
        val n = screens.size + 1
        val s = Screen(id = "screen_$n", name = "Screen $n")
        screens.add(s)
        return s
    }

    fun removeScreen(id: String) {
        if (screens.size <= 1) return
        val idx = screens.indexOfFirst { it.id == id }
        screens.removeAll { it.id == id }
        connections.removeAll { it.fromScreenId == id || it.toScreenId == id }
        if (activeScreenId == id) {
            activeScreenId = screens.getOrNull(if (idx > 0) idx - 1 else 0)?.id ?: screens.firstOrNull()?.id ?: "main"
        }
    }

    fun addConnection(fromScreenId: String, fromItemId: String, toScreenId: String) {
        connections.removeAll { it.fromScreenId == fromScreenId && it.fromItemId == fromItemId }
        connections.add(CanvasConnection(fromScreenId, fromItemId, toScreenId))
    }

    fun removeConnection(fromScreenId: String, fromItemId: String) {
        connections.removeAll { it.fromScreenId == fromScreenId && it.fromItemId == fromItemId }
    }

    fun findConnection(fromScreenId: String, fromItemId: String): CanvasConnection? =
        connections.find { it.fromScreenId == fromScreenId && it.fromItemId == fromItemId }
}

private enum class CanvasComponentKind(val displayName: String) {
    Button("Button"),
    TextField("TextField"),
    Image("Image"),
}

// ── Persistence ────────────────────────────────────────────────────────────────

private fun persist(state: CanvasState, backend: IdeBackend) {
    runCatching {
        val sb = StringBuilder("[")
        state.screens.forEachIndexed { si, screen ->
            if (si > 0) sb.append(",\n")
            sb.append("""{"id":"${esc(screen.id)}","name":"${esc(screen.name)}","items":[""")
            screen.items.forEachIndexed { ii, item ->
                if (ii > 0) sb.append(",")
                sb.append(
                    """{"id":"${esc(item.id)}","kind":"${item.kind.name}","x":${item.position.x},"y":${item.position.y},"label":"${esc(item.label)}","w":${item.widthDp},"h":${item.heightDp},"bg":"${esc(item.bgColor)}","tc":"${esc(item.textColor)}","ts":${item.textSizeSp}}""",
                )
            }
            sb.append("]}")
        }
        sb.append("]")
        backend.editor.saveFile("$CANVAS_DIR/$MANIFEST", sb.toString())
        val cons = StringBuilder("[")
        state.connections.forEachIndexed { i, c ->
            if (i > 0) cons.append(",")
            cons.append("""{"fs":"${esc(c.fromScreenId)}","fi":"${esc(c.fromItemId)}","ts":"${esc(c.toScreenId)}"}""")
        }
        cons.append("]")
        backend.editor.saveFile("$CANVAS_DIR/connections.json", cons.toString())
        generateMainFiles(state, backend)
    }
}

private fun loadPersisted(state: CanvasState, backend: IdeBackend) {
    runCatching {
        val text = backend.files.readFile("$CANVAS_DIR/$MANIFEST")
        if (text.isBlank()) return
        val entryRe = Regex("""\{[^{}]*\}""")
        val screenRe = Regex(""""items"\s*:\s*\[([^\]]*)]""")
        state.screens.clear()
        for (m in entryRe.findAll(text)) {
            val s = m.value
            val id = manifestStr(s, "id")
            val name = manifestStr(s, "name")
            val screen = Screen(id = id, name = name)
            val itemsMatch = screenRe.find(s)
            if (itemsMatch != null) {
                for (im in entryRe.findAll(itemsMatch.groupValues[1])) {
                    val is_ = im.value
                    val kind = runCatching { CanvasComponentKind.valueOf(manifestStr(is_, "kind")) }.getOrElse { CanvasComponentKind.Button }
                    screen.items.add(
                        CanvasItem(
                            id = manifestStr(is_, "id"),
                            kind = kind,
                            position = Offset(manifestFloat(is_, "x"), manifestFloat(is_, "y")),
                            label = manifestStr(is_, "label"),
                            widthDp = manifestFloat(is_, "w"),
                            heightDp = manifestFloat(is_, "h"),
                            bgColor = manifestStr(is_, "bg"),
                            textColor = manifestStr(is_, "tc"),
                            textSizeSp = manifestFloat(is_, "ts"),
                        ),
                    )
                }
            }
            state.screens.add(screen)
        }
        if (state.screens.isEmpty()) state.screens.add(Screen(id = "main", name = "Main"))
        state.activeScreenId = state.screens.firstOrNull()?.id ?: "main"
    }
    runCatching {
        val text = backend.files.readFile("$CANVAS_DIR/connections.json")
        if (text.isBlank()) return
        state.connections.clear()
        val entryRe = Regex("""\{[^{}]*\}""")
        for (m in entryRe.findAll(text)) {
            val s = m.value
            state.connections.add(
                CanvasConnection(
                    fromScreenId = manifestStr(s, "fs"),
                    fromItemId = manifestStr(s, "fi"),
                    toScreenId = manifestStr(s, "ts"),
                ),
            )
        }
    }
}

private fun manifestStr(s: String, name: String): String =
    Regex(""""$name"\s*:\s*"([^"]*)"""").find(s)?.groupValues?.get(1) ?: ""

private fun manifestFloat(s: String, name: String): Float =
    Regex(""""$name"\s*:\s*([0-9.eE+-]+)""").find(s)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

// ── Composable ─────────────────────────────────────────────────────────────────

@Composable
fun VisualCanvas(backend: IdeBackend, modifier: Modifier = Modifier) {
    val canvasState = remember { CanvasState() }
    var pickerOpen by remember { mutableStateOf(false) }
    var flowOpen by remember { mutableStateOf(false) }
    var iconManagerOpen by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var renameScreenId by remember { mutableStateOf<String?>(null) }
    var frameSizePx by remember { mutableStateOf(IntOffset.Zero) }
    var connectingFrom by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Crosshair guide state: set by PlacedCanvasItem during drag, cleared on drag-end.
    var dragItemId by remember { mutableStateOf<String?>(null) }
    var dragCenterDp by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    LaunchedEffect(backend) { loadPersisted(canvasState, backend) }

    fun selectedScreen() = canvasState.activeScreen
    fun updatePosition(itemId: String, delta: Offset) {
        val items = selectedScreen()?.items ?: return
        val idx = items.indexOfFirst { it.id == itemId }
        if (idx >= 0) {
            val cur = items[idx].position
            val frameW = with(density) { frameSizePx.x.toDp().value }
            val frameH = with(density) { frameSizePx.y.toDp().value }
            val newX = (cur.x + delta.x).coerceIn(0f, frameW)
            val newY = (cur.y + delta.y).coerceIn(0f, frameH)
            items[idx] = items[idx].copy(position = Offset(newX, newY))
            persist(canvasState, backend)
        }
    }

    Box(modifier = modifier.background(Ide.colors.editorBg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Screen tabs ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                canvasState.screens.forEach { screen ->
                    val isActive = screen.id == canvasState.activeScreenId
                    val isTarget = connectingFrom != null && connectingFrom!!.first != screen.id
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            isActive -> MaterialTheme.colorScheme.primary
                            isTarget -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        border = if (isTarget) BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary) else null,
                        modifier = Modifier
                            .clickable(enabled = true) {
                                if (isActive) {
                                    // Tapping the current tab again → rename it (never deletes the name).
                                    renameScreenId = screen.id
                                } else if (connectingFrom != null && connectingFrom!!.first != screen.id) {
                                    canvasState.addConnection(connectingFrom!!.first, connectingFrom!!.second, screen.id)
                                    persist(canvasState, backend)
                                    connectingFrom = null
                                } else {
                                    canvasState.activeScreenId = screen.id
                                    selectedId = null
                                    connectingFrom = null
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = screen.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = 2.dp).weight(1f, fill = false),
                            )
                            Icon(
                                CaIcons.close,
                                contentDescription = "Eliminar pantalla",
                                modifier = Modifier.size(13.dp).clickable(enabled = canvasState.screens.size > 1) {
                                    canvasState.removeScreen(screen.id); persist(canvasState, backend)
                                },
                                tint = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val created = canvasState.addScreen()
                        canvasState.activeScreenId = created.id
                        selectedId = null
                        persist(canvasState, backend)
                    },
                ) {
                    Icon(
                        CaIcons.plus,
                        contentDescription = "Nueva pantalla",
                        modifier = Modifier.padding(6.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            if (connectingFrom != null) {
                Text(
                    "Selecciona la pantalla destino…",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }

            // ── Centered phone frame ──
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
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
                            .onGloballyPositioned { val b = it.boundsInWindow(); frameSizePx = IntOffset(b.width.toInt(), b.height.toInt()) },
                    ) {
                        val items = selectedScreen()?.items
                        if (items.isNullOrEmpty()) {
                            Text(
                                "Toca + para añadir componentes\nArrastra para moverlos, toca para editarlos",
                                color = Color(0xFF888888),
                                style = Ide.type.codeSmall,
                                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                                items.forEach { item ->
                                    val con = canvasState.findConnection(canvasState.activeScreenId, item.id)
                                    PlacedCanvasItem(
                                        item = item,
                                        selected = selectedId == item.id,
                                        hasConnection = con != null,
                                        connectionTarget = con?.let { c -> canvasState.screens.find { it.id == c.toScreenId }?.name },
                                        frameSizePx = frameSizePx,
                                        onClick = {
                                            // Tap selects; tapping while already selected opens properties.
                                            if (selectedId == item.id) {
                                                editingId = item.id
                                            } else {
                                                selectedId = item.id
                                            }
                                        },
                                        onDragStart = { dragItemId = item.id },
                                        onDragMove = { delta ->
                                            updatePosition(item.id, delta)
                                            val idx = items.indexOfFirst { it.id == item.id }
                                            if (idx >= 0) {
                                                val it = items[idx]
                                                dragCenterDp = Offset(it.position.x + it.widthDp / 2f, it.position.y + it.heightDp / 2f)
                                            }
                                        },
                                        onDragEnd = { dragItemId = null },
                                        onDelete = {
                                            items.remove(item)
                                            canvasState.removeConnection(canvasState.activeScreenId, item.id)
                                            if (selectedId == item.id) selectedId = null
                                            dragItemId = null
                                            persist(canvasState, backend)
                                        },
                                        onConnect = {
                                            connectingFrom = if (connectingFrom?.second == item.id) null
                                            else canvasState.activeScreenId to item.id
                                        },
                                        onEdit = { editingId = item.id },
                                    )
                                }
                                if (dragItemId != null) {
                                    val guideColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    val guideWidth = with(density) { 1.dp.toPx() }
                                    val centerX = with(density) { dragCenterDp.x.toDp().toPx() }
                                    val centerY = with(density) { dragCenterDp.y.toDp().toPx() }
                                    Box(
                                        Modifier
                                            .matchParentSize()
                                            .drawBehind {
                                                drawLine(guideColor, Offset(centerX, 0f), Offset(centerX, size.height), strokeWidth = guideWidth)
                                            },
                                    )
                                    Box(
                                        Modifier
                                            .matchParentSize()
                                            .drawBehind {
                                                drawLine(guideColor, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = guideWidth)
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Action Flow (connections) FAB — sits above the main FAB ──
        FloatingActionButton(
            onClick = { flowOpen = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 88.dp),
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Icon(CaIcons.gitBranch, contentDescription = "Action Flow (nodos)")
        }

        // ── Main FAB (Project / Libraries / Compose picker) ──
        FloatingActionButton(
            onClick = { pickerOpen = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(CaIcons.plus, contentDescription = "Añadir componente")
        }

        if (pickerOpen) {
            val screen = selectedScreen()
            if (screen != null) {
                CanvasPickerSheet(
                    backend = backend,
                    onDismiss = { pickerOpen = false },
                    onComponentPlaced = { kind, label ->
                        val idSuffix = System.currentTimeMillis()
                        val item = CanvasItem(
                            id = "canvas_item_$idSuffix",
                            kind = kind,
                            position = Offset(24f, 24f + (screen.items.size * 60f)),
                            label = label,
                        )
                        screen.items.add(item)
                        selectedId = item.id
                        persist(canvasState, backend)
                    },
                    onIconPlaced = { icon, _ ->
                        val idSuffix = System.currentTimeMillis()
                        val item = CanvasItem(
                            id = "canvas_icon_$idSuffix",
                            kind = CanvasComponentKind.Image,
                            position = Offset(24f, 24f + (screen.items.size * 60f)),
                            label = icon.name.replace(" ", "_"),
                        )
                        screen.items.add(item)
                        selectedId = item.id
                        persist(canvasState, backend)
                    },
                )
            }
        }

        if (flowOpen) {
            ActionFlowSheet(
                state = canvasState,
                onDismiss = { flowOpen = false },
            )
        }

        if (iconManagerOpen) {
            val screen = selectedScreen()
            if (screen != null) {
                IconManagerSheet(
                    backend = backend,
                    resolveDrawableDir = { resolveResDir(backend) },
                    onDismiss = { iconManagerOpen = false },
                    onIconPlaced = { xml, fileName ->
                        val drawableName = fileName.removeSuffix(".xml")
                        val idSuffix = System.currentTimeMillis()
                        val item = CanvasItem(
                            id = "canvas_icon_$idSuffix",
                            kind = CanvasComponentKind.Image,
                            position = Offset(24f, 24f + (screen.items.size * 60f)),
                            label = drawableName,
                        )
                        screen.items.add(item)
                        selectedId = item.id
                        persist(canvasState, backend)
                    },
                )
            }
        }

        // ── Screen rename dialog ──
        renameScreenId?.let { id ->
            val sc = canvasState.screens.find { it.id == id }
            if (sc != null) {
                val title = sc.name
                RenameScreenDialog(
                    initialName = title,
                    onDismiss = { renameScreenId = null },
                    onRename = { newName ->
                        val trimmed = newName.trim()
                        if (trimmed.isNotBlank()) {
                            sc.name = trimmed
                            persist(canvasState, backend)
                        }
                        renameScreenId = null
                    },
                )
            }
        }

        // ── Properties dialog for the tapped component ──
        editingId?.let { id ->
            val screen = selectedScreen()
            if (screen != null) {
                val item = screen.items.find { it.id == id }
                if (item != null) {
                    val idx = screen.items.indexOf(item)
                    ItemPropertiesDialog(
                        item = item,
                        onDismiss = { editingId = null },
                        onSave = { updated ->
                            screen.items[idx] = updated
                            selectedId = id
                            persist(canvasState, backend)
                        },
                    )
                }
            }
        }
    }
}

// ── Placed canvas item ─────────────────────────────────────────────────────────

@Composable
private fun PlacedCanvasItem(
    item: CanvasItem,
    selected: Boolean,
    hasConnection: Boolean,
    connectionTarget: String?,
    frameSizePx: IntOffset,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDelete: () -> Unit,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    // Effective rendered size (honour user-set width/height when present).
    val itemWidthDp = item.widthDp.takeIf { it > 0f } ?: defaultWidthFor(item.kind)
    val itemHeightDp = item.heightDp.takeIf { it > 0f } ?: defaultHeightFor(item.kind)
    val bg = parseColor(item.bgColor) ?: MaterialTheme.colorScheme.surfaceVariant
    val fg = parseColor(item.textColor) ?: MaterialTheme.colorScheme.onSurface
    val textSize = item.textSizeSp.takeIf { it > 0f } ?: 12f

    Column(
        modifier = Modifier
            // Position in dp; render using the Dp offset overload so drag maths line up.
            .offset(x = item.position.x.dp, y = item.position.y.dp)
            .width(itemWidthDp.dp)
            .height(itemHeightDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg, RoundedCornerShape(8.dp))
            .pointerInput(item.id, frameSizePx) {
                // Immediate drag (no long-press) so moving feels responsive and reaches the bottom.
                detectDragGestures(
                    onDragStart = { dragging = true; onDragStart() },
                    onDragEnd = { dragging = false; onDragEnd() },
                    onDragCancel = { dragging = false; onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragMove(Offset(with(density) { dragAmount.x.toDp().value }, with(density) { dragAmount.y.toDp().value }))
                    },
                )
            }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(if (selected) 2.dp else 0.dp, border),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    item.label.ifBlank { item.kind.displayName },
                    color = fg,
                    fontSize = textSize.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasConnection) {
                    Text(
                        "→ ${connectionTarget ?: "?"}",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (item.kind == CanvasComponentKind.Button) {
                    Icon(
                        CaIcons.plus,
                        contentDescription = "Conectar a pantalla",
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(onClick = onConnect),
                        tint = if (hasConnection) MaterialTheme.colorScheme.tertiary else fg.copy(alpha = 0.6f),
                    )
                }
            }
        }
        if (selected) {
            Row(
                Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CaIcons.gear, contentDescription = "Editar", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(11.dp))
                }
                Box(
                    Modifier.size(18.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CaIcons.close, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(11.dp))
                }
            }
        }
    }
}

private fun defaultWidthFor(kind: CanvasComponentKind): Float = when (kind) {
    CanvasComponentKind.Button -> 110f
    CanvasComponentKind.TextField -> 200f
    CanvasComponentKind.Image -> 48f
}

private fun defaultHeightFor(kind: CanvasComponentKind): Float = when (kind) {
    CanvasComponentKind.Button -> 40f
    CanvasComponentKind.TextField -> 44f
    CanvasComponentKind.Image -> 48f
}

private fun parseColor(hex: String): Color? {
    val h = hex.removePrefix("#")
    if (h.length != 6 && h.length != 8) return null
    val v = h.toLongOrNull(16) ?: return null
    return if (h.length == 8) {
        Color((v ushr 24) and 0xFF, (v ushr 16) and 0xFF, (v ushr 8) and 0xFF, v and 0xFF)
    } else {
        Color((v ushr 16) and 0xFF, (v ushr 8) and 0xFF, v and 0xFF)
    }
}

// ── Action Flow (connections) sheet ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionFlowSheet(state: CanvasState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Action Flow (nodos)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                "Conecta botones de una pantalla hacia otras. Toca → en un botón del canvas y luego la pantalla destino.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            if (state.connections.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Sin conexiones todavía", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                state.connections.forEach { c ->
                    val fromScreen = state.screens.find { it.id == c.fromScreenId }?.name ?: c.fromScreenId
                    val toScreen = state.screens.find { it.id == c.toScreenId }?.name ?: c.toScreenId
                    val fromItem = state.screens.find { it.id == c.fromScreenId }
                        ?.items?.find { it.id == c.fromItemId }?.label ?: c.fromItemId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("$fromScreen · $fromItem", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text("→ $toScreen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            Icon(
                                CaIcons.close,
                                contentDescription = "Borrar conexión",
                                modifier = Modifier.size(16.dp).clickable {
                                    state.removeConnection(c.fromScreenId, c.fromItemId)
                                },
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    }
}

// ── Component / Library / Compose picker sheet ─────────────────────────────────

private enum class PickerTab(val title: String) {
    Project("Project"),
    Libraries("Libraries"),
    Compose("Compose"),
}

private data class CatalogGroup(
    val name: String,
    val count: String,
    val icons: List<UiCatalogIcon>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanvasPickerSheet(
    backend: IdeBackend,
    onDismiss: () -> Unit,
    onComponentPlaced: (CanvasComponentKind, String) -> Unit,
    onIconPlaced: (UiCatalogIcon, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(PickerTab.Project) }
    val icons = remember(backend) { backend.icons.icons() }
    val parsed = remember(icons) { icons.associateWith { parseVectorXml(it.xml) } }
    // Simple heuristic grouping: match icons whose name begins with a known prefix family.
    val groups = remember(icons) { groupIcons(icons) }
    var selectedIcon by remember { mutableStateOf<UiCatalogIcon?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Text(
            "Añadir al canvas",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        // ── Tab row ──
        TabRow(selectedTabIndex = tab.ordinal) {
            PickerTab.values().forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            when (tab) {
                PickerTab.Project -> ProjectContent(
                    onComponentPlaced = onComponentPlaced,
                )

                PickerTab.Libraries -> LibrariesContent(
                    groups = groups,
                    parsed = parsed,
                    onIcon = { icon -> selectedIcon = icon },
                )

                PickerTab.Compose -> ComposeContent(backend)
            }
        }
    }

    selectedIcon?.let { icon ->
        RenameIconDialog(
            icon = icon,
            backend = backend,
            resolveDrawableDir = { resolveResDir(backend) },
            onDismiss = { selectedIcon = null },
            onCopied = { finalName, dismissDialog ->
                onIconPlaced(icon, finalName)
                dismissDialog()
            },
        )
    }
}

@Composable
private fun ProjectContent(
    onComponentPlaced: (CanvasComponentKind, String) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text(
        "Componentes",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ComponentCard(
            modifier = Modifier.weight(1f),
            label = "Botón",
            icon = "BTN",
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = { onComponentPlaced(CanvasComponentKind.Button, "Button") },
        )
        ComponentCard(
            modifier = Modifier.weight(1f),
            label = "Texto",
            icon = "TXT",
            color = MaterialTheme.colorScheme.secondaryContainer,
            onClick = { onComponentPlaced(CanvasComponentKind.TextField, "TextField") },
        )
        ComponentCard(
            modifier = Modifier.weight(1f),
            label = "Imagen",
            icon = "IMG",
            color = MaterialTheme.colorScheme.tertiaryContainer,
            onClick = { onComponentPlaced(CanvasComponentKind.Image, "Image") },
        )
    }
    Spacer(Modifier.height(20.dp))
    Text(
        "Los componentes se colocan automáticamente en el canvas. Arrastra para moverlos, toca dos veces para editar.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LibrariesContent(
    groups: List<CatalogGroup>,
    parsed: Map<UiCatalogIcon, UvIcon?>,
    onIcon: (UiCatalogIcon) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text(
        "Material Symbols",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "${groups.sumOf { it.icons.size }} iconos",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Sin iconos cargados", color = MaterialTheme.colorScheme.outline)
        }
    } else {
        groups.forEach { group ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(group.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                ) {
                    Text(
                        group.count,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxWidth().height((group.icons.size / 5 + 1) * 72.dp)
                    .heightIn(max = 220.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(group.icons) { icon ->
                    IconCell(icon = icon, vec = parsed[icon], onClick = { onIcon(icon) })
                }
            }
        }
    }
}

@Composable
private fun ComposeContent(backend: IdeBackend) {
    Spacer(Modifier.height(8.dp))
    Text(
        "Librerías",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    LibraryItem(
        name = "Compose BOM",
        coordinate = "androidx.compose:compose-bom:2025.06.01",
        description = "Jetpack Compose Bill of Materials",
        backend = backend,
    )
    Spacer(Modifier.height(6.dp))
    LibraryItem(
        name = "Material 3",
        coordinate = "androidx.compose.material3:material3",
        description = "Componentes M3 + MaterialTheme",
        backend = backend,
    )
    Spacer(Modifier.height(6.dp))
    LibraryItem(
        name = "Material Icons Extended",
        coordinate = "androidx.compose.material:material-icons-extended",
        description = "3000+ iconos Material en Compose",
        backend = backend,
    )
    Spacer(Modifier.height(6.dp))
    LibraryItem(
        name = "Navigation Compose",
        coordinate = "androidx.navigation:navigation-compose:2.8.9",
        description = "Navegación con rutas",
        backend = backend,
    )
    Spacer(Modifier.height(6.dp))
    LibraryItem(
        name = "Lifecycle ViewModel",
        coordinate = "androidx.lifecycle:lifecycle-viewmodel-compose",
        description = "ViewModel + estado de UI",
        backend = backend,
    )
}

private fun groupIcons(icons: List<UiCatalogIcon>): List<CatalogGroup> {
    val out = ArrayList<CatalogGroup>()
    // "Principales" = first 300 by generic/frequent families; rest grouped by first word.
    val main = icons.take(300)
    out.add(CatalogGroup("Principales", "${main.size}", main))
    val rest = icons.drop(300)
    rest.groupBy { it.name.split(" ", "_").firstOrNull()?.lowercase() ?: "otros" }
        .entries
        .sortedBy { it.key }
        .forEach { (key, list) ->
            out.add(CatalogGroup(key.replaceFirstChar { it.uppercase() }, "${list.size}", list))
        }
    return out
}

// ── Component / Library helper composables ─────────────────────────────────────

@Composable
private fun ComponentCard(
    modifier: Modifier = Modifier,
    label: String,
    icon: String,
    color: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(icon, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun LibraryItem(
    name: String,
    coordinate: String,
    description: String,
    backend: IdeBackend,
) {
    var added by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (added) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    if (!added) {
                        scope.launch {
                            runCatching {
                                backend.deps.addDependency("app", coordinate, "implementation")
                                added = true
                            }
                        }
                    }
                },
            ) {
                Text(
                    if (added) "✓" else "+",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

// ── Item properties dialog ─────────────────────────────────────────────────────

@Composable
private fun RenameScreenDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renombrar pantalla") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

private val SWATCHES = listOf(
    "#FFFFFF", "#FFEBEE", "#E3F2FD", "#E8F5E9", "#FFF3E0",
    "#FCE4EC", "#EDE7F6", "#E0F7FA", "#1A1A1F", "#000000",
    "#F44336", "#2196F3", "#4CAF50", "#FF9800", "#9C27B0",
    "#00BCD4", "#FFEB3B", "#009688", "#3F51B5", "#795548",
)

@Composable
private fun ItemPropertiesDialog(
    item: CanvasItem,
    onDismiss: () -> Unit,
    onSave: (CanvasItem) -> Unit,
) {
    var label by remember(item.id) { mutableStateOf(item.label) }
    var width by remember(item.id) { mutableStateOf(item.widthDp.takeIf { it > 0f }?.toInt()?.toString() ?: "") }
    var height by remember(item.id) { mutableStateOf(item.heightDp.takeIf { it > 0f }?.toInt()?.toString() ?: "") }
    var textSize by remember(item.id) { mutableStateOf(item.textSizeSp.takeIf { it > 0f }?.toInt()?.toString() ?: "") }
    var bg by remember(item.id) { mutableStateOf(item.bgColor.ifBlank { "#E0E0E0" }) }
    var textCol by remember(item.id) { mutableStateOf(item.textColor.ifBlank { "#000000" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Propiedades · ${item.kind.displayName}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it },
                        label = { Text("Ancho (dp)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it },
                        label = { Text("Alto (dp)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = textSize,
                    onValueChange = { textSize = it },
                    label = { Text("Tamaño texto (sp)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Text("Color fondo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                ColorSwatches(current = bg, onPick = { bg = it })
                Spacer(Modifier.height(12.dp))
                Text("Color texto", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                ColorSwatches(current = textCol, onPick = { textCol = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    item.copy(
                        label = label.trim(),
                        widthDp = width.toFloatOrNull() ?: 0f,
                        heightDp = height.toFloatOrNull() ?: 0f,
                        textSizeSp = textSize.toFloatOrNull() ?: 0f,
                        bgColor = bg,
                        textColor = textCol,
                    ),
                )
                onDismiss()
            }) { Text("Aplicar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun ColorSwatches(current: String, onPick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxWidth().height(90.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(SWATCHES) { hex ->
            val isCurrent = current.equals(hex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(parseColor(hex) ?: Color.White)
                    .clickable { onPick(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (isCurrent) {
                    Icon(CaIcons.check, null, Modifier.size(16.dp), tint = Color.White)
                }
            }
        }
    }
}

// ── Android resource dir helpers ───────────────────────────────────────────────

private fun resolveResDir(backend: IdeBackend): String {
    val root = runCatching { backend.files.fileTree() }.getOrNull() ?: return CANVAS_DIR
    return findResDir(root)?.resDirPath ?: CANVAS_DIR
}

private fun findResDir(node: TreeNode): TreeNode? {
    if (node.kind == NodeKind.Folder && node.name == "res" && node.resDirPath != null) return node
    node.children.forEach { child -> findResDir(child)?.let { return it } }
    return null
}

private fun resolveLayoutDir(backend: IdeBackend): String {
    val res = resolveResDir(backend)
    return if (res != CANVAS_DIR) "$res/layout" else ""
}

// ── Android code generation ────────────────────────────────────────────────────

private fun generateMainFiles(state: CanvasState, backend: IdeBackend) {
    runCatching {
        val layoutDir = resolveLayoutDir(backend)
        if (layoutDir.isBlank()) return@runCatching
        for (screen in state.screens) {
            backend.editor.saveFile(
                "$layoutDir/activity_${screenName(screen.name)}.xml",
                buildActivityXml(screen),
            )
        }
        val existing = runCatching { backend.files.readFile("$CANVAS_DIR/$MAIN_ACTIVITY") }.getOrNull()
        if (existing.isNullOrBlank()) {
            backend.editor.saveFile("$CANVAS_DIR/$MAIN_ACTIVITY", buildMainActivityKt(state))
        }
    }
}

private fun buildActivityXml(screen: Screen): String = buildString {
    append(XML_HEADER)
    append("<FrameLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
    append("    xmlns:tools=\"http://schemas.android.com/tools\"\n")
    append("    android:id=\"@+id/canvas_root\"\n")
    append("    android:layout_width=\"match_parent\"\n")
    append("    android:layout_height=\"match_parent\"\n")
    append("    tools:context=\".MainActivity\">\n\n")
    if (screen.items.isEmpty()) {
        append("    <!-- Canvas empty — add components in the Canvas tab -->\n\n")
    } else {
        for (item in screen.items) {
            val id = item.label.replace("[^a-zA-Z0-9_]".toRegex(), "_").lowercase()
            val x = item.position.x.toInt()
            val y = item.position.y.toInt()
            val w = (item.widthDp.takeIf { it > 0f } ?: 0).toInt()
            val h = (item.heightDp.takeIf { it > 0f } ?: 0).toInt()
            when (item.kind) {
                CanvasComponentKind.Button -> {
                    append("    <Button\n")
                    append("        android:id=\"@+id/$id\"\n")
                    if (w > 0) append("        android:layout_width=\"${w}dp\"\n")
                    else append("        android:layout_width=\"wrap_content\"\n")
                    if (h > 0) append("        android:layout_height=\"${h}dp\"\n")
                    else append("        android:layout_height=\"wrap_content\"\n")
                    append("        android:layout_marginStart=\"${x}dp\"\n")
                    append("        android:layout_marginTop=\"${y}dp\"\n")
                    item.bgColor.trim().takeIf { it.isNotBlank() }?.let { c ->
                        append("        android:backgroundTint=\"${c}\"\n")
                    }
                    append("        android:text=\"${item.label}\"")
                    item.textColor.trim().takeIf { it.isNotBlank() }?.let { c ->
                        append(" android:textColor=\"${c}\"")
                    }
                    if (item.textSizeSp > 0f) append(" android:textSize=\"${item.textSizeSp.toInt()}sp\"")
                    append(" />\n\n")
                }
                CanvasComponentKind.TextField -> {
                    append("    <EditText\n")
                    append("        android:id=\"@+id/$id\"\n")
                    append("        android:layout_width=\"match_parent\"\n")
                    append("        android:layout_height=\"wrap_content\"\n")
                    append("        android:layout_marginStart=\"${x}dp\"\n")
                    append("        android:layout_marginTop=\"${y}dp\"\n")
                    append("        android:hint=\"${item.label}\" />\n\n")
                }
                CanvasComponentKind.Image -> {
                    append("    <ImageView\n")
                    append("        android:id=\"@+id/$id\"\n")
                    append("        android:layout_width=\"${if (w > 0) w else 48}dp\"\n")
                    append("        android:layout_height=\"${if (h > 0) h else 48}dp\"\n")
                    append("        android:layout_marginStart=\"${x}dp\"\n")
                    append("        android:layout_marginTop=\"${y}dp\"\n")
                    append("        android:src=\"@drawable/$id\" />\n\n")
                }
            }
        }
    }
    append("</FrameLayout>\n")
}

private fun buildMainActivityKt(state: CanvasState): String = buildString {
    append("package com.example.app\n\n")
    append("import android.content.Intent\n")
    append("import android.os.Bundle\n")
    append("import android.widget.Toast\n")
    append("import androidx.appcompat.app.AppCompatActivity\n")
    append("import android.widget.Button\n\n")
    append("class MainActivity : AppCompatActivity() {\n\n")
    append("    override fun onCreate(savedInstanceState: Bundle?) {\n")
    append("        super.onCreate(savedInstanceState)\n")
    append("        setContentView(R.layout.activity_${screenName(state.screens.firstOrNull()?.name ?: "main")})\n\n")
    for (screen in state.screens) {
        val screenId = screenName(screen.name)
        for (item in screen.items) {
            val id = item.label.replace("[^a-zA-Z0-9_]".toRegex(), "_").lowercase()
            val con = state.findConnection(screen.id, item.id)
            when (item.kind) {
                CanvasComponentKind.Button -> {
                    append("        // ${screen.name} → ${item.label}\n")
                    append("        findViewById<Button>(R.id.$id).setOnClickListener {\n")
                    if (con != null) {
                        val targetScreen = state.screens.find { it.id == con.toScreenId }
                        if (targetScreen != null) {
                            append("            startActivity(Intent(this, MainActivity::class.java).apply {\n")
                            append("                putExtra(\"screen\", \"${screenName(targetScreen.name)}\")\n")
                            append("            })\n")
                        } else {
                            append("            Toast.makeText(this, \"${item.label} → target not set\", Toast.LENGTH_SHORT).show()\n")
                        }
                    } else {
                        append("            Toast.makeText(this, \"${item.label} clicked\", Toast.LENGTH_SHORT).show()\n")
                    }
                    append("        }\n\n")
                }
                CanvasComponentKind.TextField -> { }
                CanvasComponentKind.Image -> { }
            }
        }
    }
    append("    }\n")
    append("}\n")
}

private fun screenName(raw: String): String =
    raw.trim().replace("[^a-zA-Z0-9_]".toRegex(), "_").lowercase().ifBlank { "screen" }
