package dev.ide.android.Terminal

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide

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
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            engine.ensureReady()
            if (engine.setup.value is TerminalEngine.SetupState.Ready) engine.startSession()
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
                is TerminalEngine.SetupState.Failed -> StatusLine(s.message, error = true)
                TerminalEngine.SetupState.Ready -> Unit
            }
            if (setup is TerminalEngine.SetupState.Ready) {
                Output(readout = output, running = running)
                InputLine(onSubmit = { line -> engine.writeCommand(line) }, enabled = running)
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
private fun Output(readout: String, running: Boolean) {
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
        // Keep the readout pinned to the tail as output streams in.
        LaunchedEffect(readout.length) { scroll.scrollTo(scroll.maxValue) }
    }
}

@Composable
private fun InputLine(onSubmit: (String) -> Unit, enabled: Boolean) {
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF161B22), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CaIcons.terminal, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = Color(0xFFE6EDF3), fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            cursorBrush = SolidColor(Color(0xFFE6EDF3)),
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) { inner ->
            Box {
                if (text.isEmpty()) Text("command…", color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                inner()
            }
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = {
            val line = text.trim()
            if (line.isNotEmpty()) { onSubmit(line); text = ""; keyboard?.hide() }
        }, enabled = enabled) {
            Icon(CaIcons.arrowRight, "run", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}