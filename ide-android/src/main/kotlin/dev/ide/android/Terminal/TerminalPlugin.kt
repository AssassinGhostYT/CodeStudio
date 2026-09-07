package dev.ide.android.Terminal

import android.content.Context
import dev.ide.ui.ext.TERMINAL_TOOL_WINDOW_ID
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContext
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.ToolWindowRegistry

/**
 * The public face of the Terminal feature on Android: wires [TerminalEngine] to the app and contributes
 * the terminal as a BOTTOM tool window (a console tab next to Pasos/Steps). Idempotent — call once per
 * process from the Android host ([dev.ide.android.AndroidIde] bootstrap); a later call is a no-op.
 */
object TerminalPlugin {

    private val iconId = "terminal"
    private val registered = java.util.concurrent.atomic.AtomicBoolean(false)

    fun install(context: Context) {
        TerminalEngine.init(context)
        if (registered.compareAndSet(false, true)) {
            ToolWindowRegistry.register(contribution())
        }
    }

    fun contribution(): ToolWindowContribution = ToolWindowContribution(
        id = TERMINAL_TOOL_WINDOW_ID,
        title = "Terminal",
        iconId = iconId,
        anchor = ToolWindowAnchor.RIGHT,
        order = 100,
        content = { _: ToolWindowContext -> TerminalPanel() },
    )
}