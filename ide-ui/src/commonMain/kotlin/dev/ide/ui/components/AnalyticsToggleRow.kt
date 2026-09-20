package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.not_sharing_performance_data
import dev.ide.ui.generated.resources.performance_analytics
import dev.ide.ui.generated.resources.sharing_performance_data
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/** A compact reusable analytics on/off row for a settings surface (the editor's More menu). */
@Composable
fun AnalyticsToggleRow(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(
                remember { MutableInteractionSource() },
                indication = null
            ) { onChange(!enabled) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(CaIcons.info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.performance_analytics),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                if (enabled) stringResource(Res.string.sharing_performance_data) else stringResource(
                    Res.string.not_sharing_performance_data
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ConsentToggle(enabled, onChange)
    }
}

/** A small on-brand pill toggle (matches the module-settings switch), local to the analytics row. */
@Composable
private fun ConsentToggle(on: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(width = 44.dp, height = 26.dp)
            .background(
                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(Ca.radius.pill)
            )
            .clickable(remember { MutableInteractionSource() }, indication = null) { onToggle(!on) }
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.size(20.dp)
                .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(Ca.radius.pill))
        )
    }
}