package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ide.ui.backend.GitBranch
import dev.ide.ui.backend.GitCommitInfo
import dev.ide.ui.backend.GitFile
import dev.ide.ui.backend.GitHubDeviceFlow
import dev.ide.ui.backend.GitHubRepo
import dev.ide.ui.backend.GitHubSession
import dev.ide.ui.backend.GitOpResult
import dev.ide.ui.backend.GitRemote
import dev.ide.ui.backend.GitService
import dev.ide.ui.backend.GitStash
import dev.ide.ui.backend.GitStatus
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.PublishMode
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The git operation currently running in the panel (drives per-button loading states). */
private enum class GitOp {
    Refresh, Init, Commit, Push, Pull, Fetch,
    BranchCreate, BranchCheckout, BranchDelete,
    Merge, StashSave, StashRestore, StashDrop,
    RemoteAdd, RemoteRemove, Publish,
}

/** Which dialog is open; null = none. */
private enum class GitDialog { Commit, Branch, Merge, Stash, Remote, Device, Publish }

/** An auto-dismissing toast: [ok] selects the success/error styling. */
private data class Notice(val text: String, val ok: Boolean, val id: Long)

/**
 * Source-control panel: working-tree changes grouped by kind (nuevos/modificados/eliminados), a git actions
 * grid (commit / push / pull / fetch / branch / merge / stash), remote configuration, repository info and
 * success/error notifications — all driven by the backend [GitService] (JGit on device).
 */
