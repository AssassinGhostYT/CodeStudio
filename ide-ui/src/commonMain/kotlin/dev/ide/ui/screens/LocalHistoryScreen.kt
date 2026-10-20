package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLocalHistoryEntry
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.UnifiedDiffView
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.local_history_current
import dev.ide.ui.generated.resources.local_history_empty
import dev.ide.ui.generated.resources.local_history_restore
import dev.ide.ui.generated.resources.local_history_restore_body
import dev.ide.ui.generated.resources.local_history_restore_title
import dev.ide.ui.generated.resources.local_history_size
import dev.ide.ui.generated.resources.local_history_title
import dev.ide.ui.platform.ioDispatcher
import dev.ide.ui.platform.nowMillis
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Local History for one file: every version remembered on save, newest first, with a coloured diff against what
 * is in the editor right now and a restore that loads a version back into the buffer — never straight onto disk,
 * so the user can still undo it and nothing is written until they save.
 *
 * The index and the snapshot bodies are read off the main thread ([ioDispatcher]): this is the same on-disk
 * store the save path writes, and on Android a read on the composition thread is exactly the "App not
 * responding" trap the rest of the IDE avoids.
 */
@Composable
fun LocalHistoryScreen(
    backend: IdeBackend,
    path: String,
    currentText: String,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
) {
    var entries by remember(path, backend) { mutableStateOf<List<UiLocalHistoryEntry>>(emptyList()) }
    var loaded by remember(path, backend) { mutableStateOf(false) }
    LaunchedEffect(path, backend) {
        loaded = false
        entries = withContext(ioDispatcher) { backend.localHistory.entries(path) }
        loaded = true
    }

    var selected by remember { mutableStateOf<UiLocalHistoryEntry?>(null) }
    var confirmRestore by remember { mutableStateOf<String?>(null) }
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')

    val selectedEntry = selected
    if (selectedEntry != null) {
        // Diff first, restore second: the user always sees what a version changes before putting it back.
        var snapshotText by remember(selectedEntry.id) { mutableStateOf<String?>(null) }
        LaunchedEffect(selectedEntry.id) {
            snapshotText = withContext(ioDispatcher) { backend.localHistory.read(selectedEntry.id) }
        }
        val old = snapshotText
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            TextButton(onClick = { selected = null }) { Text("← ${stringResource(Res.string.back)}") }
            Text(
                fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (old != null) {
                UnifiedDiffView(
                    old = old,
                    new = currentText,
                    modifier = Modifier.weight(1f).padding(top = 4.dp),
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { confirmRestore = old }) {
                        Text(stringResource(Res.string.local_history_restore))
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Text(
                "${stringResource(Res.string.local_history_title)} — $fileName",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            if (entries.isEmpty() && loaded) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(Res.string.local_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Capture the clock once per change so every row's "hace …" is consistent within a frame.
                val now = remember(entries) { nowMillis() }
                LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        HistoryRow(entry, now) { selected = entry }
                    }
                }
            }
        }
    }

    val restore = confirmRestore
    if (restore != null) {
        CenteredDialog(visible = true, onDismiss = { confirmRestore = null }) {
            Column(
                Modifier
                    .widthIn(max = 380.dp)
                    .padding(horizontal = 24.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(Res.string.local_history_restore_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(Res.string.local_history_restore_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { confirmRestore = null }, Modifier.weight(1f)) {
                        Text(stringResource(Res.string.cancel))
                    }
                    TextButton(onClick = {
                        confirmRestore = null
                        onRestore(restore)
                    }, Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.local_history_restore),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: UiLocalHistoryEntry, now: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                relativeLabel(now, entry.timestampMs),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (entry.current) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                stringResource(Res.string.local_history_size, timeOfDay(entry.timestampMs), entry.bytes / 1024),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.current) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(Res.string.local_history_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** "hace un momento" / "hace 5 min" / "hace 3 h" / "hace 2 días" — relative only, so no calendar/timezone code
 *  is needed in commonMain. */
private fun relativeLabel(nowMs: Long, thenMs: Long): String {
    val diff = (nowMs - thenMs).coerceAtLeast(0)
    return when {
        diff < 60_000L -> "hace un momento"
        diff < 3_600_000L -> "hace ${diff / 60_000L} min"
        diff < 86_400_000L -> "hace ${diff / 3_600_000L} h"
        else -> "hace ${diff / 86_400_000L} días"
    }
}

/** The HH:mm:ss part of a snapshot's timestamp, as a fixed-width label next to its age. */
private fun timeOfDay(ms: Long): String {
    val secs = ms / 1000
    val s = (secs % 60).toInt()
    val m = ((secs / 60) % 60).toInt()
    val h = ((secs / 3600) % 24).toInt()
    return "${pad2(h)}:${pad2(m)}:${pad2(s)}"
}

private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()
