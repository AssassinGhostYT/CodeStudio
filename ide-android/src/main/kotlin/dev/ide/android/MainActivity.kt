package dev.ide.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.core.CaprojFormat
import dev.ide.core.IdeServicesBackend
import dev.ide.ui.CodeStudioApp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android host. Bootstraps the real on-device engine ([AndroidIde]) off the main thread, then renders the
 * shared Compose IDE UI ([CodeStudioApp]) over the resulting [IdeBackend] — the same path the desktop runs.
 * The SAF/FileProvider plumbing lives in [AndroidFileOps]; the small host helpers (splash, "Save As" contract,
 * ad init, source-root search) in AndroidHostUi. This activity is just lifecycle + the Compose host + the SAF
 * launchers (which must be remembered in composition). A splash shows while the engine starts.
 */
class MainActivity : ComponentActivity() {

    private var session: AndroidIde.Session? = null

    /** A file handed in by another app ("Open with" / "Share to"), pending import once the engine is up. */
    private val inbound = mutableStateOf<Uri?>(null)

    /** Android file/SAF/FileProvider plumbing (byte-level import / share / export / install / reveal). */
    private val fileOps by lazy { AndroidFileOps(this) }

    /** UMP consent flow (gathers ad consent before AdMob init; drives the "Manage ad consent" Settings entry). */
    private val adConsent by lazy { AdConsentManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge with default (auto) bar styles; the system-bar ICON appearance is then driven reactively
        // by the app theme via `PlatformSystemBars` (light icons in dark mode, dark icons in light mode) — a fixed
        // `SystemBarStyle.dark` here forced light icons even in light mode.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        inbound.value = extractStream(intent)
        // Gather UMP consent BEFORE initializing the Ads SDK (mediation + EEA/UK requirement); only initialize
        // once consent allows ad requests. Failure resolves too, so a consent hiccup never blocks the IDE.
        adConsent.gather(this) { if (adConsent.canRequestAds) initAds(applicationContext) }

        setContent {
            var backend by remember { mutableStateOf<IdeBackend?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                runCatching { withContext(Dispatchers.IO) { AndroidIde.bootstrap(applicationContext) } }.onSuccess { s ->
                    session = s; backend = s.backend
                }.onFailure { e -> error = e.stackTraceToString() }
            }

            // POST_NOTIFICATIONS (Android 13+/API 33) is asked for at the FIRST build, not here at launch, so the
            // request lands in context — see BuildNotificationGate (:ide-ui). Nothing to request in this activity.
            var pendingTarget by remember { mutableStateOf<String?>(null) }
            var pendingCallback by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
            val importLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    val b = backend
                    val target = pendingTarget
                    val created =
                        if (b != null && target != null) uris.mapNotNull { fileOps.importUri(it, target, b) } else emptyList()
                    pendingCallback?.invoke(created)
                    pendingTarget = null; pendingCallback = null
                }
            var pendingPick by remember { mutableStateOf<((String?) -> Unit)?>(null) }
            val pickLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    pendingPick?.invoke(uri?.let { fileOps.copyUriToCache(it) })
                    pendingPick = null
                }
            // Directory picker (Gradle import): SAF hands back a content:// tree, so copy it into local
            // storage off the main thread and return that path — the copy can be large, hence the coroutine.
            val hostScope = rememberCoroutineScope()
            var pendingPickDir by remember { mutableStateOf<((String?) -> Unit)?>(null) }
            val pickDirLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    val cb = pendingPickDir
                    pendingPickDir = null
                    if (uri == null) { cb?.invoke(null); return@rememberLauncherForActivityResult }
                    Toast.makeText(this@MainActivity, "Copying project…", Toast.LENGTH_SHORT).show()
                    hostScope.launch {
                        val path = withContext(Dispatchers.IO) { fileOps.copyTreeToCache(uri) }
                        cb?.invoke(path)
                    }
                }
            // "Save As" export: the user picks a destination (Files/Drive/Downloads); we copy the bytes there.
            var pendingExport by remember { mutableStateOf<String?>(null) }
            val exportLauncher =
                rememberLauncherForActivityResult(ExportDocumentContract()) { uri ->
                    val src = pendingExport
                    pendingExport = null
                    if (uri != null && src != null) fileOps.exportTo(uri, src)
                }
            val fileActions = remember {
                object : FileActions {
                    override val canImport: Boolean = true
                    override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) {
                        pendingTarget = targetDir
                        pendingCallback = onImported
                        try {
                            importLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "*/*"))
                        } catch (e: ActivityNotFoundException) {
                            pendingTarget = null; pendingCallback = null
                            Toast.makeText(this@MainActivity, "No file manager available to import from", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override val canPickFile: Boolean = true
                    override fun pickFile(extensions: List<String>, onPicked: (String?) -> Unit) {
                        pendingPick = onPicked
                        // Custom extensions (e.g. .caproj) have no registered MIME, so fall back to */* and let
                        // the caller validate the picked file; known extensions narrow the SAF picker.
                        val mimes = extensions.mapNotNull { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                        try {
                            pickLauncher.launch(if (mimes.isEmpty()) arrayOf("*/*") else mimes.toTypedArray())
                        } catch (e: ActivityNotFoundException) {
                            // Some ROMs ship no SAF/documents provider; don't crash — signal cancel + tell the user.
                            pendingPick = null
                            onPicked(null)
                            Toast.makeText(this@MainActivity, "No file manager available to pick a file", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override val canPickDirectory: Boolean = true
                    override fun pickDirectory(onPicked: (String?) -> Unit) {
                        pendingPickDir = onPicked
                        try {
                            pickDirLauncher.launch(null)
                        } catch (e: ActivityNotFoundException) {
                            pendingPickDir = null
                            onPicked(null)
                            Toast.makeText(this@MainActivity, "No file manager available to pick a folder", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override val canShare: Boolean = true
                    override fun share(path: String) = fileOps.shareFile(path)

                    override val canExport: Boolean = true
                    override fun exportFile(path: String) {
                        pendingExport = path
                        try {
                            exportLauncher.launch(File(path).name)
                        } catch (e: ActivityNotFoundException) {
                            pendingExport = null
                            Toast.makeText(this@MainActivity, "No file manager available to export to", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override val canOpenUrl: Boolean = true
                    override fun openUrl(url: String) = fileOps.openInBrowser(url)

                    override val canReveal: Boolean = true
                    override fun reveal(path: String) = fileOps.openInFiles(path)

                    override val canInstallApk: Boolean = true
                    override fun installApk(path: String) = fileOps.promptInstall(path)
                }
            }

            // Android advertising bridge (native ads via AdMob + mediation). The consent hooks drive the
            // Settings "Manage ad consent" entry (UMP privacy options) and reopen the form on request.
            val adHost = remember {
                AndroidAdHost(
                    openUrl = { url -> fileOps.openInBrowser(url) },
                    privacyOptionsRequiredProvider = { adConsent.privacyOptionsRequired },
                    onShowPrivacyOptions = { adConsent.showPrivacyOptions(this@MainActivity) },
                    // The full-screen build interstitial needs the foreground Activity to show().
                    activityProvider = { this@MainActivity },
                    // `lastUpdateTime` changes on a fresh install and on every update, and is identical across
                    // launches in between — the shared AdController turns ads back on once per new value.
                    installStamp = runCatching {
                        packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
                    }.getOrNull(),
                )
            }

            // A `.caproj` package handed in via "Open with" opens the import preview (see the branch below); any
            // other inbound file is copied into the open project's first source root as before.
            var importPackagePath by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(backend, inbound.value) {
                val b = backend
                val uri = inbound.value
                if (b != null && uri != null) {
                    val name = fileOps.queryDisplayName(uri) ?: ""
                    if (name.endsWith(".${CaprojFormat.EXTENSION}", ignoreCase = true)) {
                        val path = withContext(Dispatchers.IO) { fileOps.copyInboundToCache(uri, name) }
                        if (path != null) importPackagePath = path
                        else Toast.makeText(this@MainActivity, "Couldn't open the project package", Toast.LENGTH_SHORT).show()
                    } else {
                        val target = firstSourceRoot(b.files.fileTree())
                        val path = if (target != null) fileOps.importUri(uri, target, b) else null
                        Toast.makeText(
                            this@MainActivity,
                            if (path != null) "Imported ${File(path).name}" else "Couldn't import file",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    inbound.value = null
                }
            }

            val b = backend
            when {
                b != null -> CodeStudioApp(
                    b,
                    fileActions = fileActions,
                    adHost = adHost,
                    // On-device Compose preview: render @Preview composables through the interpreter. The backend
                    // instance is stable across project switches (it swaps services internally), so one host suffices.
                    composePreviewHost = (b as? IdeServicesBackend)?.let { AndroidComposePreviewHost(it) },
                    importPackagePath = importPackagePath,
                    // Real Termux terminal as an Activity — replaces the legacy in-IDE PRoot dock for the
                    // toolbar button. The activity is com.termux.app.TermuxActivity, declared in :termux:application's
                    // manifest (launchMode=singleTask, exported=true so it can be launched via Intent; it is
                    // NOT a launcher entry, only the toolbar button starts it).
                    onOpenTerminal = {
                        // Wrap in try-catch so a missing-class / not-found-activity failure surfaces as a Toast
                        // instead of being silently swallowed by Compose's recomposition handler. Every branch
                        // also logs to logcat under [terminalTag] so `adb logcat -s CodeTermux:*` shows the trace
                        // even when the Toast is missed (fades in ~3.5s, easy to overlook).
                        //
                        // The happy path ([startActivity] returns) ALSO Toasts, but briefly, so we can verify
                        // visually that the tap reached MainActivity and the launch was issued without needing
                        // adb. If the user sees "Lanzando Termux…" but no terminal appears, the failure is on
                        // Termux's side (e.g. TermuxAppSharedPreferences.build() returning null at line 212 of
                        // TermuxActivity.java — see ce63814 for the TERMUX_PACKAGE_NAME alignment that usually
                        // fixes it).
                        val terminalTag = "CodeTermux"
                        Log.i(terminalTag, "tap fired; activity=${this@MainActivity.javaClass.simpleName}")
                        try {
                            // Resolve the class explicitly so a missing-class failure (e.g. Termux not bundled
                            // in this APK) shows up in logcat instead of dying silently before resolveActivity.
                            val activityClass = Class.forName("com.termux.app.TermuxActivity")
                            Log.i(terminalTag, "loaded ${activityClass.name}")
                            val intent = Intent(this, activityClass)
                            val resolved = packageManager.resolveActivity(intent, 0)
                            if (resolved != null) {
                                Log.i(terminalTag, "resolveActivity hit ${resolved.activityInfo.name}; launching")
                                startActivity(intent)
                                Log.i(terminalTag, "startActivity returned")
                                Toast.makeText(this@MainActivity, "✓ Lanzando Termux…", Toast.LENGTH_SHORT).show()
                            } else {
                                val msg = "❌ Termux activity not registered in this build (merged manifest missing com.termux.app.TermuxActivity)"
                                Log.e(terminalTag, msg)
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        } catch (e: ClassNotFoundException) {
                            Log.e(terminalTag, "TermuxActivity class not in this APK", e)
                            Toast.makeText(
                                this@MainActivity,
                                "❌ Termux no incluido en este build",
                                Toast.LENGTH_LONG,
                            ).show()
                        } catch (e: android.content.ActivityNotFoundException) {
                            Log.e(terminalTag, "ActivityNotFoundException", e)
                            Toast.makeText(
                                this@MainActivity,
                                "❌ Couldn't open Terminal: ${e.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        } catch (e: Throwable) {
                            Log.e(terminalTag, "launch failed", e)
                            Toast.makeText(
                                this@MainActivity,
                                "❌ Terminal launch failed: ${e.javaClass.simpleName}: ${e.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )

                error != null -> Splash("Failed to start: $error")
                else -> Splash("Starting CodeStudio…")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractStream(intent)?.let { inbound.value = it }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Close the *active* engine (a project switch may have swapped it), not just the initial one.
        session?.backend?.close()
    }

    private fun extractStream(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> null
    }
}
