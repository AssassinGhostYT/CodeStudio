package dev.ide.android.Terminal

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Standalone full-screen Alpine shell — Termux-style: black canvas + extra-keys bar, launched as its
 * own Activity from the IDE toolbar's Terminal button.
 *
 * The engine is [TerminalEngine] (proot + Alpine rootfs, see its KDoc for why Termux's own bootstrap
 * can't exec on Android 11+). The in-IDE TerminalPanel tool window is deliberately NOT registered
 * (AndroidIde.kt), so EditorCenter routes the toolbar button here via MainActivity.onOpenTerminal.
 *
 * Manifest: `.Terminal.TerminalActivity`, launchMode=singleTask, Theme.CodeStudio.Termux, exported=false.
 * On re-entry the shell keeps running (the session lives in [TerminalEngine], not the view): we attach
 * the existing session if one is alive instead of starting a second copy.
 *
 * System insets: states/composition bars are respected (the screen draws edge-to-edge and both the
 * terminal and the keys bar are padded by the real status/navigation inset), so the phone's gesture
 * bar never overlaps the keys.
 *
 * Copying: long-press is intercepted (no Termux-style text-selection overlays / mouse pointers); a
 * long-press grabs the visible screen text to the clipboard instead.
 *
 * Storage is requested exactly once (first launch): READ_EXTERNAL_STORAGE runtime prompt on ≤11,
 * one-tap jump to "All files access" settings on 12+ — never nagged again.
 */
class TerminalActivity : Activity() {

    private val scope = MainScope()
    private var terminalView: TerminalView? = null
    private var statusText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        TerminalEngine.init(applicationContext)
        requestStorageOnce()
        buildUi()
        startOrAttachShell()
    }

    override fun onDestroy() {
        scope.cancel()
        terminalView = null
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        // Edge-to-edge: content draws under the status/nav bars and we pad by the real insets, so
        // nothing (keys bar included) hides behind the phone's gesture/navigation bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val terminalArea = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val tv = TerminalView(this, null).apply {
            setTextSize(20)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.BLACK)
            setTerminalViewClient(createClient(this))
        }
        terminalView = tv
        terminalArea.addView(
            tv,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        val st = TextView(this).apply {
            text = "Waiting for shell…"
            setTextColor(Color.rgb(139, 148, 158))
            typeface = Typeface.MONOSPACE
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        statusText = st
        terminalArea.addView(
            st,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        // Terminal fills the area above the keys; both are pure black so they read as ONE block
        // (scrollback isn't hidden behind the keys, unlike an overlay).
        root.addView(
            terminalArea,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(buildKeysBar())
        setContentView(root)
    }

    /** The extra-keys bar: one single black block below the terminal (Termux-style), all keys
     *  together with thin separators — no separate gray card, no gap, reads as part of the shell. */
    private fun buildKeysBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        addKeyRow(bar,
            "ESC" to "\u001B", "/" to "/", "-" to "-", "HOME" to "\u001B[H", "↑" to "\u001B[A",
            "END" to "\u001B[F", "PGUP" to "\u001B[5~",
        )
        addKeyRow(bar,
            "TAB" to "\t", "CTRL" to MOD_CTRL, "ALT" to MOD_ALT, "←" to "\u001B[D", "↓" to "\u001B[B",
            "→" to "\u001B[C", "PGDN" to "\u001B[6~",
        )
        return bar
    }

    private fun addKeyRow(bar: LinearLayout, vararg keys: Pair<String, String>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        for ((label, command) in keys) {
            val button = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(220, 220, 220))
                typeface = Typeface.MONOSPACE
                textSize = 16f
                setPadding(0, 12, 0, 12)
                isClickable = true
                // Never steal focus from the terminal; plain click writes the escape/control sequence.
                isFocusable = false
                setOnClickListener { applyKey(command) }
            }
            button.setBackgroundColor(Color.rgb(28, 28, 30))
            button.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
            row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = if (command != keys.last().second) 1 else 0
            })
        }
        bar.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    // Sticky CTRL/ALT modifiers (Termux-style): tap toggles the modifier; the NEXT key is the one
    // modified (Ctrl+A = 0x01, Alt+X = ESC X). They auto-release after 2 s if untouched so they
    // never get stuck.
    private var ctrlHeld = false
    private var altHeld = false
    private val releaseModifiers = Runnable { ctrlHeld = false; altHeld = false }

    private fun applyKey(command: String) {
        when (command) {
            MOD_CTRL -> {
                ctrlHeld = !ctrlHeld
                if (ctrlHeld) scheduleModifierReset()
            }
            MOD_ALT -> {
                altHeld = !altHeld
                if (altHeld) scheduleModifierReset()
            }
            else -> {
                val useCtrl = ctrlHeld
                val useAlt = altHeld
                ctrlHeld = false
                altHeld = false
                handler.removeCallbacks(releaseModifiers)
                if (useCtrl && command.length == 1) {
                    controlCode(command[0].code)?.let { code ->
                        TerminalEngine.writeRaw(Char(code).toString())
                        return
                    }
                }
                // Meta = ESC prefix (the one escape sequence every line editor understands) — the
                // ALT key sends nothing of its own, it modifies the following character/token.
                TerminalEngine.writeRaw(if (useAlt) "\u001B$command" else command)
            }
        }
    }

    private fun scheduleModifierReset() {
        handler.removeCallbacks(releaseModifiers)
        handler.postDelayed(releaseModifiers, 2_000)
    }

    private fun controlCode(cp: Int): Int? = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        '@'.code -> 0
        '['.code -> 27
        '\\'.code -> 28
        ']'.code -> 29
        '^'.code -> 30
        '_'.code -> 31
        else -> null
    }

    private fun createClient(tv: TerminalView) = object : TerminalViewClient {
        override fun onScale(s: Float) = s
        override fun onSingleTapUp(e: MotionEvent) {
            tv.requestFocus()
            (tv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(tv, 0)
        }

        override fun shouldBackButtonBeMappedToEscape() = true
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(b: Boolean) {}
        override fun onKeyDown(k: Int, e: KeyEvent, s: TerminalSession) = false
        override fun onKeyUp(k: Int, e: KeyEvent) = false
        // Long-press = Termux-style text selection: return false so TerminalView starts the
        // selection mode (handles + floating Copy/Paste toolbar). Default client behavior.
        override fun onLongPress(e: MotionEvent) = false
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false

        override fun onCodePoint(cp: Int, ctrl: Boolean, s: TerminalSession): Boolean {
            // Shell died (or is awaiting manual restart): revive it on the first keystroke so the
            // user is never stuck on a dead "[Process completed]" buffer.
            if (TerminalEngine.setup.value is TerminalSetupState.Ready && TerminalEngine.session == null) {
                restartShell(manual = true)
            }
            val target = TerminalEngine.session ?: return false
            target.writeCodePoint(false, cp)
            return true
        }

        override fun onEmulatorSet() {}
        override fun logError(t: String, m: String) { Log.e("TerminalActivity", m) }
        override fun logWarn(t: String, m: String) { Log.w("TerminalActivity", m) }
        override fun logInfo(t: String, m: String) { Log.i("TerminalActivity", m) }
        override fun logDebug(t: String, m: String) { Log.d("TerminalActivity", m) }
        override fun logVerbose(t: String, m: String) { Log.v("TerminalActivity", m) }
        override fun logStackTraceWithMessage(t: String, m: String, e: Exception) { Log.e(t, m, e) }
        override fun logStackTrace(t: String, e: Exception) { Log.e(t, "", e) }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var startAttempted = false
    private var lastShellStartMs = 0L
    private var crashStreak = 0
    private var awaitingManualRestart = false
    private var lastCols = 80
    private var lastRows = 24

    private fun startOrAttachShell() {
        val existing = TerminalEngine.session
        if (existing != null && TerminalEngine.running.value) {
            statusText?.visibility = View.GONE
            attachView(existing)
            return
        }
        scope.launch {
            startAttempted = true
            TerminalEngine.ensureReady { msg -> showStatus(msg) }
            when (val s = TerminalEngine.setup.value) {
                is TerminalSetupState.Ready -> {
                    showStatus("Starting shell…")
                    TerminalEngine.startSession(lastCols, lastRows)
                    val session = TerminalEngine.session
                    if (session != null) {
                        lastShellStartMs = SystemClock.uptimeMillis()
                        crashStreak = 0
                        attachView(session)
                        // Hide overlay NOW — terminal buffer is visible via the view.
                        runOnUiThread { statusText?.visibility = View.GONE }
                        watchForShellDeath(session)
                    }
                }
                is TerminalSetupState.Failed -> showStatus("Setup fallido: ${s.message}")
                else -> showStatus("Preparando terminal…")
            }
        }
        // A shell exit brings the prompt back automatically (no "[Process completed]" dead end):
        // restart unless it dies repeatedly within 3 s (broken rootfs → manual restart instead).
        scope.launch {
            TerminalEngine.running.collect { runningNow ->
                if (!runningNow && startAttempted &&
                    TerminalEngine.setup.value is TerminalSetupState.Ready
                ) {
                    restartShell(manual = false)
                }
            }
        }
    }

    /** Restart the shell (fresh prompt). Semi-automatic: guards against crash-loops, and the first
     *  keystroke after a dead shell revives it manually ([manual]=true skips the loop protection). */
    private fun restartShell(manual: Boolean) {
        val engine = TerminalEngine
        if (engine.setup.value !is TerminalSetupState.Ready) return
        val now = SystemClock.uptimeMillis()
        if (!manual) {
            if (now - lastShellStartMs < 3_000) crashStreak++ else crashStreak = 0
            if (crashStreak >= 3) {
                awaitingManualRestart = true
                handler.removeCallbacksAndMessages(null)
                showStatus("El shell terminó 3 veces seguidas. Tocá la terminal para reiniciar.")
                runOnUiThread {
                    Toast.makeText(this, "Shell caído — tocá la terminal para reiniciar", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        awaitingManualRestart = false
        crashStreak = 0
        lastShellStartMs = now
        handler.removeCallbacks(releaseModifiers)
        TerminalEngine.startSession(lastCols, lastRows)
        val ns = TerminalEngine.session
        if (ns == null) {
            showStatus("No se pudo iniciar el shell")
            return
        }
        runOnUiThread {
            attachView(ns)
            statusText?.visibility = View.GONE
        }
        watchForShellDeath(ns)
    }

    /** 3 s after launch, log the shell buffer + whether it's alive (diagnostic path we've used to
     *  chase proot/init failures — the user's "Process completed (code 127)" builds). */
    private fun watchForShellDeath(session: TerminalSession) {
        handler.postDelayed({
            val alive = TerminalEngine.running.value
            val emu = session.getEmulator()
            val buf = emu?.getScreen()?.getSelectedText(
                0, 0, emu.mColumns, emu.getScreen().getActiveRows(), true,
            ) ?: ""
            Log.i(TAG, "shell-alive=$alive bufLen=${buf.length}")
            if (buf.isNotBlank()) Log.i(TAG, "shell-buffer:\n$buf")
            if (!alive) {
                runOnUiThread {
                    Toast.makeText(
                        this@TerminalActivity,
                        "Shell exited — check logcat -s TerminalActivity:TerminalEngine",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }, 3_000)
    }

    private fun attachView(session: TerminalSession) {
        val tv = terminalView ?: run {
            Log.w(TAG, "attachView before buildUi"); return
        }
        val current = tv.getCurrentSession()
        if (current == null || current !== session) tv.attachSession(session)
        tv.post { tv.requestFocus() }
    }

    // TerminalEngine.ensureReady's onProgress and the setup state land on background threads; route
    // every status update through runOnUiThread.
    private fun showStatus(text: String) {
        runOnUiThread { statusText?.let { it.text = text; it.visibility = View.VISIBLE } }
    }

    /** Storage once, never again (sharedPrefs gate) — replaces the old every-entry panel banner. */
    private fun requestStorageOnce() {
        val prefs = getSharedPreferences("terminal", Context.MODE_PRIVATE)
        if (prefs.getBoolean("storage_asked", false)) return
        prefs.edit().putBoolean("storage_asked", true).apply()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
            }
        } else if (!Environment.isExternalStorageManager()) {
            // One-tap trip to "All files access" the first time only.
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        }
    }

    companion object {
        private const val TAG = "TerminalActivity"
        private const val REQ_STORAGE = 42
        private const val MOD_CTRL = "\u0000mod-ctrl"
        private const val MOD_ALT = "\u0000mod-alt"
    }
}