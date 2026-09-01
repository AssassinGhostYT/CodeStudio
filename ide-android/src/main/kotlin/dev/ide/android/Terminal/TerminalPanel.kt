package dev.ide.android.Terminal

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.ComponentActivity
import androidx.activity.view.WindowInsetsOptimized
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FillProgress
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.Unit
import androidx.compose.ui.ViewConfiguration
import androidx.compose.ui.accessibility.AccessibilityManager
import androidx.compose.ui.draw.semantics.semantics
import androidx.compose.ui.focusModifiers
import androidx.compose.ui.focusModifiers.focusRequester
import androidx.compose.ui.focusModifiers.isFocused
import androidx.compose.ui.focusModifiers.isWithin
import androidx.compose.ui.focusModifiers.noFocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.WindowInsets
import androidx.compose.ui.platform.getRootContext
import androidx.compose.ui.readconfig
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.view.TerminalView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalView
import com.termux.terminal.TerminalViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.Horizontal
import androidx.compose.foundation.layout.Arrangement.Vertical
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.Unit
import androidx.compose.ui.ViewConfiguration
import androidx.compose.ui.accessibility.AccessibilityManager
import androidx.compose.ui.draw.semantics.semantics
import androidx.compose.ui.focusModifiers
import androidx.compose.ui.focusModifiers.focusRequester
import androidx.compose.ui.focusModifiers.isFocused
import androidx.compose.ui.focusModifiers.isWithin
import androidx.compose.ui.focusModifiers.noFocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.WindowInsets
import androidx.compose.ui.platform.getRootContext
import androidx.compose.ui.readconfig
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.run.also
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.Horizontal
import androidx.compose.foundation.layout.Arrangement.Vertical
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.Unit
import androidx.compose.ui.ViewConfiguration
import androidx.compose.ui.accessibility.AccessibilityManager
import androidx.compose.ui.draw.semantics.semantics
import androidx.compose.ui.focusModifiers
import androidx.compose.ui.focusModifiers.focusRequester
import androidx.compose.ui.focusModifiers.isFocused
import androidx.compose.ui.focusModifiers.isWithin
import androidx.compose.ui.focusModifiers.noFocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.WindowInsets
import androidx.compose.ui.platform.getRootContext
import androidx.compose.ui.readconfig
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Terminal Panel using the real Termux TerminalView + TerminalSession
 */
@Composable
internal fun TerminalPanel() {
    val engine = TerminalEngine
    val setup by engine.setup.collectAsState()
    val output by engine.output.collectAsState()
    val running by engine.running.collectAsState()
    val scope = rememberCoroutineScope()
    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            engine.ensureReady()
            if (engine.setup.value is TerminalEngine.SetupState.Ready) {
                engine.startSession()
            }
        }
    }

    // Update terminal size when view is ready
    LaunchedEffect(running, terminalViewRef.value) {
        if (running && terminalViewRef.value != null) {
            // TerminalView will call updateSize internally via attachSession
        }
    }

    Surface(color = 0xFF0D1117.toInt(), modifier = Modifier.fillMaxSize()) {
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
                TerminalViewWrapper(
                    engine = engine,
                    modifier = Modifier.weight(1f).fillMaxSize()
                )
                SpecialKeysBar(
                    onKeyPress = { key -> engine.writeCommand(key) },
                    enabled = running
                )
            }
        }
    }
}

@Composable
private fun TerminalViewWrapper(
    engine: TerminalEngine,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = TerminalView(ctx, null)
            view.setTerminalViewClient(TerminalViewClientImpl(engine))
            terminalViewRef.value = view
            view
        },
        update = { view ->
            // Attach session when engine has one and view isn't attached
            val session = engine.session
            if (session != null && view.mTermSession != session) {
                val attached = view.attachSession(session)
                if (attached) {
                    // TerminalView will handle sizing and rendering
                }
            }
        }
    )
}

