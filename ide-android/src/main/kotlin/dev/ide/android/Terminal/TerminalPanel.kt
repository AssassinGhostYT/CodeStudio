package dev.ide.android.Terminal

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.ide.ui.icons.CaIcons
import kotlinx.coroutines.launch

@Composable
internal fun TerminalPanel() {
    val engine = TerminalEngine
    val setup by engine.setup.collectAsState()
    val running by engine.running.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            engine.ensureReady()
            if (engine.setup.value is TerminalEngine.SetupState.Ready) engine.startSession()
        }
    }

    androidx.compose.material3.Surface(color = Color(0xFF0D1117), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = setup) {
                TerminalEngine.SetupState.Idle -> StatusLine("Initializing…")
                is TerminalEngine.SetupState.Downloading -> StatusLine(s.label)
                TerminalEngine.SetupState.Extracting -> StatusLine("Extracting rootfs…")
                is TerminalEngine.SetupState.Failed -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StatusLine(s.message, error = true)
                        IconButton(onClick = { scope.launch { engine.ensureReady() } }) {
                            Icon(CaIcons.refresh, "Retry", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                TerminalEngine.SetupState.Ready -> Unit
            }
            if (setup is TerminalEngine.SetupState.Ready) {
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black, RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))) {
                    if (running && engine.session != null) {
                        TermView(engine.session!!)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Waiting for shell…", color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                    }
                }
                SpecialKeysBar(onKeyPress = { k -> engine.writeCommand(k) }, enabled = running)
            }
        }
    }
}

@Composable
private fun TermView(session: TerminalSession) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            val tv = TerminalView(c, null)
            // Initialise the TerminalRenderer before Compose measures the view. mRenderer is created
            // lazily by setTextSize/setTypeface, so without this Compose's first onSizeChanged fires
            // with mRenderer == null and updateSize() throws NPE reading mRenderer.mFontWidth.
            tv.setTextSize(14)
            tv.isFocusable = true
            tv.isFocusableInTouchMode = true
            tv.setTerminalViewClient(object : TerminalViewClient {
                override fun onScale(s: Float) = s
                override fun onSingleTapUp(e: MotionEvent) { tv.requestFocus(); (tv.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.showSoftInput(tv, 0) }
                    override fun shouldBackButtonBeMappedToEscape() = true
                    override fun shouldEnforceCharBasedInput() = false
                    override fun shouldUseCtrlSpaceWorkaround() = false
                    override fun isTerminalViewSelected() = true
                    override fun copyModeChanged(b: Boolean) {}
                    override fun onKeyDown(k: Int, e: KeyEvent, s: TerminalSession) = false
                    override fun onKeyUp(k: Int, e: KeyEvent) = false
                    override fun onLongPress(e: MotionEvent) = false
                    override fun readControlKey() = false
                    override fun readAltKey() = false
                    override fun readShiftKey() = false
                    override fun readFnKey() = false
                    override fun onCodePoint(cp: Int, ctrl: Boolean, s: TerminalSession): Boolean { s.writeCodePoint(false, cp); return true }
                    override fun onEmulatorSet() {}
                    override fun logError(t: String, m: String) { android.util.Log.e("TermView", m) }
                    override fun logWarn(t: String, m: String) { android.util.Log.w("TermView", m) }
                    override fun logInfo(t: String, m: String) { android.util.Log.i("TermView", m) }
                    override fun logDebug(t: String, m: String) { android.util.Log.d("TermView", m) }
                    override fun logVerbose(t: String, m: String) { android.util.Log.v("TermView", m) }
                    override fun logStackTraceWithMessage(t: String, m: String, e: Exception) { android.util.Log.e(t, m, e) }
                    override fun logStackTrace(t: String, e: Exception) { android.util.Log.e(t, "", e) }
                })
            tv.attachSession(session)
            tv.post { tv.requestFocus() }
            tv
        },
        update = { v ->
            if (v.mTermSession !== session) v.attachSession(session)
            v.requestFocus()
        }
    )
}

@Composable
private fun StatusLine(text: String, error: Boolean = false) {
    Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun SpecialKeysBar(onKeyPress: (String) -> Unit, enabled: Boolean) {
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Color(0xFF161B22), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            Box(Modifier.weight(1f)) { ExtraKey("CTRL", { ctrl = !ctrl; if (ctrl) onKeyPress("\u001D") }, enabled, active = ctrl) }
            Box(Modifier.weight(1f)) { ExtraKey("ALT", { alt = !alt; if (alt) onKeyPress("\u001B") }, enabled, active = alt) }
            Box(Modifier.weight(1f)) { ExtraKey("←", { onKeyPress("\u001B[D") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("↓", { onKeyPress("\u001B[B") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("→", { onKeyPress("\u001B[C") }, enabled) }
            Box(Modifier.weight(1f)) { ExtraKey("PGDN", { onKeyPress("\u001B[6~") }, enabled) }
        }
    }
}

@Composable
private fun ExtraKey(label: String, onClick: () -> Unit, enabled: Boolean, active: Boolean = false) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(if (active) Color(0xFF1F6FEB) else Color.Transparent).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp)) {
        Text(label, color = if (!enabled) Color(0xFF6E7681) else if (active) Color.White else Color(0xFFE6EDF3), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
