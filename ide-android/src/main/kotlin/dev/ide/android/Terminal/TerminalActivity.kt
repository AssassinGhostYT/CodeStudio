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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
 * Storage is requested exactly once (first launch): READ_EXTERNAL_STORAGE runtime prompt on ≤11,
 * one-tap jump to "All files access" settings on 12+ — never nagged again (unlike the old panel's
 * every-entry TerminalStorageGate banner).
 */
class TerminalActivity : Activity() {

    private val scope = MainScope()
    private var terminalView: TerminalView? = null
    private var statusText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
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
        val terminalArea = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val tv = TerminalView(this, null).apply {
            setTextSize(14)
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
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        statusText = st
        terminalArea.addView(
            st,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        root.addView(
            terminalArea,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(buildKeysBar())
        setContentView(root)
    }

    private fun buildKeysBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 26, 30))
            setPadding(6, 4, 6, 4)
        }
        bar.addView(keyRow(
            "ESC" to "\u001B", "TAB" to "\t", "CTRL" to "\u001D", "ALT" to "\u001B", "/" to "/", "-" to "-",
        ))
        bar.addView(keyRow(
            "HOME" to "\u001B[H", "↑" to "\u001B[A", "END" to "\u001B[F", "←" to "\u001B[D",
            "↓" to "\u001B[B", "→" to "\u001B[C", "PGUP" to "\u001B[5~", "PGDN" to "\u001B[6~",
        ))
        return bar
    }

    private fun keyRow(vararg keys: Pair<String, String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 3, 0, 3)
        }
        for ((label, command) in keys) {
            val button = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setPadding(0, 12, 0, 12)
                isClickable = true
                // Never steal focus from the terminal; plain click writes the escape/control sequence.
                isFocusable = false
                setOnClickListener { TerminalEngine.writeCommand(command) }
            }
            button.setBackgroundColor(Color.rgb(45, 49, 55))
            button.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
            row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = if (command != keys.last().second) 4 else 0
            })
        }
        return row
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
        override fun onLongPress(e: MotionEvent) = false
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
    }
}