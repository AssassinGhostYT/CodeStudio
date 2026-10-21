package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiDeviceEntry
import dev.ide.ui.backend.UiDeviceListing
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import org.jetbrains.compose.resources.stringResource

/**
 * Device File Explorer — browse the filesystem of the device that runs the IDE and pull device files into the
 * open project. No ADB, no root: only paths the app can already read appear. Opens from the editor's overflow
 * menu. Tapping a directory descends into it; tapping a file opens it in the editor; the download action copies
 * it into the project root.
 */
@Composable
fun DeviceFileExplorerScreen(
    backend: IdeBackend,
    initialPath: String?,
    projectDir: String?,
    onBack: () -> Unit,
    onOpenFile: (devicePath: String) -> Unit,
) {
    val service = backend.deviceFiles
    val defaultRoot = remember(service) { runCatching { service.defaultPath() }.getOrNull() ?: "/" }
    var currentPath by remember { mutableStateOf(initialPath ?: defaultRoot) }
    var listing by remember { mutableStateOf<UiDeviceListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    val roots = remember(service) { runCatching { service.roots() }.getOrDefault(emptyList()) }

    LaunchedEffect(currentPath, service) {
        loading.value = true
        listing.value = runCatching { service.list(currentPath) }
            .getOrDefault(UiDeviceListing(currentPath, error = "error"))
        loading.value = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        TextButton(onClick = onBack) { Text("← ${stringResource(Res.string.back)}") }
        Text(
            "Device File Explorer",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )

        // Quick roots
        if (roots.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                roots.forEach { root ->
                    RootChip(root, active = root.path == currentPath) { currentPath = root.path }
                }
            }
        }

        // Breadcrumb
        Breadcrumbs(currentPath) { target -> currentPath = target }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        val l = listing
        when {
            loading.value -> CenterText("Cargando…")

            l == null || l.error != null -> CenterText(l?.error?.let { "No se puede abrir: $it" } ?: "No se puede abrir esta carpeta")

            l.entries.isEmpty() -> CenterText("Carpeta vacía")

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(l.entries) { entry ->
                    DeviceRow(
                        entry = entry,
                        canPull = !entry.isDirectory && projectDir != null,
                    ) { action ->
                        when (action) {
                            RowAction.Open -> if (entry.isDirectory) currentPath = entry.path else onOpenFile(entry.path)
                            RowAction.Pull -> {
                                val dest = projectDir?.let { service.pullToProject(entry.path, it) }
                                status = if (dest != null) "Descargado al proyecto: $dest" else "No se pudo descargar"
                            }
                        }
                    }
                }
            }
        }

        // Footer: a hint of where pulled files land.
        if (projectDir != null) {
            Text(
                "Los archivos descargados se guardan en: $projectDir",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RootChip(root: UiDeviceEntry, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(root.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "/",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onNavigate("/") }.padding(horizontal = 2.dp),
        )
        var acc = ""
        segments.forEach { seg ->
            acc = "$acc/$seg"
            val target = acc
            Text("›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                seg,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onNavigate(target) }.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun DeviceRow(entry: UiDeviceEntry, canPull: Boolean, onAction: (RowAction) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onAction(RowAction.Open) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderOrFileGlyph(entry)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (entry.isDirectory) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (entry.isDirectory) entry.path else "${formatBytes(entry.sizeBytes)} · ${entry.path}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!entry.readable) {
            Text("sin permiso", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        } else if (canPull) {
            TextButton(onClick = { onAction(RowAction.Pull) }) {
                Text("Descargar", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** A row's user gesture: open (descend / open in editor) or pull the file into the project. */
private enum class RowAction { Open, Pull }

@Composable
private fun FolderOrFileGlyph(entry: UiDeviceEntry) {
    val color = if (entry.isDirectory) Color(0xFFFFB74D) else Color(0xFF90A4AE)
    Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "${(bytes / 1_073_741_824.0).toString().take(4)} GB"
    bytes >= 1_048_576 -> "${(bytes / 1_048_576.0).toString().take(4)} MB"
    bytes >= 1_024 -> "${(bytes / 1024.0).toString().take(4)} KB"
    else -> "$bytes B"
}
