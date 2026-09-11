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
import kotlin.math.max
import kotlin.math.min
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
 * COPY key grabs the visible screen text to the clipboard instead.
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
            setTextSize(19)
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
            "ESC" to "\u001B", "CTRL" to "\u001D", "ALT" to "\u001B", "TAB" to "\t", "/" to "/", "-" to "-",
        )
        addKeyRow(bar,
            "COPY" to COPY, "HOME" to "\u001B[H", "↑" to "\u001B[A", "END" to "\u001B[F",
            "←" to "\u001B[D", "↓" to "\u001B[B", "→" to "\u001B[C",
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
                // Never steal focus from the terminal; plain click writes the escape/control sequence
                // (COPY grabs the visible screen text to the clipboard instead).
                isFocusable = false
                setOnClickListener {
                    if (command == COPY) copyScreen() else TerminalEngine.writeCommand(command)
                }
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
        // Swallow long-press: prevents the selection/copy overlays (mouse pointers) from appearing.
        override fun onLongPress(e: MotionEvent) = true
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(cp: Int, ctrl: Boolean, s: TerminalSession): Boolean {
            s.writeCodePoint(false, cp)
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

    /** Copy the currently visible terminal screen to the Android clipboard (no selection overlays). */
    private fun copyScreen() {
        val session = TerminalEngine.session ?: return
        val emulator = session.getEmulator()
        val screen = emulator.getScreen()
        val cols = emulator.mColumns
        val rows = emulator.mRows
        val total = screen.getActiveRows()
        val top = max(0, total - rows)
        val bottom = max(top, min(total, top + rows))
        val text = screen.getSelectedText(0, top, cols, bottom, true)
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText(null, text))
        Toast.makeText(this, "Copiado: ${text.length} caracteres", Toast.LENGTH_SHORT).show()
    }

    private var startAttempted = false

    private fun startOrAttachShell() {
        // Re-entry: the previous session is still running in the engine; just re-attach the view.
        val existing = TerminalEngine.session
        if (existing != null && TerminalEngine.running.value) {
            statusText?.visibility = View.GONE
            attachView(existing)
        } else {
            scope.launch {
                startAttempted = true
                TerminalEngine.ensureReady { msg -> showStatus(msg) }
                when (val s = TerminalEngine.setup.value) {
                    is TerminalSetupState.Ready -> {
                        showStatus("Waiting for shell…")
                        TerminalEngine.startSession()
                        TerminalEngine.session?.let { attachView(it) }
                    }
                    is TerminalSetupState.Failed -> showStatus("Setup fallido: ${s.message}")
                    else -> showStatus("Preparando terminal…")
                }
            }
        }
        // Watch the session lifecycle so a dead shell surfaces as a message instead of a silent
        // black screen; hide the overlay as soon as the session is actually running.
        scope.launch {
            TerminalEngine.running.collect { runningNow ->
                if (runningNow) {
                    runOnUiThread { statusText?.visibility = View.GONE }
                } else if (startAttempted &&
                    TerminalEngine.setup.value is TerminalSetupState.Ready &&
                    TerminalEngine.session == null
                ) {
                    showStatus("El shell terminó — reabrí la terminal para reiniciarlo")
                }
            }
        }
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
        private const val COPY = "\u0000copy"
    }
}