@Composable
fun GitPanel(backend: IdeBackend) {
    val git = backend.git
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var files by remember { mutableStateOf<List<GitFile>>(emptyList()) }
    var currentBranch by remember { mutableStateOf<String?>(null) }
    var remotes by remember { mutableStateOf<List<GitRemote>>(emptyList()) }
    var branchList by remember { mutableStateOf<List<GitBranch>>(emptyList()) }
    var stashList by remember { mutableStateOf<List<GitStash>>(emptyList()) }
    var lastCommit by remember { mutableStateOf<GitCommitInfo?>(null) }
    var busy by remember { mutableStateOf<GitOp?>(null) }
    var dialog by remember { mutableStateOf<GitDialog?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var noticeId by remember { mutableLongStateOf(0L) }
    var refreshing by remember { mutableStateOf(false) }
    var diffFile by remember { mutableStateOf<GitFile?>(null) }
    var diffText by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<GitHubSession?>(null) }
    var repos by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var deviceFlow by remember { mutableStateOf<GitHubDeviceFlow?>(null) }
    var connecting by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            refreshing = true
            val f = runCatching { git.status() }.getOrDefault(emptyList())
            val b = runCatching { git.branch() }.getOrNull()
            val r = runCatching { git.remotes() }.getOrDefault(emptyList())
            val br = runCatching { git.branches() }.getOrDefault(emptyList())
            val st = runCatching { git.stashList() }.getOrDefault(emptyList())
            val lc = runCatching { git.lastCommit() }.getOrNull()
            val s = runCatching { git.githubSession() }.getOrNull()
            withContext(Dispatchers.Main) {
                files = f
                currentBranch = b
                remotes = r
                branchList = br
                stashList = st
                lastCommit = lc
                if (s != null) session = s
                refreshing = false
            }
        }
    }

    /** Runs [block] on IO, shows its result as a notification and reloads the panel state. */
    fun runOp(op: GitOp, closeOnSuccess: Boolean = true, block: suspend () -> GitOpResult) {
        if (busy != null) return
        scope.launch {
            busy = op
            val result = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse { GitOpResult.fail(it.message ?: "Error desconocido") }
            }
            busy = null
            if (result.success && closeOnSuccess) dialog = null
            notice = Notice(result.message, result.success, ++noticeId)
            if (result.conflicts.isNotEmpty()) {
                notice = Notice("Existe un conflicto en:\n" + result.conflicts.joinToString("\n"), false, ++noticeId)
            }
            reload()
        }
    }

    fun openGitHub() {
        val remote = remotes.firstOrNull { it.url.contains("github.com") } ?: remotes.firstOrNull()
        if (remote == null) {
            notice = Notice("Conecta un repositorio remoto primero.", false, ++noticeId)
        } else {
            runCatching { uriHandler.openUri(remote.url) }
        }
    }

    /** Starts the GitHub OAuth device flow: returns the code to display, or a readable error. */
    fun startGithubConnect() {
        if (connecting || deviceFlow != null) return
        scope.launch {
            connecting = true
            val flow = withContext(Dispatchers.IO) { git.githubDevice() }
            connecting = false
            if (flow == null) {
                notice = Notice(
                    "GitHub no está configurado en esta compilación: reemplaza TU_CLIENT_ID en GitServiceCli.kt " +
                        "(github.com/settings/developers → New OAuth App suele bastar con 1 minuto).",
                    false, ++noticeId,
                )
            } else {
                deviceFlow = flow
                dialog = GitDialog.Device
            }
        }
    }

    /** Chooses [repo] as the connected GitHub repository (configures the origin remote). */
    fun pickRepo(repo: GitHubRepo) {
        if (connecting) return
        scope.launch {
            connecting = true
            val result = withContext(Dispatchers.IO) { git.githubConnectRepo(repo.fullName, repo.defaultBranch) }
            connecting = false
            notice = Notice(result.message, result.success, ++noticeId)
            if (result.success) {
                session = session?.copy(repoFullName = repo.fullName, repoDefaultBranch = repo.defaultBranch)
                reload()
            }
        }
    }

    /** Logs out of GitHub and removes the automatically configured origin remote. */
    fun logoutGithub() {
        if (connecting) return
        scope.launch {
            connecting = true
            val result = withContext(Dispatchers.IO) { git.githubDisconnect() }
            connecting = false
            session = null
            repos = emptyList()
            deviceFlow = null
            notice = Notice("Desconectado de GitHub.", true, ++noticeId)
            reload()
        }
    }

    /** Forgets the picked repo (keeps the GitHub session) and returns to the repo picker. */
    fun changeRepo() {
        if (connecting) return
        scope.launch {
            connecting = true
            val result = withContext(Dispatchers.IO) { git.githubClearRepo() }
            connecting = false
            notice = Notice(result.message, result.success, ++noticeId)
            if (result.success) {
                session = session?.copy(repoFullName = null, repoDefaultBranch = null)
                repos = emptyList()
                reload()
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(notice?.id) {
        if (notice != null) {
            delay(4200)
            notice = null
        }
    }

    // Polls GitHub while the device-flow dialog is open, until the user approves (or cancels).
    LaunchedEffect(deviceFlow?.userCode) {
        val flow = deviceFlow ?: return@LaunchedEffect
        while (deviceFlow != null) {
            delay((flow.intervalSeconds.coerceAtLeast(5)) * 1000L)
            val result = withContext(Dispatchers.IO) { git.githubPoll() }
            if (deviceFlow == null) break
            if (result.success) {
                session = runCatching { git.githubSession() }.getOrNull()
                deviceFlow = null
                dialog = null
                notice = Notice(result.message, true, ++noticeId)
                reload()
            } else {
                notice = Notice(result.message, false, ++noticeId)
                if (result.message.contains("expiró") || result.message.contains("rechazada")) {
                    deviceFlow = null
                    dialog = null
                }
            }
        }
    }

    // Loads the repo list once the account is connected and no repo is picked yet.
    LaunchedEffect(session?.login) {
        val s = session
        if (s != null && s.repoFullName == null && repos.isEmpty()) {
            val r = withContext(Dispatchers.IO) { git.githubRepos() }
            repos = r
            if (r.isEmpty()) {
                notice = Notice("No se encontraron repositorios en tu cuenta.", false, ++noticeId)
            }
        }
    }

    val infinite = rememberInfiniteTransition(label = "gitRefresh")
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "refreshSpin",
    )

    Column(Modifier.fillMaxSize()) {
        NoticeBar(notice)

    // --- Header: Title + Refresh (Spinning icon) + GitHub ---
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        CaIcons.gitBranch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Control de versiones",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    currentBranch?.let { "Rama activa: $it" } ?: "Panel de Git",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TextButton(onClick = { reload() }, enabled = !refreshing) {
            Icon(
                CaIcons.refresh,
                contentDescription = "Actualizar",
                modifier = Modifier.size(16.dp).rotate(if (refreshing) angle else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Actualizar", modifier = Modifier.padding(start = 6.dp))
        }
        IconButton(onClick = { openGitHub() }) {
            Icon(
                CaIcons.github,
                contentDescription = "Abrir en GitHub",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }


AnimatedVisibility(
            visible = session != null,
            enter = fadeIn(tween(Motion.BASE)),
            exit = fadeOut(tween(Motion.FAST)),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Ide.colors.success.copy(alpha = 0.14f),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(CaIcons.github, contentDescription = null, tint = Ide.colors.success, modifier = Modifier.size(13.dp))
                        Text(
                            "Conectado a GitHub · ${session?.login.orEmpty()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Ide.colors.success,
                            modifier = Modifier.padding(start = 5.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (session?.repoFullName != null) {
                            Text(
                                " · ${session?.repoFullName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (session?.repoFullName != null) {
                    TextButton(onClick = { changeRepo() }, enabled = !connecting) {
                        Text("Cambiar repo", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = currentBranch != null && diffFile == null,
            enter = fadeIn(tween(Motion.BASE)),
            exit = fadeOut(tween(Motion.FAST)),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(CaIcons.gitBranch, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                        Text(
                            currentBranch.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 5.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (diffFile != null) {
            DiffView(diffFile!!, diffText, onBack = { diffFile = null })
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                when {
                    session == null -> item {
                        GitHubConnectCard(
                            connecting = connecting,
                            onConnect = { startGithubConnect() },
                            onManageRemote = { dialog = GitDialog.Remote },
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    session?.repoFullName == null -> {
                        item {
                            RepoPickerHeader(
                                login = session?.login.orEmpty(),
                                count = repos.size,
                                connectingRepo = connecting,
                                onLogout = { logoutGithub() },
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        items(repos, key = { it.fullName }) { repo ->
                            RepoRow(
                                repo = repo,
                                connecting = connecting,
                                onPick = { pickRepo(repo) },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        item { Spacer(Modifier.height(6.dp)) }
                    }
                    !git.available -> item {
                        NoRepoCard(
                            initializing = busy == GitOp.Init,
                            onInit = { runOp(GitOp.Init) { git.init() } },
                            note = "El repositorio también se inicializará solo al subir el proyecto a ${session?.repoFullName.orEmpty()}.",
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    else -> {
                        item {
                            SectionCard("Acciones") {
                                ActionsGrid(
                                    git = git,
                                    hasRemote = remotes.isNotEmpty(),
                                    busy = busy,
                                    runOp = { op, block -> runOp(op, block = block) },
                                    openDialog = { dialog = it },
                                    onPush = { dialog = GitDialog.Publish },
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        item {
                            SectionCard("Repositorio remoto", CaIcons.github) {
                                RemoteCardBody(
                                    remotes = remotes,
                                    currentBranch = currentBranch,
                                    busyRemove = busy == GitOp.RemoteRemove,
                                    onConnect = { dialog = GitDialog.Remote },
                                    onRemove = { name -> runOp(GitOp.RemoteRemove) { git.removeRemote(name) } },
                                    onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        item {
                            SectionCard("Cambios") {
                                ChangesCard(
                                    files = files,
                                    onToggleStage = { f ->
                                        scope.launch(Dispatchers.IO) {
                                            if (f.staged) git.unstage(listOf(f.path)) else git.stage(listOf(f.path))
                                            reload()
                                        }
                                    },
                                    onOpenDiff = { f ->
                                        scope.launch(Dispatchers.IO) {
                                            val t = runCatching { git.diff(f.path, f.staged) }.getOrDefault("")
                                            withContext(Dispatchers.Main) { diffFile = f; diffText = t }
                                        }
                                    },
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        item {
                            SectionCard("Información") {
                                InfoCardBody(branch = currentBranch, filesCount = files.size, remotes = remotes, lastCommit = lastCommit)
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                    }
                }
            }
        }
    }

    // --- Dialogs ---
    when (dialog) {
        GitDialog.Commit -> CommitDialog(
            busy = busy == GitOp.Commit,
            onDismiss = { dialog = null },
            onConfirm = { message -> runOp(GitOp.Commit) { git.commit(message) } },
        )
        GitDialog.Branch -> BranchDialog(
            branches = branchList,
            busyCreate = busy == GitOp.BranchCreate,
            busyCheckout = busy == GitOp.BranchCheckout,
            busyDelete = busy == GitOp.BranchDelete,
            onDismiss = { dialog = null },
            onCreate = { name -> runOp(GitOp.BranchCreate, closeOnSuccess = false) { git.createBranch(name) } },
            onCheckout = { name -> runOp(GitOp.BranchCheckout) { git.checkoutBranch(name) } },
            onDelete = { name -> runOp(GitOp.BranchDelete, closeOnSuccess = false) { git.deleteBranch(name) } },
        )
        GitDialog.Merge -> MergeDialog(
            branches = branchList,
            current = currentBranch,
            busy = busy == GitOp.Merge,
            onDismiss = { dialog = null },
            onMerge = { name -> runOp(GitOp.Merge) { git.merge(name) } },
        )
        GitDialog.Stash -> StashDialog(
            stashes = stashList,
            busySave = busy == GitOp.StashSave,
            busyRestore = busy == GitOp.StashRestore,
            busyDrop = busy == GitOp.StashDrop,
            onDismiss = { dialog = null },
            onSave = { message -> runOp(GitOp.StashSave) { git.stashSave(message) } },
            onRestore = { id -> runOp(GitOp.StashRestore) { git.stashRestore(id) } },
            onDrop = { id -> runOp(GitOp.StashDrop, closeOnSuccess = false) { git.stashDrop(id) } },
        )
        GitDialog.Remote -> RemoteDialog(
            existing = remotes.firstOrNull(),
            busy = busy == GitOp.RemoteAdd,
            onDismiss = { dialog = null },
            onConnect = { name, url -> runOp(GitOp.RemoteAdd) { git.addRemote(name, url) } },
        )
        GitDialog.Device -> DeviceFlowDialog(
            flow = deviceFlow,
            onDismiss = { deviceFlow = null; dialog = null },
            onOpen = { uri -> runCatching { uriHandler.openUri(uri) } },
        )
        GitDialog.Publish -> PublishDialog(
            defaultBranch = session?.repoDefaultBranch ?: currentBranch ?: "main",
            currentBranch = currentBranch,
            repoName = session?.repoFullName.orEmpty(),
            busy = busy == GitOp.Publish,
            onDismiss = { dialog = null },
            onPublish = { mode, branch, message ->
                runOp(GitOp.Publish) { git.publish(mode, branch, message) }
            },
        )
        null -> Unit
    }
}

// ---------------------------------------------------------------------------
// Notification toast
// ---------------------------------------------------------------------------

@Composable
private fun NoticeBar(notice: Notice?) {
    AnimatedVisibility(
        visible = notice != null,
        enter = slideInVertically(tween(Motion.BASE)) { -it } + fadeIn(tween(Motion.BASE)),
        exit = slideOutVertically(tween(Motion.FAST)) { -it } + fadeOut(tween(Motion.FAST)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (notice?.ok == true) Ide.colors.success.copy(alpha = 0.16f)
            else MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
            border = BorderStroke(
                1.dp,
                if (notice?.ok == true) Ide.colors.success.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
            ),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    if (notice?.ok == true) CaIcons.check else CaIcons.warning,
                    contentDescription = null,
                    tint = if (notice?.ok == true) Ide.colors.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    notice?.text.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// No-repository state
// ---------------------------------------------------------------------------

@Composable
private fun NoRepoCard(initializing: Boolean, onInit: () -> Unit, note: String = "") {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                Icon(CaIcons.gitBranch, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(14.dp).size(28.dp))
            }
            Text(
                "No hay un repositorio git en este proyecto.",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                note.ifEmpty { "Inicializa un repositorio para empezar a usar Control de versiones." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp)
                    .clickable(enabled = !initializing) { onInit() },
            ) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (initializing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(CaIcons.plus, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        "Inicializar repositorio",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Diff view
// ---------------------------------------------------------------------------

@Composable
private fun DiffView(file: GitFile, diffText: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        TextButton(onClick = onBack) { Text("← Volver") }
        Text(
            file.path,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            diffText.ifEmpty { "Sin diferencias." },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Section plumbing
// ---------------------------------------------------------------------------

@Composable
private fun SectionCard(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = if (icon != null) 6.dp else 0.dp),
                )
            }
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Actions grid
// ---------------------------------------------------------------------------

@Composable
private fun ActionsGrid(
    git: GitService,
    hasRemote: Boolean,
    busy: GitOp?,
    runOp: (GitOp, suspend () -> GitOpResult) -> Unit,
    openDialog: (GitDialog) -> Unit,
    onPush: () -> Unit,
) {
    val enabled = busy == null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionTile("Commit", CaIcons.check, running = busy == GitOp.Commit, enabled = enabled, modifier = Modifier.weight(1f)) {
            openDialog(GitDialog.Commit)
        }
        ActionTile("Push", CaIcons.upload, running = busy == GitOp.Publish, enabled = enabled, modifier = Modifier.weight(1f)) {
            onPush()
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionTile("Pull", CaIcons.download, running = busy == GitOp.Pull, enabled = enabled, modifier = Modifier.weight(1f)) {
            runOp(GitOp.Pull) {
                if (!hasRemote) GitOpResult.fail("No hay un repositorio remoto configurado. Conéctalo en la sección Repositorio remoto.")
                else git.pull()
            }
        }
        ActionTile("Fetch", CaIcons.refresh, running = busy == GitOp.Fetch, enabled = enabled, modifier = Modifier.weight(1f)) {
            runOp(GitOp.Fetch) {
                if (!hasRemote) GitOpResult.fail("No hay un repositorio remoto configurado. Conéctalo en la sección Repositorio remoto.")
                else git.fetch()
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionTile("Branch", CaIcons.gitBranch, running = busy == GitOp.BranchCheckout, enabled = enabled, modifier = Modifier.weight(1f)) {
            openDialog(GitDialog.Branch)
        }
        ActionTile("Merge", CaIcons.merge, running = busy == GitOp.Merge, enabled = enabled, modifier = Modifier.weight(1f)) {
            openDialog(GitDialog.Merge)
        }
    }
    Spacer(Modifier.height(10.dp))
    ActionTile("Stash", CaIcons.archive, running = busy == GitOp.StashSave, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        openDialog(GitDialog.Stash)
    }
}

// ---------------------------------------------------------------------------
// Changes grouped by status
// ---------------------------------------------------------------------------

@Composable
private fun ChangesCard(
    files: List<GitFile>,
    onToggleStage: (GitFile) -> Unit,
    onOpenDiff: (GitFile) -> Unit,
) {
    if (files.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(CaIcons.check, contentDescription = null, tint = Ide.colors.success, modifier = Modifier.size(26.dp))
            Text("No hay cambios", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Tu árbol de trabajo está limpio",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        return
    }
    ChangeGroup(
        title = "Nuevos",
        icon = CaIcons.plus,
        color = Ide.colors.gitAdded,
        group = files.filter { it.status == GitStatus.Untracked || it.status == GitStatus.Added },
        onToggleStage = onToggleStage,
        onOpenDiff = onOpenDiff,
    )
    ChangeGroup(
        title = "Modificados",
        icon = CaIcons.dot,
        color = Ide.colors.gitModified,
        group = files.filter { it.status == GitStatus.Modified },
        onToggleStage = onToggleStage,
        onOpenDiff = onOpenDiff,
    )
    ChangeGroup(
        title = "Eliminados",
        icon = CaIcons.close,
        color = Ide.colors.gitDeleted,
        group = files.filter { it.status == GitStatus.Deleted },
        onToggleStage = onToggleStage,
        onOpenDiff = onOpenDiff,
    )
}

@Composable
private fun ChangeGroup(
    title: String,
    icon: ImageVector,
    color: Color,
    group: List<GitFile>,
    onToggleStage: (GitFile) -> Unit,
    onOpenDiff: (GitFile) -> Unit,
) {
    if (group.isEmpty()) return
    Row(Modifier.padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(
            "$title (${group.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
    group.forEach { f ->
        Row(
            Modifier.fillMaxWidth().clickable { onOpenDiff(f) }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
            Text(
                f.path,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            if (f.staged) {
                Icon(CaIcons.check, contentDescription = "Preparado", tint = Ide.colors.success, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Checkbox(checked = f.staged, onCheckedChange = { onToggleStage(f) })
        }
    }
}

// ---------------------------------------------------------------------------
// Remote card
// ---------------------------------------------------------------------------

@Composable
private fun RemoteCardBody(
    remotes: List<GitRemote>,
    currentBranch: String?,
    busyRemove: Boolean,
    onConnect: () -> Unit,
    onRemove: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    if (remotes.isEmpty()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(CaIcons.github, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp).size(24.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Conectar con GitHub", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Conecta tu repositorio remoto",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onConnect() },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(CaIcons.link, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                    Text("Conectar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    } else {
        remotes.forEach { remote ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Icon(CaIcons.github, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp).size(24.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(remote.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Conectado · ${currentBranch?.let { "$remote.name/$it" } ?: remote.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ide.colors.success,
                    )
                    Text(
                        remote.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onOpenUrl(remote.url) }) {
                    Icon(CaIcons.share, contentDescription = "Abrir", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { onRemove(remote.name) }, enabled = !busyRemove) {
                    if (busyRemove) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(CaIcons.close, contentDescription = "Desconectar", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Info card
// ---------------------------------------------------------------------------

@Composable
private fun InfoCardBody(
    branch: String?,
    filesCount: Int,
    remotes: List<GitRemote>,
    lastCommit: GitCommitInfo?,
) {
    InfoRow("Rama actual", branch ?: "—")
    InfoRow("Estado", if (filesCount == 0) "Limpio" else "$filesCount cambio(s)")
    InfoRow("Remoto", remotes.firstOrNull()?.name ?: "No configurado")
    InfoRow(
        "Último commit",
        lastCommit?.let { "${it.message} · ${it.author} · ${relativeTime(it.dateMillis)}" } ?: "Sin commits todavía",
    )
    InfoRow("Cantidad de cambios", filesCount.toString())
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Action tile (grid button with press animation + loading state)
// ---------------------------------------------------------------------------

@Composable
private fun ActionTile(
    label: String,
    icon: ImageVector,
    running: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESS_SCALE else 1f,
        animationSpec = tween(Motion.FAST),
        label = "${label}Press",
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, enabled = enabled && !running) { onClick() },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun DialogShell(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

@Composable
private fun CommitDialog(busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var message by remember { mutableStateOf("") }
    DialogShell("Commit", onDismiss) {
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Mensaje del commit") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
            TextButton(onClick = { onConfirm(message) }, enabled = !busy && message.isNotBlank()) {
                Text(if (busy) "Confirmando…" else "Confirmar commit")
            }
        }
    }
}

@Composable
private fun BranchDialog(
    branches: List<GitBranch>,
    busyCreate: Boolean,
    busyCheckout: Boolean,
    busyDelete: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onCheckout: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(branches.firstOrNull { it.isCurrent }?.name) }
    DialogShell("Administrar ramas", onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Nueva rama") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            TextButton(onClick = { onCreate(newName); newName = "" }, enabled = !busyCreate && newName.isNotBlank()) {
                if (busyCreate) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Icon(CaIcons.plus, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Crear", modifier = Modifier.padding(start = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().height(220.dp)) {
            items(branches, key = { it.name }) { b ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = b.name }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (b.isCurrent) CaIcons.check else CaIcons.dot,
                        contentDescription = null,
                        tint = if (b.isCurrent) Ide.colors.success else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        b.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (b.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!b.isRemote && !b.isCurrent && selected == b.name) {
                        IconButton(onClick = { onDelete(b.name) }, enabled = !busyDelete && selected == b.name) {
                            if (busyDelete) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            else Icon(CaIcons.close, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
            val sel = branches.firstOrNull { it.name == selected }
            TextButton(
                onClick = { selected?.let(onCheckout) },
                enabled = !busyCheckout && sel != null && !sel.isCurrent,
            ) {
                Text(if (busyCheckout) "Cambiando…" else "Cambiar de rama")
            }
        }
    }
}

@Composable
private fun MergeDialog(
    branches: List<GitBranch>,
    current: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onMerge: (String) -> Unit,
) {
    val candidates = branches.filter { !it.isRemote && it.name != current }
    var selected by remember { mutableStateOf(candidates.firstOrNull()?.name) }
    DialogShell("Fusionar rama", onDismiss) {
        Text(
            "Elige una rama para fusionar con ${current ?: "la rama actual"}:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().height(200.dp)) {
            items(candidates, key = { it.name }) { b ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = b.name }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected == b.name) CaIcons.check else CaIcons.dot,
                        contentDescription = null,
                        tint = if (selected == b.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        b.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
            TextButton(onClick = { selected?.let(onMerge) }, enabled = !busy && selected != null) {
                Text(if (busy) "Fusionando…" else "Fusionar")
            }
        }
    }
}

@Composable
private fun StashDialog(
    stashes: List<GitStash>,
    busySave: Boolean,
    busyRestore: Boolean,
    busyDrop: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRestore: (Int) -> Unit,
    onDrop: (Int) -> Unit,
) {
    var message by remember { mutableStateOf("") }
    DialogShell("Stash", onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Descripción (opcional)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            TextButton(onClick = { onSave(message); message = "" }, enabled = !busySave) {
                if (busySave) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Icon(CaIcons.archive, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Guardar", modifier = Modifier.padding(start = 4.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(4.dp))
        if (stashes.isEmpty()) {
            Text(
                "No hay cambios guardados.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().height(200.dp)) {
                items(stashes, key = { it.id }) { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRestore(s.id) }, enabled = !busyRestore) {
                            if (busyRestore) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            else Icon(CaIcons.undo, contentDescription = "Restaurar", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { onDrop(s.id) }, enabled = !busyDrop) {
                            if (busyDrop) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            else Icon(CaIcons.close, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    }
}

@Composable
private fun RemoteDialog(
    existing: GitRemote?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConnect: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "origin") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    DialogShell("Repositorio remoto", onDismiss) {
        Text(
            "Configura el repositorio remoto al que se harán Push y Pull.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nombre") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL (https://github.com/usuario/repo.git)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
            TextButton(onClick = { onConnect(name, url) }, enabled = !busy && url.isNotBlank()) {
                Text(if (busy) "Conectando…" else "Conectar")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// GitHub connect / repo picker / device-flow UI
// ---------------------------------------------------------------------------

@Composable
private fun GitHubConnectCard(connecting: Boolean, onConnect: () -> Unit, onManageRemote: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                Icon(CaIcons.github, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp).size(32.dp))
            }
            Text(
                "Conectar con GitHub",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                "Inicia sesión con tu cuenta de GitHub para ver tus repositorios, elegir uno " +
                    "y subir tu proyecto directamente a tu perfil. No saldrás de la app: GitHub te " +
                    "mostrará un código de 8 dígitos que confirmas en el navegador.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 18.dp)
                    .clickable(enabled = !connecting) { onConnect() },
            ) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (connecting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(CaIcons.github, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        "Conectar con GitHub",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            TextButton(onClick = onManageRemote, enabled = !connecting) {
                Text("O configurar un remoto manualmente (URL)", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun RepoPickerHeader(login: String, count: Int, connectingRepo: Boolean, onLogout: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Ide.colors.success.copy(alpha = 0.14f)) {
                Icon(CaIcons.github, contentDescription = null, tint = Ide.colors.success, modifier = Modifier.padding(10.dp).size(24.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Elige tu repositorio", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (count == 0) "Cargando repositorios de @$login…"
                    else "Selecciona a dónde subirás tu proyecto, @$login",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onLogout, enabled = !connectingRepo) { Text("Cerrar sesión") }
        }
    }
}

@Composable
private fun RepoRow(repo: GitHubRepo, connecting: Boolean, onPick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESS_SCALE else 1f,
        animationSpec = tween(Motion.FAST),
        label = "repoPress",
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, enabled = !connecting) { onPick() },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CaIcons.gitBranch, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(repo.fullName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Rama predeterminada: ${repo.defaultBranch}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(CaIcons.link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DeviceFlowDialog(flow: GitHubDeviceFlow?, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    if (flow == null) return
    DialogShell("Conectar con GitHub", onDismiss) {
        Text(
            "Ingresa este código en github.com/login/device dentro de tu navegador:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            flow.userCode,
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp),
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clickable { onOpen(flow.verificationUri) },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(CaIcons.share, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                Text(
                    "Abrir ${flow.verificationUri}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            "La app comprueba automáticamente cada ${flow.intervalSeconds} s. No cierres este diálogo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    }
}

@Composable
private fun PublishDialog(
    defaultBranch: String,
    currentBranch: String?,
    repoName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPublish: (PublishMode, String, String) -> Unit,
) {
    var mode by remember { mutableStateOf(PublishMode.FULL_PROJECT) }
    var branch by remember { mutableStateOf(defaultBranch) }
    var message by remember { mutableStateOf("") }
    DialogShell("Subir a GitHub", onDismiss) {
        Text(
            "Destino: $repoName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ModeOption(
            selected = mode == PublishMode.FULL_PROJECT,
            enabled = !busy,
            title = "Todo el proyecto",
            subtitle = "Inicializa el repositorio y sube todas las carpetas y archivos del proyecto.",
            onClick = { mode = PublishMode.FULL_PROJECT },
        )
        Spacer(Modifier.height(8.dp))
        ModeOption(
            selected = mode == PublishMode.CHANGES_ONLY,
            enabled = !busy,
            title = "Solo mis cambios",
            subtitle = "Sube únicamente los cambios del árbol de trabajo (recomendado en cada edición).",
            onClick = { mode = PublishMode.CHANGES_ONLY },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            label = { Text("Rama de destino (github.com)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Mensaje del commit (opcional)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Text(
            if (currentBranch != null && currentBranch != branch) "Se publicará tu rama $currentBranch en la rama $branch de GitHub."
            else "Se publicará tu rama actual en $branch de GitHub.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
            TextButton(onClick = { onPublish(mode, branch, message) }, enabled = !busy && branch.isNotBlank()) {
                if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(if (busy) "Subiendo…" else "Subir proyecto", modifier = Modifier.padding(start = if (busy) 6.dp else 0.dp))
            }
        }
    }
}

@Composable
private fun ModeOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) CaIcons.check else CaIcons.dot,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun relativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    if (diff < 60_000) return "Justo ahora"
    val minutes = diff / 60_000
    return when {
        minutes < 60 -> "Hace $minutes min"
        minutes < 60 * 24 -> "Hace ${minutes / 60} h"
        else -> "Hace ${minutes / (60 * 24)} días"
    }
}
