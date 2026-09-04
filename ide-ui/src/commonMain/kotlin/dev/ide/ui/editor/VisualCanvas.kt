package dev.ide.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.launch

/**
 * Visual Canvas — Multi-screen visual editor with Action Flow navigation.
 *
 * Each screen is a phone-frame with components positioned at user-chosen dp coordinates.
 * Screen tabs appear at the top. The FAB opens the Material Icon Manager.
 *
 * Connector icon (→) on each button lets the user visually link it to another screen.
 * A small arrow indicator shows which buttons have existing connections.
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
        screens.removeAll { it.id == id }
        connections.removeAll { it.fromScreenId == id || it.toScreenId == id }
        if (activeScreenId == id) activeScreenId = screens.firstOrNull()?.id ?: "main"
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
                sb.append("""{"id":"${esc(item.id)}","kind":"${item.kind.name}","x":${item.position.x},"y":${item.position.y},"label":"${esc(item.label)}","w":${item.widthDp},"h":${item.heightDp},"bg":"${esc(item.bgColor)}","tc":"${esc(item.textColor)}","ts":${item.textSizeSp}}""")
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
    var iconManagerOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var frameSizePx by remember { mutableStateOf(IntOffset.Zero) }
    var connectingFrom by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Crosshair guide state: set by PlacedCanvasItem during drag, cleared on drag-end.
    var dragItemId by remember { mutableStateOf<String?>(null) }
    var dragCenterDp by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
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
        Column(modifier = Modifier.align(Alignment.TopCenter)) {
            // ── Screen tabs ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
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
                            .clickable {
                                if (connectingFrom != null && connectingFrom!!.first != screen.id) {
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
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = screen.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            if (canvasState.screens.size > 1) {
                                Icon(
                                    CaIcons.close,
                                    contentDescription = "Eliminar pantalla",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { canvasState.removeScreen(screen.id); persist(canvasState, backend) },
                                    tint = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                // + button to add screen
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { canvasState.addScreen(); persist(canvasState, backend) },
                ) {
                    Icon(
                        CaIcons.plus,
                        contentDescription = "Nueva pantalla",
                        modifier = Modifier.padding(6.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
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

            // ── Phone frame ──
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
                            "Toca + para añadir componentes\nToca → en un botón para conectar pantallas",
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
                                    onClick = { selectedId = item.id },
                                    onDragStart = { dragItemId = item.id },
                                    onDragMove = { delta ->
                                        updatePosition(item.id, delta)
                                        val idx = items.indexOfFirst { it.id == item.id }
                                        if (idx >= 0) {
                                            val it = items[idx]
                                            dragCenterDp = Offset(it.position.x + 30f, it.position.y + 12f)
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
                                )
                            }
                            // ── Crosshair guides (visible while dragging) ──
                            if (dragItemId != null) {
                                val guideColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                val guideWidth = with(density) { 1.dp.toPx() }
                                val centerX = with(density) { dragCenterDp.x.toDp().toPx() }
                                val centerY = with(density) { dragCenterDp.y.toDp().toPx() }
                                // Vertical guide
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .drawBehind {
                                            drawLine(guideColor, Offset(centerX, 0f), Offset(centerX, size.height), strokeWidth = guideWidth)
                                        },
                                )
                                // Horizontal guide
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

            Spacer(Modifier.height(52.dp))
        }

        // ── FAB (Component Picker) ──
        FloatingActionButton(
            onClick = { pickerOpen = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(CaIcons.plus, contentDescription = "Añadir componente")
        }

        if (pickerOpen) {
            val screen = selectedScreen()
            if (screen != null) {
                ComponentPickerSheet(
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
                        pickerOpen = false
                    },
                    onIconManagerOpen = { iconManagerOpen = true },
                )
            }
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
                    onDragStart = { dragging = true; onDragStart() },
                    onDragEnd = { dragging = false; onDragEnd() },
                    onDragCancel = { dragging = false; onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val dxDp = with(density) { dragAmount.x.toDp().value }
                        val dyDp = with(density) { dragAmount.y.toDp().value }
                        onDragMove(Offset(dxDp, dyDp))
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
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    item.label.ifBlank { item.kind.displayName },
                    style = MaterialTheme.typography.labelSmall,
                )
                if (hasConnection) {
                    Text(
                        "→ ${connectionTarget ?: "?"}",
                        style = MaterialTheme.typography.labelSmall,
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
                        tint = if (hasConnection) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 3.dp)
                    .size(18.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.close, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(11.dp))
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
            when (item.kind) {
                CanvasComponentKind.Button -> {
                    append("    <Button\n")
                    append("        android:id=\"@+id/$id\"\n")
                    append("        android:layout_width=\"wrap_content\"\n")
                    append("        android:layout_height=\"wrap_content\"\n")
                    append("        android:layout_marginStart=\"${x}dp\"\n")
                    append("        android:layout_marginTop=\"${y}dp\"\n")
                    append("        android:text=\"${item.label}\" />\n\n")
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
                    append("        android:layout_width=\"48dp\"\n")
                    append("        android:layout_height=\"48dp\"\n")
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

// ── Component Picker Sheet (FAB menu) ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComponentPickerSheet(
    backend: IdeBackend,
    onDismiss: () -> Unit,
    onComponentPlaced: (CanvasComponentKind, String) -> Unit,
    onIconManagerOpen: () -> Unit,
) {
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
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // ── Header ──
            Text(
                "Añadir al canvas",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // ── Components section ──
            SectionHeader("Componentes")
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

            // ── Icons section ──
            SectionHeader("Iconos M3")
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss(); onIconManagerOpen() },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(CaIcons.plus, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Iconos M3", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "6800+ Material Design icons",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Libraries section ──
            SectionHeader("Librerías")
            Spacer(Modifier.height(8.dp))
            LibraryItem(
                name = "Material Design 3",
                coordinate = "com.google.android.material:material:1.12.0",
                description = "Componentes M3: Button, Card, FAB, TopAppBar, BottomNavigation…",
                backend = backend,
            )
            Spacer(Modifier.height(6.dp))
            LibraryItem(
                name = "AppCompat",
                coordinate = "androidx.appcompat:appcompat:1.7.0",
                description = "Backward-compatible Activity, ActionBar, Theme.AppCompat",
                backend = backend,
            )
            Spacer(Modifier.height(6.dp))
            LibraryItem(
                name = "ConstraintLayout",
                coordinate = "androidx.constraintlayout:constraintlayout:2.2.1",
                description = "Layout flexible con constraints y guidelines",
                backend = backend,
            )
            Spacer(Modifier.height(6.dp))
            LibraryItem(
                name = "RecyclerView",
                coordinate = "androidx.recyclerview:recyclerview:1.4.0",
                description = "Listas eficientes con ViewHolder pattern",
                backend = backend,
            )
            Spacer(Modifier.height(6.dp))
            LibraryItem(
                name = "Glide",
                coordinate = "com.github.bumptech.glide:glide:4.16.0",
                description = "Carga y cache de imágenes",
                backend = backend,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

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
