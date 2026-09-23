package dev.ide.android.Terminal

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone full-screen terminal — Termux-style: black canvas + extra-keys bar, launched as its
 * own Activity from the IDE toolbar's Terminal button.
 *
 * ## Engines (in priority order)
 *
 * 1. **[UbuntuRuntime]** — a real Ubuntu 22.04 arm64 rootfs (ubuntu-base, downloaded once at
 *    runtime to `$PREFIX/local/ubuntu`, ~27 MB) running under the app's bundled proot
 *    (libproot.so + libloader.so). Ubuntu's glibc ELFs resolve ld-linux-aarch64.so.1 from INSIDE the
 *    rootfs, so the ptrace-execve proot path works here (the historical Alpine failure was
 *    unresolved Termux deps, not ptrace). This is the target userland for the IDE tooling (opencode,
 *    gcc, node, python) because the official builds need glibc.
 * 2. **[TermuxRuntime]** — the bundled Termux userland extracted to `<filesDir>/usr` and run NATIVE
 *    via `/system/bin/linker64` + the `libtermux-exec` LD_PRELOAD shim. No ptrace, no proot: full
 *    bionic `apt`/`pkg`/`bash` at device speed. Faster safety net if Ubuntu cannot come up.
 * 3. **[TerminalEngine]** native mode — Android's own `/system/bin/sh` (mksh). Pure fallback,
 *    always works, but there is no package manager.
 *
 * The chosen engine is persisted ("terminal"/"runtime"), so later opens jump straight to the
 * working shell. The in-IDE TerminalPanel tool window is deliberately NOT registered
 * (AndroidIde.kt), so EditorCenter routes the toolbar button here via MainActivity.onOpenTerminal.
 *
 * Manifest: `.Terminal.TerminalActivity`, launchMode=singleTask, Theme.CodeStudio.Termux, exported=false.
 * On re-entry the shell keeps running (the session lives in the engine, not the view): we attach
 * the existing session if one is alive instead of starting a second copy.
 *
 * System insets: states/composition bars are respected (the screen draws edge-to-edge and both the
 * terminal and the keys bar are padded by the real status/navigation inset), so the phone's gesture
 * bar never overlaps the keys.
 *
 * Copying/pasting: Termux-style — long-press starts text selection (handles + floating Copy/Paste
 * toolbar), which copies to the clipboard; pasting from a phone clipboard happens through the
 * TerminalView's bracketed-paste path on paste action.
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
        requestTerminalNotificationPermission()
        startTerminalKeepAlive()
        TerminalEngine.init(applicationContext)
        TermuxRuntime.init(applicationContext)
        UbuntuRuntime.init(applicationContext)
        requestStorageOnce()
        buildUi()
        startOrAttachShell()
    }

    override fun onDestroy() {
        scope.cancel()
        terminalView = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Resumes may not fire onEmulatorSet again; start the cursor blinker explicitly so the
        // view keeps invalidating (rendering fresh output) even if onTextChanged is quiet.
        terminalView?.let { tv ->
            if (activeSession() != null) tv.setTerminalCursorBlinkerState(true, true)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        // Edge-to-edge: content draws under the status/nav bars and we pad by the real insets, so
        // nothing (keys bar included) hides behind the phone's gesture/navigation bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // With edge-to-edge the soft keyboard only arrives as an IME inset; pad the bottom by
            // whichever is bigger so the terminal + extra-keys bar sit ABOVE the keyboard and never
            // get covered by it.
            val bottom = maxOf(bars.bottom, ime.bottom)
            view.setPadding(0, bars.top, 0, bottom)
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
            when (label) {
                "CTRL" -> ctrlButton = button
                "ALT" -> altButton = button
            }
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
    // never get stuck. The active button is highlighted (blue) like Termux highlights extra keys.
    private var ctrlHeld = false
    private var altHeld = false
    private var ctrlButton: TextView? = null
    private var altButton: TextView? = null
    private val modDefaultBg = Color.rgb(28, 28, 30)
    private val modActiveBg = Color.rgb(58, 130, 191)
    private val modDefaultFg = Color.rgb(220, 220, 220)
    private val releaseModifiers = Runnable {
        ctrlHeld = false
        altHeld = false
        updateModifierVisuals()
    }

    private fun updateModifierVisuals() {
        ctrlButton?.let {
            it.setBackgroundColor(if (ctrlHeld) modActiveBg else modDefaultBg)
            it.setTextColor(if (ctrlHeld) Color.WHITE else modDefaultFg)
        }
        altButton?.let {
            it.setBackgroundColor(if (altHeld) modActiveBg else modDefaultBg)
            it.setTextColor(if (altHeld) Color.WHITE else modDefaultFg)
        }
    }

    private fun applyKey(command: String) {
        when (command) {
            MOD_CTRL -> {
                ctrlHeld = !ctrlHeld
                if (ctrlHeld) scheduleModifierReset() else handler.removeCallbacks(releaseModifiers)
                updateModifierVisuals()
            }
            MOD_ALT -> {
                altHeld = !altHeld
                if (altHeld) scheduleModifierReset() else handler.removeCallbacks(releaseModifiers)
                updateModifierVisuals()
            }
            MOD_COPY -> { copyToClipboard() }
            MOD_PASTE -> { pasteFromClipboard() }
            else -> {
                val useCtrl = ctrlHeld
                val useAlt = altHeld
                ctrlHeld = false
                altHeld = false
                handler.removeCallbacks(releaseModifiers)
                updateModifierVisuals()
                if (useCtrl && command.length == 1) {
                    controlCode(command[0].code)?.let { code ->
                        activeWriteRaw(Char(code).toString())
                        return
                    }
                }
                // Meta = ESC prefix (the one escape sequence every line editor understands) — the
                // ALT key sends nothing of its own, it modifies the following character/token.
                activeWriteRaw(if (useAlt) "\u001B$command" else command)
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

    private fun clipboardManager() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun copyToClipboard() {
        val session = activeSession() ?: return
        val tv = terminalView
        val selected = tv?.selectedText?.takeIf { it.isNotEmpty() }
            ?: run {
                val emu = session.getEmulator() ?: return
                val scr = emu.getScreen() ?: return
                val t = scr.getSelectedText(0, 0, emu.mColumns, scr.getActiveRows(), true)
                if (t.isEmpty()) null else t
            } ?: return
        runCatching {
            clipboardManager().setPrimaryClip(ClipData.newPlainText(null, selected))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        }.onFailure { Log.e(TAG, "clipboard copy failed", it) }
    }

    private fun pasteFromClipboard() {
        val session = activeSession() ?: return
        runCatching {
            val item = clipboardManager().primaryClip?.getItemAt(0) ?: return
            val text = item.coerceToText(this).toString()
            if (text.isEmpty()) return
            val emu = session.getEmulator()
            if (emu != null) emu.paste(text) else session.write(text)
        }.onFailure { Log.e(TAG, "clipboard paste failed", it) }
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
            if (activeSession() == null && activeEngine != ActiveEngine.NONE) {
                restartShell(manual = true)
            }
            val target = activeSession() ?: return false
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
    private var restartInFlight = false
    private var lastCols = 80
    private var lastRows = 24

    private enum class ActiveEngine { NONE, TERMUX, UBUNTU, NATIVE }
    private var activeEngine: ActiveEngine = ActiveEngine.NONE

    private fun runtimePrefs() = getSharedPreferences("terminal", Context.MODE_PRIVATE)
    private fun savedRuntime(): String? {
        val prefs = runtimePrefs()
        // One-time migration: builds before Ubuntu support auto-saved "termux" on first run (there is
        // no engine picker, so that was not a user choice). Clear it once so the new default chain
        // (Ubuntu first) runs; afterwards the persisted value is respected as-is.
        if (!prefs.getBoolean(ENGINE_MIGRATION_KEY, false)) {
            prefs.edit().putBoolean(ENGINE_MIGRATION_KEY, true).remove(RUNTIME_KEY).apply()
            return null
        }
        return prefs.getString(RUNTIME_KEY, null)
    }
    private fun setRuntime(r: String) { runtimePrefs().edit().putString(RUNTIME_KEY, r).apply() }

    private fun startOrAttachShell() {
        val persistedRuntime = savedRuntime()
        if (activeEngine == ActiveEngine.NONE) {
            activeEngine = when (persistedRuntime) {
                "ubuntu" -> ActiveEngine.UBUNTU
                "termux" -> ActiveEngine.TERMUX
                "native" -> ActiveEngine.NATIVE
                else -> ActiveEngine.NONE
            }
        }
        val existing = activeSession()
        // The interactive host is the one place where screen updates must actually reach the
        // TerminalView: the emulator only repaints on invalidate(), and the session announces new
        // output through the engine's onTextChanged (TermuxRuntime/TerminalEngine).
        val redrawHook: (TerminalSession) -> Unit = { _ ->
            terminalView?.post { terminalView?.onScreenUpdated() }
        }
        TermuxRuntime.onScreenChanged = redrawHook
        TerminalEngine.onScreenChanged = redrawHook
        UbuntuRuntime.onScreenChanged = redrawHook
        if (existing != null && isActiveAlive()) {
            statusText?.visibility = View.GONE
            attachView(existing)
            watchForShellDeath(existing)
            return
        }
        scope.launch {
            startAttempted = true
            when (persistedRuntime) {
                "ubuntu" -> startUbuntu()
                "termux" -> startTermux()
                "native" -> startNative(banner = null)
                else -> {
                    // Fresh install: the Ubuntu glibc rootfs is the target userland for the IDE
                    // tooling (opencode, gcc, node, python). startUbuntu falls back to the bionic
                    // Termux userland and then to the device's own mksh if it cannot come up.
                    startUbuntu()
                }
            }
        }
        // Die-away watcher: poll both engines' running flag; a clean exit triggers an auto-restart
        // (or a degrade from Termux → native after a fast crash).
        scope.launch {
            while (true) {
                delay(500)
                if (!startAttempted || restartInFlight) continue
                if (activeEngine == ActiveEngine.NONE) continue
                if (!isActiveAlive()) {
                    restartInFlight = true
                    try {
                        restartShell(manual = false)
                    } finally {
                        restartInFlight = false
                    }
                }
            }
        }
    }

    /** Startup arm: Termux userland (must already be Ready). */
    private suspend fun startTermux() {
        TermuxRuntime.ensureReady { msg -> showStatus(msg) }
        if (TermuxRuntime.setup.value !is TerminalSetupState.Ready) {
            activeEngine = ActiveEngine.NONE
            setRuntime("native")
            startNative(banner = "userland Termux no pudo instalarse → shell nativo de Android (mksh). Sin apt.")
            return
        }
        setRuntime("termux")
        activeEngine = ActiveEngine.TERMUX
        TermuxRuntime.startSession(lastCols, lastRows)
        val s = TermuxRuntime.session
        if (s == null) {
            activeEngine = ActiveEngine.NONE
            startNative(banner = "userland Termux no arrancó → shell nativo de Android (mksh). Sin apt.")
        } else {
            finishStart(s)
        }
    }

    /** Startup arm: the Ubuntu 22.04 proot rootfs (must already be Ready). */
    private suspend fun startUbuntu() {
        showStatus("Preparando Ubuntu 22.04 (descarga única ~27 MB)…")
        UbuntuRuntime.ensureReady { msg -> showStatus(msg) }
        if (UbuntuRuntime.setup.value !is TerminalSetupState.Ready) {
            activeEngine = ActiveEngine.NONE
            startTermux()
            return
        }
        setRuntime("ubuntu")
        activeEngine = ActiveEngine.UBUNTU
        UbuntuRuntime.startSession(lastCols, lastRows)
        val s = UbuntuRuntime.session
        if (s == null) {
            activeEngine = ActiveEngine.NONE
            startTermux()
        } else {
            finishStart(s)
        }
    }

    /** Startup arm: the device's own mksh — cannot fail. Runs through [TerminalEngine]. */
    private fun startNative(banner: String?) {
        activeEngine = ActiveEngine.NATIVE
        setRuntime("native")
        TerminalEngine.startNativeSession(lastCols, lastRows)
        val s = TerminalEngine.session
        if (s == null) {
            activeEngine = ActiveEngine.NONE
            showStatus("No se pudo iniciar el shell nativo")
        } else {
            finishStart(s, banner)
        }
    }

    private fun finishStart(s: TerminalSession, banner: String? = null) {
        lastShellStartMs = SystemClock.uptimeMillis()
        crashStreak = 0
        attachView(s)
        terminalView?.setTerminalCursorBlinkerState(true, true)
        runOnUiThread {
            if (banner != null) {
                statusText?.text = banner
                statusText?.visibility = View.VISIBLE
                handler.postDelayed({ statusText?.visibility = View.GONE }, 8_000)
            } else {
                statusText?.visibility = View.GONE
            }
        }
        watchForShellDeath(s)
    }

    private fun isActiveAlive(): Boolean = when (activeEngine) {
        ActiveEngine.TERMUX -> TermuxRuntime.running.value
        ActiveEngine.UBUNTU -> UbuntuRuntime.running.value
        ActiveEngine.NATIVE -> TerminalEngine.running.value
        ActiveEngine.NONE -> false
    }

    private fun activeSession(): TerminalSession? = when (activeEngine) {
        ActiveEngine.TERMUX -> TermuxRuntime.session
        ActiveEngine.UBUNTU -> UbuntuRuntime.session
        ActiveEngine.NATIVE -> TerminalEngine.session
        ActiveEngine.NONE -> TerminalEngine.session ?: TermuxRuntime.session ?: UbuntuRuntime.session
    }

    private fun activeWriteRaw(text: String) {
        activeSession()?.write(text)
    }

    /** Restart the active shell (fresh prompt). Semi-automatic: guards against crash-loops, and the
     *  first keystroke after a dead shell revives it manually ([manual]=true skips the protection). */
    private fun restartShell(manual: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!manual) {
            if (now - lastShellStartMs < 3_000) crashStreak++ else crashStreak = 0
            if (crashStreak >= 1 && activeEngine == ActiveEngine.UBUNTU) {
                // Ubuntu died within seconds — fall back to the bionic Termux userland (startTermux
                // falls through to the native mksh if Termux cannot come up either).
                crashStreak = 0
                activeEngine = ActiveEngine.NONE
                scope.launch { startTermux() }
                return
            }
            if (crashStreak >= 1 && activeEngine == ActiveEngine.TERMUX) {
                // Termux also died fast — degrade to the native mksh, which cannot fail the same way.
                crashStreak = 0
                activeEngine = ActiveEngine.NONE
                setRuntime("native")
                startNative(banner = "userland Termux se cortó → shell nativo de Android (mksh). Sin apt.")
                return
            }
        }
        awaitingManualRestart = false
        crashStreak = 0
        lastShellStartMs = now
        handler.removeCallbacks(releaseModifiers)
        when (activeEngine) {
            ActiveEngine.TERMUX -> {
                TermuxRuntime.startSession(lastCols, lastRows)
                val s = TermuxRuntime.session
                if (s != null) finishStart(s) else showStatus("No se pudo reiniciar el userland Termux")
            }
            ActiveEngine.UBUNTU -> {
                UbuntuRuntime.startSession(lastCols, lastRows)
                val s = UbuntuRuntime.session
                if (s != null) finishStart(s) else showStatus("No se pudo reiniciar el Ubuntu")
            }
            ActiveEngine.NATIVE -> {
                TerminalEngine.startNativeSession(lastCols, lastRows)
                val s = TerminalEngine.session
                if (s != null) finishStart(s) else showStatus("No se pudo reiniciar el shell nativo")
            }
            ActiveEngine.NONE -> showStatus("No hay shell activo — volvé a abrir la terminal")
        }
    }

    /** 3 s after launch, log the shell buffer + whether it's alive, and surface a real exit reason
     *  on screen (status overlay + toast) instead of a silent black screen. */
    private fun watchForShellDeath(session: TerminalSession) {
        handler.postDelayed({
            val alive = isActiveAlive()
            val emu = session.getEmulator()
            val buf = emu?.getScreen()?.getSelectedText(
                0, 0, emu.mColumns, emu.getScreen().getActiveRows(), true,
            ) ?: ""
            Log.i(TAG, "shell-alive=$alive bufLen=${buf.length}")
            if (buf.isNotBlank()) Log.i(TAG, "shell-buffer:\n${buf.take(1200)}")
            if (!alive) {
                val reason = buf.trim().takeIf { it.isNotEmpty() }?.take(600) ?: "buffer vacío"
                val dbg = TerminalEngine.debugFilePath()
                runOnUiThread {
                    showStatus("El shell se cerró:\n$reason\n\n(detalles en:\n$dbg)")
                    Toast.makeText(this@TerminalActivity, "Shell cerrado — reiniciando…", Toast.LENGTH_LONG).show()
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

    // ensureReady's onProgress and the setup state land on background threads; route every status
    // update through runOnUiThread.
    private fun showStatus(text: String) {
        runOnUiThread { statusText?.let { it.text = text; it.visibility = View.VISIBLE } }
    }

    private fun startTerminalKeepAlive() {
        val intent = Intent(this, TerminalKeepAliveService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }.onFailure { Log.e(TAG, "No se pudo iniciar el servicio de terminal", it) }
    }

    private fun requestTerminalNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
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
        private const val REQ_NOTIFICATIONS = 43
        private const val MOD_CTRL = "\u0000mod-ctrl"
        private const val MOD_ALT = "\u0000mod-alt"
        private const val MOD_COPY = "\u0000mod-copy"
        private const val MOD_PASTE = "\u0000mod-paste"
        private const val RUNTIME_KEY = "runtime"
        private const val ENGINE_MIGRATION_KEY = "engine_migration_v1"
    }
}