private class TerminalViewClientImpl(
    private val engine: TerminalEngine
) : TerminalViewClient {

    override fun onScale(scale: Float): Float = scale

    override fun onSingleTapUp(e: MotionEvent) {
        // Show keyboard on tap
        engine.session?.write(("\u001B[6n").toByteArray()) // DSR for cursor position
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = true

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        // Let TerminalView handle standard keys, intercept special ones
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> session.writeCodePoint(false, 0x1B5B41) // ESC[A
            KeyEvent.KEYCODE_DPAD_DOWN -> session.writeCodePoint(false, 0x1B5B42) // ESC[B
            KeyEvent.KEYCODE_DPAD_LEFT -> session.writeCodePoint(false, 0x1B5B44) // ESC[D
            KeyEvent.KEYCODE_DPAD_RIGHT -> session.writeCodePoint(false, 0x1B5B43) // ESC[C
            KeyEvent.KEYCODE_ENTER -> session.writeCodePoint(false, '\n'.toInt())
            KeyEvent.KEYCODE_DEL -> session.writeCodePoint(false, 0x7F) // DEL
            KeyEvent.KEYCODE_TAB -> session.writeCodePoint(false, '\t'.toInt())
            KeyEvent.KEYCODE_ESCAPE -> session.writeCodePoint(false, 0x1B)
            else -> return false
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (ctrlDown) {
            // Handle Ctrl+key combinations
            when (codePoint) {
                'c'.toInt() -> session.writeCodePoint(false, 0x03) // Ctrl+C
                'd'.toInt() -> session.writeCodePoint(false, 0x04) // Ctrl+D
                'l'.toInt() -> session.writeCodePoint(false, 0x0C) // Ctrl+L
                else -> return false
            }
        } else {
            session.writeCodePoint(false, codePoint)
        }
        return true
    }

    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) { Log.e("TerminalView", message) }
    override fun logWarn(tag: String, message: String) { Log.w("TerminalView", message) }
    override fun logInfo(tag: String, message: String) { Log.i("TerminalView", message) }
    override fun logDebug(tag: String, message: String) { Log.d("TerminalView", message) }
    override fun logVerbose(tag: String, message: String) { Log.v("TerminalView", message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "", e) }
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
private fun SpecialKeysBar(onKeyPress: (String) -> Unit, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var ctrlPressed by remember { mutableStateOf(false) }
    var altPressed by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        SpecialKeyButton("ESC", { onKeyPress("\u001B") }, enabled)
        SpecialKeyButton("/", { onKeyPress("/") }, enabled)
        SpecialKeyButton("-", { onKeyPress("-") }, enabled)
        SpecialKeyButton("HOME", { onKeyPress("\u001B[H") }, enabled)
        SpecialKeyButton("END", { onKeyPress("\u001B[F") }, enabled)
        SpecialKeyButton("PGUP", { onKeyPress("\u001B[5~") }, enabled)
        SpecialKeyButton("PGDN", { onKeyPress("\u001B[6~") }, enabled)
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        SpecialKeyButton(
            "CTRL",
            {
                ctrlPressed = !ctrlPressed
                if (ctrlPressed) onKeyPress("\u001D")
            },
            enabled,
            active = ctrlPressed
        )
        SpecialKeyButton(
            "ALT",
            {
                altPressed = !altPressed
                if (altPressed) onKeyPress("\u001B")
            },
            enabled,
            active = altPressed
        )
        SpecialKeyButton("TAB", { onKeyPress("\t") }, enabled)
        SpecialKeyButton("↑", { onKeyPress("\u001B[A") }, enabled)
        SpecialKeyButton("↓", { onKeyPress("\u001B[B") }, enabled)
        SpecialKeyButton("←", { onKeyPress("\u001B[D") }, enabled)
        SpecialKeyButton("→", { onKeyPress("\u001B[C") }, enabled)
    }
}

@Composable
private fun SpecialKeyButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    active: Boolean = false
) {
    Surface(
        color = if (active) 0xFF1F6FEB.toInt() else 0xFF21262D.toInt(),
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier = Modifier.size(40.dp),
        onClick = onClick,
        enabled = enabled
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (enabled) 0xFFE6EDF3.toInt() else 0xFF6E7681.toInt(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}