package dev.ide.android.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import dev.ide.ui.icons.CaIcons

/**
 * The terminal body shown inside the BOTTOM console tab. Shows setup/download progress until the
 * rootfs is ready, then a live monospace readout with a single input line at the bottom. Setup is
 * lazy: it kicks off the moment the tab is opened.
 */
@Composable
internal fun TerminalPanel() {
    val engine = TerminalEngine
    val setup by engine.setup.collectAsState()
    val output by engine.output.collectAsState()
    val running by engine.running.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            engine.ensureReady()
            if (engine.setup.value is TerminalEngine.SetupState.Ready) {
                engine.startSession()
            }
        }
    }

    Surface(color = Color(0xFF0D1117), modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (val s = setup) {
                TerminalEngine.SetupState.Idle -> StatusLine("Initializing…")
                is TerminalEngine.SetupState.Downloading -> StatusLine(s.label)
                TerminalEngine.SetupState.Extracting -> StatusLine("Extracting rootfs…")
                is TerminalEngine.SetupState.Failed -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StatusLine(s.message, error = true)
                        IconButton(onClick = { 
                            scope.launch { engine.ensureReady() }
                        }) {
                            Icon(CaIcons.refresh, "Retry", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                TerminalEngine.SetupState.Ready -> Unit
            }
            if (setup is TerminalEngine.SetupState.Ready) {
                Output(readout = output, running = running)
                SpecialKeysBar(onKeyPress = { key -> engine.writeCommand(key) }, enabled = running)
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, error: Boolean = false) {
    Text(
        text,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun ColumnScope.Output(readout: String, running: Boolean) {
    val scroll = rememberScrollState()
    Box(Modifier.weight(1f).fillMaxWidth()) {
        SelectionContainer {
            Text(
                if (readout.isEmpty()) if (running) "Waiting for shell…" else "No session yet — tap the input below to start." else readout,
                color = Color(0xFFE6EDF3),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxSize().verticalScroll(scroll),
            )
        }
        LaunchedEffect(readout.length) { scroll.scrollTo(scroll.maxValue) }
    }
}

@Composable
private fun SpecialKeysBar(onKeyPress: (String) -> Unit, enabled: Boolean) {
    var ctrlPressed by remember { mutableStateOf(false) }
    var altPressed by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF161B22), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.weight(1f)) { ExtraKey("ESC", { onKeyPress("\u001B") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("/", { onKeyPress("/") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("-", { onKeyPress("-") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("HOME", { onKeyPress("\u001B[H") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("↑", { onKeyPress("\u001B[A") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("END", { onKeyPress("\u001B[F") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("PGUP", { onKeyPress("\u001B[5~") }, enabled) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.weight(1f)) { ExtraKey("TAB", { onKeyPress("\t") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("CTRL", { ctrlPressed = !ctrlPressed; if (ctrlPressed) onKeyPress("\u001D") }, enabled, active = ctrlPressed) }
            Box(Modifier.weight(1f)) { ExtraKey("ALT", { altPressed = !altPressed; if (altPressed) onKeyPress("\u001B") }, enabled, active = altPressed) }
            Box(Modifier.weight(1f)) { ExtraKey("←", { onKeyPress("\u001B[D") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("↓", { onKeyPress("\u001B[B") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("→", { onKeyPress("\u001B[C") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("PGDN", { onKeyPress("\u001B[6~") }, enabled) }
        }
    }
}

@Composable
private fun ExtraKey(label: String, onClick: () -> Unit, enabled: Boolean, active: Boolean = false) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) Color(0xFF1F6FEB) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(label, color = if (!enabled) Color(0xFF6E7681) else if (active) Color.White else Color(0xFFE6EDF3), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
