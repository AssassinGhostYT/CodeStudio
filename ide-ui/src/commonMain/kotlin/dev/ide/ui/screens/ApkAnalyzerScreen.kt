package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.ApkAnalyzerEntry
import dev.ide.ui.backend.ApkAnalyzerResult
import dev.ide.ui.backend.ApkAnalyzerSummary
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.apk_analyzer_category
import dev.ide.ui.generated.resources.apk_analyzer_empty
import dev.ide.ui.generated.resources.apk_analyzer_methods
import dev.ide.ui.generated.resources.apk_analyzer_summary
import dev.ide.ui.generated.resources.apk_analyzer_title
import dev.ide.ui.generated.resources.back
import org.jetbrains.compose.resources.stringResource

/**
 * APK Analyzer — read-only size breakdown by category (dex, resources, assets, res, manifest, native, other).
 * Opens from the file tree context menu on an .apk file.
 */
@Composable
fun ApkAnalyzerScreen(
    backend: IdeBackend,
    apkPath: String,
    onBack: () -> Unit,
) {
    val result = remember(apkPath, backend) { mutableStateOf<ApkAnalyzerResult?>(null) }
    val loading = remember(apkPath, backend) { mutableStateOf(true) }

    LaunchedEffect(apkPath, backend) {
        loading.value = true
        val r = backend.apkAnalyzer.analyze(apkPath)
        result.value = r
        loading.value = false
    }

    val fileName = apkPath.substringAfterLast('/').substringAfterLast('\\')

    if (loading.value) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(Res.string.apk_analyzer_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val r = result.value ?: return
    val summary = r.summary

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        TextButton(onClick = onBack) { Text("← ${stringResource(Res.string.back)}") }
        Text(
            "${stringResource(Res.string.apk_analyzer_title)} — $fileName",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            SummaryChip(
                label = stringResource(Res.string.apk_analyzer_summary),
                value = "${formatBytes(summary.totalCompressed)} / ${formatBytes(summary.totalUncompressed)}",
            )
            SummaryChip(
                label = stringResource(Res.string.apk_analyzer_category, "entradas"),
                value = "${r.entries.size}",
            )
        }

        if (summary.byCategory.isNotEmpty()) {
            Text(
                stringResource(Res.string.apk_analyzer_category, "Por categoría"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(summary.byCategory.entries.sortedByDescending { it.value.uncompressed }) { cat ->
                    CategoryRow(
                        category = cat.key,
                        total = cat.value,
                        grandTotal = summary.totalUncompressed,
                    )
                }
            }
        }

        if (r.entries.isNotEmpty()) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            Text(
                "Archivos (${r.entries.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(r.entries) { entry ->
                    EntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(label: String, value: String) {
    Box(
        Modifier
            .fillMaxWidth(0.5f)
            .padding(end = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CategoryRow(category: String, total: ApkAnalyzerSummary.CategoryTotal, grandTotal: Long) {
    val pct = if (grandTotal > 0) (total.uncompressed * 100.0 / grandTotal) else 0.0
    val color = categoryColor(category)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Box(Modifier.size(12.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(categoryDisplayName(category), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "${total.count} archivos · ${formatBytes(total.uncompressed)} (${formatBytes(total.compressed)} comprimido)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("${pct.toString().take(4)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntryRow(entry: ApkAnalyzerEntry) {
    val color = categoryColor(entry.category)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Box(Modifier.size(10.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                entry.path,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatBytes(entry.uncompressedSize)} → ${formatBytes(entry.compressedSize)} (${(entry.ratio * 100).toString().take(4)}%)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.methodCount?.let {
                Text("${stringResource(Res.string.apk_analyzer_methods, it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun categoryColor(name: String): Color = when (name) {
    "dex" -> Color(0xFF64B5F6)
    "resources" -> Color(0xFF81C784)
    "res" -> Color(0xFFE57373)
    "assets" -> Color(0xFFFFB74D)
    "manifest" -> Color(0xFFBA68C8)
    "native" -> Color(0xFF4DB6AC)
    else -> Color(0xFFB0BEC5)
}

private fun categoryDisplayName(name: String): String = when (name) {
    "dex" -> "DEX (código)"
    "resources" -> "resources.arsc"
    "res" -> "Recursos (res/)"
    "assets" -> "Assets"
    "manifest" -> "Manifest / META-INF"
    "native" -> "Librerías nativas (lib/)"
    else -> "Otros"
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "${(bytes / 1_073_741_824.0).toString().take(4)} GB"
        bytes >= 1_048_576 -> "${(bytes / 1_048_576.0).toString().take(4)} MB"
        bytes >= 1_024 -> "${(bytes / 1024.0).toString().take(4)} KB"
        else -> "$bytes B"
    }
}
