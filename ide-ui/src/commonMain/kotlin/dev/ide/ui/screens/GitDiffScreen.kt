package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.GitCommitInfo
import dev.ide.ui.backend.GitService
import dev.ide.ui.platform.ioDispatcher
import kotlinx.coroutines.withContext

@Composable
fun GitDiffScreen(
    git: GitService,
    path: String,
    onBack: () -> Unit,
) {
    var diffText by remember(path) { mutableStateOf("") }
    var lastCommit by remember { mutableStateOf<GitCommitInfo?>(null) }
    var loading by remember(path) { mutableStateOf(true) }

    LaunchedEffect(path) {
        loading = true
        withContext(ioDispatcher) {
            lastCommit = runCatching { git.snapshot().lastCommit }.getOrNull()
            diffText = runCatching { git.diff(path, false) }.getOrDefault("")
        }
        loading = false
    }

    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Volver") }
            Spacer(Modifier.weight(1f))
            Text("Diff vs HEAD (local)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (lastCommit != null) {
                    Text(
                        "${lastCommit!!.shortHash} • ${lastCommit!!.author} • ${lastCommit!!.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        } else if (diffText.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Sin diferencias con HEAD", style = MaterialTheme.typography.titleSmall)
            }
        } else {
            val scroll = rememberScrollState()
            val hScroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp)
                    .horizontalScroll(hScroll)
                    .verticalScroll(scroll)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .border(androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
                    .padding(8.dp),
            ) {
                diffText.lineSequence().forEach { line ->
                    val isAdd = line.startsWith("+") && !line.startsWith("+++")
                    val isRem = line.startsWith("-") && !line.startsWith("---")
                    val color = when {
                        isAdd -> Color(0xFFB9F6CA)
                        isRem -> Color(0xFFFF8A80)
                        line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    val bg = when {
                        isAdd -> Color(0x332E7D32)
                        isRem -> Color(0x33FF5252)
                        else -> Color.Transparent
                    }
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
        }
    }
}
