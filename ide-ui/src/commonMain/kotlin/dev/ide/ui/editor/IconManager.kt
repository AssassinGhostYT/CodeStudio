package dev.ide.ui.editor

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCatalogIcon
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * The Material-icon picker for the Canvas. Fetches the catalog from [IdeBackend.icons], shows a searchable
 * grid of rendered vectors, and on tap opens a rename box that copies the `<vector>` XML into the project's
 * `res/drawable/` via [IdeBackend.files.createFile]. Mirrors CodeAssist's `ActivityM3Icons` + `IconCopier`,
 * in Compose and wired to this IDE's backend.
 */
@Composable
internal fun IconManagerSheet(
    backend: IdeBackend,
    resolveDrawableDir: () -> String,
    onDismiss: () -> Unit,
    onIconPlaced: (xml: String, fileName: String) -> Unit = { _, _ -> },
) {
    var query by remember { mutableStateOf("") }
    val icons = remember(backend) { backend.icons.icons() }
    val filtered = remember(icons, query) {
        if (query.isBlank()) icons
        else icons.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    var selected by remember { mutableStateOf<UiCatalogIcon?>(null) }

    // Pre-parse each icon's XML once so the grid doesn't re-parse per frame.
    val parsed = remember(icons) { icons.associateWith { parseVectorXml(it.xml) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Iconos Material") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Buscar icono…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Sin iconos", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxSize().height(360.dp),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filtered) { icon ->
                            val vec = parsed[icon]
                            IconCell(
                                icon = icon,
                                vec = vec,
                                onClick = { selected = icon },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
    )

    selected?.let { icon ->
        RenameIconDialog(
            icon = icon,
            backend = backend,
            resolveDrawableDir = resolveDrawableDir,
            onDismiss = { selected = null },
            onCopied = { finalName, onDismissDialog ->
                onIconPlaced(icon.xml, finalName)
                onDismissDialog()
            },
        )
    }
}

@Composable
private fun IconCell(icon: UiCatalogIcon, vec: UvIcon?, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (vec != null) {
                Canvas(Modifier.size(26.dp)) {
                    drawUvIcon(vec, color, size)
                }
            } else {
                Icon(CaIcons.box, null, Modifier.size(22.dp), tint = color.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            icon.name,
            style = Ide.type.codeSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Rename + destination selector before copying the icon's XML into the project. Mirrors CodeAssist's
 * "Copy to" flow: a file-name field (pre-filled from the icon name) plus a drawable/mipmap toggle.
 */
@Composable
private fun RenameIconDialog(
    icon: UiCatalogIcon,
    backend: IdeBackend,
    resolveDrawableDir: () -> String,
    onDismiss: () -> Unit,
    onCopied: (String, () -> Unit) -> Unit,
) {
    var fileName by remember(icon) { mutableStateOf(icon.name.replace(" ", "_")) }
    var useMipmap by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copiar icono") },
        text = {
            Column {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("Nombre del archivo") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Destino:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(10.dp))
                    TextButton(onClick = { useMipmap = false }) {
                        Text("drawable", fontWeight = if (!useMipmap) FontWeight.Bold else FontWeight.Normal)
                    }
                    TextButton(onClick = { useMipmap = true }) {
                        Text("mipmap", fontWeight = if (useMipmap) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val base = fileName.trim().replace(" ", "_").ifBlank { icon.name.replace(" ", "_") }
                val safe = base.removeSuffix(".xml")
                val dir = resolveDrawableDir()
                val sub = if (useMipmap) "mipmap" else "drawable"
                scope.launch {
                    runCatching {
                        // Use saveFile (overwrite) so re-placing/updating an icon doesn't silently fail.
                        backend.editor.saveFile("$dir/$sub/$safe.xml", icon.xml)
                    }
                    onCopied("$safe.xml") {
                        onDismiss()
                    }
                }
            }) { Text("Copiar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
