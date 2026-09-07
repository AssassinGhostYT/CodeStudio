package dev.ide.android.Terminal

import android.content.Context
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.StateFlow

/** Shared setup lifecycle for the in-IDE terminal engines. Kept outside the engines so the panel can
 *  render the same status UI regardless of which engine is active. */
sealed interface TerminalSetupState {
    data object Idle : TerminalSetupState
    data class Downloading(val label: String) : TerminalSetupState
    data object Extracting : TerminalSetupState
    data object Ready : TerminalSetupState
    data class Failed(val message: String) : TerminalSetupState
}

/** Common surface both terminal engines expose. The panel talks to this interface so switching
 *  engine implementations is a runtime choice, not a code fork. Currently only [TerminalEngine] is
 *  wired, but the interface keeps that decision reversible. */
interface TerminalRuntime : com.termux.terminal.TerminalSessionClient {
    val setup: StateFlow<TerminalSetupState>
    val running: StateFlow<Boolean>
    /** The active session, driven by whichever engine is selected. Intentionally declared as a
     *  plain public `var` (no restricted setter): Kotlin disallows both `private set` on an
     *  interface property AND implementations narrowing the setter's visibility, so the setter
     *  stays public at the interface level — harmless since both engines are internal objects. */
    var session: TerminalSession?
    fun init(context: Context)
    suspend fun ensureReady(onProgress: (String) -> Unit = {})
    fun startSession(cols: Int = 80, rows: Int = 24)
    fun writeCommand(line: String)
    fun stopSession()
}