package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiSearchOptions
import dev.ide.ui.components.Chip
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.refresh
import dev.ide.ui.generated.resources.todo_count
import dev.ide.ui.generated.resources.todo_empty
import dev.ide.ui.generated.resources.todo_filter_all
import dev.ide.ui.generated.resources.todo_scanning
import dev.ide.ui.generated.resources.todo_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

internal enum class TodoKind(val label: String) { TODO("TODO"), FIXME("FIXME"), HACK("HACK"), XXX("XXX") }

internal data class TodoItem(
    val kind: TodoKind,
    val filePath: String,
    val fileName: String,
    val line: Int,
    val lineText: String,
    val matchStart: Int,
    val matchEnd: Int,
    val offset: Int,
)

/**
 * Hoisted [TodoScreen] state — the scan results, the active marker filter, and whether the first scan has
 * completed. Remembered by [buildLeftPanels] so the list survives navigating to a hit and reopening the panel.
 */
internal class TodoState {
    val items = mutableStateOf<List<TodoItem>>(emptyList())
    val scanning = mutableStateOf(false)
    val filter = mutableStateOf<TodoKind?>(null)
    var loaded: Boolean = false
}

/**
 * The pending-task tool window: scans the project for pending-task markers in comments and lists them
 * grouped by file. Tapping a row opens the file at the marker. Mirrors Android Studio's pending-task panel
 * without a second index — it reuses the full-text find-in-files engine.
 */
@Composable
internal fun TodoScreen(
    backend: IdeBackend,
    onOpenAt: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    codeFont: FontFamily = FontFamily.Monospace,
    todoState: TodoState = remember { TodoState() },
) {
    var allItems by todoState.items
    var scanning by todoState.scanning
    var filter by todoState.filter
    var refreshEpoch by remember { mutableStateOf(0) }

    LaunchedEffect(refreshEpoch) {
        if (refreshEpoch == 0 && todoState.loaded) return@LaunchedEffect
        scanning = true
        allItems = scanTodos(backend)
        scanning = false
        todoState.loaded = true
    }

    val visible = remember(allItems, filter) {
        if (filter == null) allItems else allItems.filter { it.kind == filter }
    }
    val grouped = remember(visible) { visible.groupBy { it.filePath } }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(Res.string.todo_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (scanning) {
                CircularProgressIndicator(Modifier.size(14.dp), color = MaterialTheme.colorScheme.outline, strokeWidth = 2.dp)
            }
            Box(
                Modifier.size(30.dp).clickable { refreshEpoch++ },
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.refresh, stringResource(Res.string.refresh), Modifier.size(17.dp), tint = MaterialTheme.colorScheme.outline)
            }
        }

        Text(
            stringResource(Res.string.todo_count, visible.size),
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 14.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TodoFilterPill(stringResource(Res.string.todo_filter_all), filter == null) { filter = null }
            TodoKind.entries.forEach { kind ->
                TodoFilterPill(kind.label, filter == kind) { filter = if (filter == kind) null else kind }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                scanning && allItems.isEmpty() -> TodoHint(stringResource(Res.string.todo_scanning))
                visible.isEmpty() -> TodoHint(stringResource(Res.string.todo_empty))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                    grouped.forEach { (path, hits) ->
                        item("todo-file:$path") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(CaIcons.docText, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    hits.first().fileName,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Chip("${hits.size}", fill = MaterialTheme.colorScheme.surfaceContainerHigh, textColor = MaterialTheme.colorScheme.outline)
                            }
                        }
                        items(hits, key = { "todo:${it.filePath}:${it.line}" }) { todo ->
                            TodoRow(todo, codeFont, onOpenAt)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem, codeFont: FontFamily, onOpenAt: (String, Int) -> Unit) {
    val accent = kindColor(item.kind)
    Row(
        Modifier.fillMaxWidth()
            .clickable { onOpenAt(item.filePath, item.offset) }
            .padding(start = 22.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.background(accent.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.xs))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            Text(item.kind.label, color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            "${item.line}",
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = codeFont),
            modifier = Modifier.width(34.dp),
        )
        Text(
            highlightedTodo(item, accent),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFont),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TodoFilterPill(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.background(bg, RoundedCornerShape(Ca.radius.pill))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun TodoHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun kindColor(kind: TodoKind): Color = when (kind) {
    TodoKind.TODO -> MaterialTheme.colorScheme.primary
    TodoKind.FIXME -> MaterialTheme.colorScheme.error
    TodoKind.HACK -> Ide.colors.warning
    TodoKind.XXX -> Ide.colors.info
}

private fun highlightedTodo(item: TodoItem, accent: Color): AnnotatedString {
    val raw = item.lineText
    val lead = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val line = raw.substring(lead)
    val start = (item.matchStart - lead).coerceIn(0, line.length)
    val end = (item.matchEnd - lead).coerceIn(start, line.length)
    return buildAnnotatedString {
        append(line.substring(0, start))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(line.substring(start, end)) }
        append(line.substring(end))
    }
}

private suspend fun scanTodos(backend: IdeBackend): List<TodoItem> {
    val matches = runCatching {
        backend.search.findInFiles(
            "\\b(TODO|FIXME|HACK|XXX)\\b",
            UiSearchOptions(regex = true, caseSensitive = true),
            limit = 1000,
        )
    }.getOrDefault(emptyList())
    val seen = HashSet<String>()
    return matches.mapNotNull { m ->
        val start = m.matchStart.coerceIn(0, m.lineText.length)
        val end = m.matchEnd.coerceIn(start, m.lineText.length)
        val kind = TodoKind.entries.firstOrNull { it.label == m.lineText.substring(start, end) }
            ?: return@mapNotNull null
        if (!isTodoComment(m.lineText, start)) return@mapNotNull null
        if (!seen.add("${m.filePath}:${m.line}")) return@mapNotNull null
        TodoItem(kind, m.filePath, m.fileName, m.line, m.lineText, start, end, m.offset)
    }.sortedWith(compareBy({ it.filePath }, { it.line }))
}

/** Keep marker hits that sit in a comment: `//`, `/* */`, XML `<!-- -->`, KDoc `*`, `#`, `--`, `;` or `%`. */
private fun isTodoComment(line: String, markerStart: Int): Boolean {
    val before = line.substring(0, markerStart.coerceIn(0, line.length))
    if (before.contains("//") || before.contains("/*") || before.contains("<!--") ||
        before.contains("#") || before.contains("--") || before.contains(";") || before.contains("%")
    ) return true
    val trimmed = line.trimStart()
    return trimmed.startsWith("*") || TodoKind.entries.any { trimmed.startsWith(it.label) }
}
