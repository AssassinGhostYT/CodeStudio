package dev.ide.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.launch

/**
 * Visual Canvas — Phase 1.
 *
 * A phone-shape surface that replaces the editor when the active tab is in [dev.ide.ui.EditorViewMode.Canvas].
 * The user opens the FAB to pick a component kind (Button, TextField, Image, Container); tapping a kind
 * appends an in-memory [CanvasItem] AND creates an XML stub under `<workspace>/.platform/canvas/` so the file
 * tree refreshes. Tapping a placed item selects it (highlight + delete affordance) — delete removes both the
 * item and the file.
 *
 * Phase 1 deliberately keeps layout state in memory (no `canvas.layout.v1` persistence, no drag-to-reposition,
 * no resize, no AI wire-up). Persistence + move/resize lands in Phase 2; AI + action chains in Phases 3-4.
 *
 * The choice to write under `.platform/canvas/` rather than `app/src/main/res/layout/` is also Phase 1: it
 * keeps the tree tidy and lets the user wipe the sandbox by deleting one folder. Phase 5 routes output to the
 * correct resource directory per platform.
 */

private const val CANVAS_DIR = ".platform/canvas"

private data class CanvasItem(
    val id: String,
    val kind: CanvasComponentKind,
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
                    .background(Color(0xFF1A1A1F)),
            ) {
                if (items.isEmpty()) {
                    Text(
                        "Toca + para añadir componentes",
                        color = Color(0xFF888888),
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items.forEach { item ->
                            PlacedCanvasItem(
                                item = item,
                                selected = selectedId == item.id,
                                onClick = { selectedId = item.id },
                                onDelete = {
                                    items.remove(item)
                                    if (selectedId == item.id) selectedId = null
                                    scope.launch { runCatching { backend.files.deletePath(canvasPathFor(item)) } }
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
                    val item = CanvasItem(
                        id = "canvas_${kind.name.lowercase()}_${System.currentTimeMillis()}",
                        kind = kind,
                    )
                    items.add(item)
                    paletteOpen = false
                    scope.launch {
                        runCatching {
                            backend.files.createFile(
                                dirPath = CANVAS_DIR,
                                fileName = "${item.id}.xml",
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
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(2.dp, border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.kind.displayName, modifier = Modifier.weight(1f))
            if (selected) {
                TextButton(onClick = onDelete) {
                    Icon(CaIcons.close, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun canvasPathFor(item: CanvasItem): String = "$CANVAS_DIR/${item.id}.xml"