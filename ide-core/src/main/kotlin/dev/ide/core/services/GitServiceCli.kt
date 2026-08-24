package dev.ide.core.services

import dev.ide.core.EngineContext
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
import dev.ide.ui.backend.PublishMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand.ListMode
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.MergeResult.MergeStatus
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TrackingRefUpdate
import org.eclipse.jgit.transport.TrackingRefUpdate.Result as TrackingRefUpdateResult
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Git integration backed by JGit (pure Java, runs on ART — no `git` CLI, which Android doesn't ship). Every
 * operation opens the workspace's repository and degrades to a readable [GitOpResult] failure when the
 * workspace isn't a repo or JGit can't perform the operation, so the UI can show success/error toasts and
 * conflict lists instead of throwing. [init] creates a repo on demand so the Source-control panel is useful
 * even for a freshly opened (non-git) project.
 */
internal class GitServiceCli(private val ctx: EngineContext) : GitService {

    /**
     * Replace with your GitHub OAuth App client id (github.com/settings/developers → New OAuth App → keep the
     * callback URL unused — the device flow never calls it). Without a real client id the connect flow reports
     * a readable error instead of failing silently.
     */
    private companion object {
        const val GITHUB_CLIENT_ID = "TU_CLIENT_ID"
        const val SESSION_FILE = "github-session.properties"
    }

    private val root: File get() = ctx.workspaceRoot.toFile()

    private val json = Json { ignoreUnknownKeys = true }

    private val sessionFile: File
        get() {
            val cached = ctx.sharedCachesRoot
            return if (cached != null) cached.resolve(SESSION_FILE).toFile()
            else File(File(System.getProperty("java.io.tmpdir") ?: "."), SESSION_FILE)
        }

    private fun readSession(): Properties? = runCatching {
        val file = sessionFile
        if (!file.isFile) return null
        Properties().apply { file.inputStream().use(::load) }
    }.getOrNull()

    private fun writeSession(props: Properties): Boolean = runCatching {
        sessionFile.parentFile?.mkdirs()
        sessionFile.outputStream().use { props.store(it, "CodeAssist GitHub session") }
    }.isSuccess

    private fun clearSession() {
        sessionFile.delete()
        _deviceFlow = null
        _deviceCode = null
    }

    private fun sessionToken(): String? =
        readSession()?.getProperty("token")?.takeIf { it.isNotBlank() && it != GITHUB_CLIENT_ID }

    private fun githubCredentials() = sessionToken()?.let { UsernamePasswordCredentialsProvider("x-access-token", it) }

    /** Pending device-flow state (kept in memory only — polling needs the device code between checks). */
    private var _deviceCode: String? = null
    private var _deviceFlow: GitHubDeviceFlow? = null

    override val available: Boolean get() = File(root, ".git").exists()

    private fun open(): Git? = runCatching { Git.open(root) }.getOrNull()

    private fun run(block: (Git) -> GitOpResult, noRepo: String): GitOpResult {
    val git = open() ?: return GitOpResult.fail(noRepo)
    return runCatching { block(git) }.getOrElse {
        GitOpResult.fail("No se pudo completar la operación: ${it.message?.lineSequence()?.firstOrNull()?.take(120) ?: "error"}")
    }
}

    override fun init(): GitOpResult {
        if (available) return GitOpResult.ok("El repositorio ya está inicializado.")
        return runCatching {
            Git.init().setDirectory(root).call()
            "Repositorio git inicializado en ${root.absolutePath}."
        }.fold(
            onSuccess = { GitOpResult.ok(it) },
            onFailure = { GitOpResult.fail("No se pudo inicializar el repositorio: ${it.message?.lineSequence()?.firstOrNull()?.take(120) ?: "error"}") },
        )
    }

    override fun branch(): String? {
        val git = open() ?: return null
        return runCatching {
            val full = git.repository.fullBranch
            if (full.startsWith(Constants.R_HEADS)) full.substring(Constants.R_HEADS.length) else null
        }.getOrNull()
    }

    override fun status(): List<GitFile> {
        val git = open() ?: return emptyList()
        return runCatching {
            val st = git.status().call()
            val out = mutableListOf<GitFile>()
            st.added.forEach { out += GitFile(it, GitStatus.Added, staged = true) }
            st.changed.forEach { out += GitFile(it, GitStatus.Modified, staged = true) }
            st.removed.forEach { out += GitFile(it, GitStatus.Deleted, staged = true) }
            st.modified.forEach { out += GitFile(it, GitStatus.Modified, staged = false) }
            st.missing.forEach { out += GitFile(it, GitStatus.Deleted, staged = false) }
            st.untracked.forEach { out += GitFile(it, GitStatus.Untracked, staged = false) }
            st.untrackedFolders.forEach { out += GitFile(it, GitStatus.Untracked, staged = false) }
            out
        }.getOrDefault(emptyList())
    }

    override fun diff(path: String, staged: Boolean): String {
        val git = open() ?: return ""
        return runCatching {
            val cmd = git.diff()
            if (staged) cmd.setCached(true)
            cmd.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(path))
            val entries: List<DiffEntry> = cmd.call()
            val baos = ByteArrayOutputStream()
            DiffFormatter(baos).apply {
                setRepository(git.repository)
                format(entries)
                flush()
            }
            baos.toString("UTF-8")
        }.getOrDefault("")
    }

    override fun stage(paths: List<String>) {
        val git = open() ?: return
        if (paths.isEmpty()) return
        runCatching {
            val add = git.add()
            paths.forEach { add.addFilepattern(it) }
            add.call()
        }
    }

    override fun unstage(paths: List<String>) {
        val git = open() ?: return
        if (paths.isEmpty()) return
        runCatching {
            val reset = git.reset()
            paths.forEach { reset.addPath(it) }
            reset.call()
        }
    }

    override fun commit(message: String): GitOpResult {
        val msg = message.trim()
        if (msg.isEmpty()) return GitOpResult.fail("Escribe un mensaje de commit.")
        return run({ git: Git ->
            val commit = git.commit().setMessage(msg).call()
            GitOpResult.ok("Commit ${commit.name.take(7)} creado en ${branch() ?: "la rama actual"}.")
        }, "Git no está disponible en este entorno.")
    }

    override fun push(remote: String): GitOpResult {
        return run({ git ->
            val b = branch() ?: return@run GitOpResult.fail("No se pudo determinar la rama actual.")
            val cmd = git.push().setRemote(remote)
            githubCredentials()?.let { cmd.setCredentialsProvider(it) }
            cmd.call()
            GitOpResult.ok("Push a $remote/$b completado.")
        }, "Git no está disponible en este entorno.")
    }

    override fun pull(remote: String): GitOpResult {
        return run({ git ->
            val cmd = git.pull().setRemote(remote).setRebase(false)
            githubCredentials()?.let { cmd.setCredentialsProvider(it) }
            val result = cmd.call()
            val mergeResult = result.mergeResult
            val conflicts = mergeResult?.conflicts?.keys?.toList().orEmpty()
            when {
                result.isSuccessful -> GitOpResult.ok("Pull completado desde $remote.")
                conflicts.isNotEmpty() -> GitOpResult(false, "Pull completado con conflictos.", conflicts)
                else -> GitOpResult.fail("No se pudo hacer pull desde $remote: revisa la conexión o los cambios locales pendientes.")
            }
        }, "Git no está disponible en este entorno.")
    }

    override fun fetch(remote: String): GitOpResult {
        return run({ git ->
            val cmd = git.fetch().setRemote(remote)
            githubCredentials()?.let { cmd.setCredentialsProvider(it) }
            val result = cmd.call()
            val updated = result.trackingRefUpdates.filter {
                it.result == TrackingRefUpdateResult.NEW ||
                    it.result == TrackingRefUpdateResult.FAST_FORWARD ||
                    it.result == TrackingRefUpdateResult.FORCED
            }
            if (updated.isEmpty()) GitOpResult.ok("Fetch completado: sin cambios nuevos en $remote.")
            else GitOpResult.ok("Fetch completado: ${updated.size} rama(s) actualizada(s) desde $remote.")
        }, "Git no está disponible en este entorno.")
    }

    override fun merge(branch: String): GitOpResult {
        return run({ git ->
            val refName = if (git.repository.findRef("refs/heads/$branch") != null) "refs/heads/$branch"
            else "refs/remotes/$branch"
            val ref = git.repository.findRef(refName) ?: return@run GitOpResult.fail("No se encontró la rama $branch")
            val result = git.merge().include(ref).call()
            when (result.mergeStatus) {
                MergeStatus.CONFLICTING -> {
                    val conflictList: List<String> = result.conflicts?.keys?.toList() ?: emptyList()
                    GitOpResult(
                        success = false,
                        message = "Conflicto al fusionar $branch con ${branch() ?: "la rama actual"}.",
                        conflicts = conflictList,
                    )
                }
                MergeStatus.ALREADY_UP_TO_DATE -> GitOpResult.ok("Ya está actualizada con $branch.")
                else -> if (result.mergeStatus.isSuccessful)
                    GitOpResult.ok("Rama $branch fusionada en ${branch() ?: "la rama actual"}.")
                else GitOpResult.fail("No se pudo fusionar $branch: ${result.mergeStatus}")
            }
        }, "Git no está disponible en este entorno.")
    }

    override fun branches(): List<GitBranch> {
        val git = open() ?: return emptyList()
        return runCatching {
            val current = branch()
            git.branchList().setListMode(ListMode.ALL).call().map { ref ->
                val remote = ref.name.startsWith(Constants.R_REMOTES)
                val name = if (remote) ref.name.substring(Constants.R_REMOTES.length)
                else ref.name.substring(Constants.R_HEADS.length)
                GitBranch(name = name, isCurrent = !remote && name == current, isRemote = remote)
            }
        }.getOrDefault(emptyList())
    }

    override fun createBranch(name: String): GitOpResult {
        val clean = name.trim()
        if (clean.isEmpty()) return GitOpResult.fail("Escribe un nombre para la rama.")
        return run({ git ->
            val existing = git.repository.findRef("refs/heads/$clean")
            if (existing != null) return@run GitOpResult.fail("La rama $clean ya existe.")
            git.branchCreate().setName(clean).call()
            GitOpResult.ok("Rama $clean creada correctamente.")
        }, "Git no está disponible en este entorno.")
    }

    override fun checkoutBranch(name: String): GitOpResult {
        val clean = name.trim()
        if (clean.isEmpty()) return GitOpResult.fail("Elige una rama.")
        return run({ git ->
            val remote = clean.startsWith("origin/") || clean.contains('/')
            if (!remote) {
                git.checkout().setName(clean).call()
            } else {
                val local = clean.substringAfter('/')
                git.checkout()
                    .setCreateBranch(true)
                    .setName(local)
                    .setUpstreamMode(SetupUpstreamMode.TRACK)
                    .setStartPoint(clean)
                    .call()
            }
            GitOpResult.ok("Cambiado a la rama ${if (remote) clean.substringAfter('/') else clean}.")
        }, "Git no está disponible en este entorno.")
    }

    override fun deleteBranch(name: String): GitOpResult {
        return run({ git ->
            git.branchDelete().setBranchNames(name).setForce(true).call()
            GitOpResult.ok("Rama $name eliminada.")
        }, "Git no está disponible en este entorno.")
    }

    override fun stashSave(message: String): GitOpResult {
        return run({ git ->
            val commit = if (message.isBlank()) git.stashCreate().call()
            else git.stashCreate().setWorkingDirectoryMessage(message.trim()).call()
            if (commit == null) GitOpResult.fail("No hay cambios para guardar en el stash.")
            else GitOpResult.ok("Cambios guardados en el stash.")
        }, "Git no está disponible en este entorno.")
    }

    override fun stashList(): List<GitStash> {
        val git = open() ?: return emptyList()
        return runCatching {
            git.stashList().call()
                .sortedByDescending { it.commitTime }
                .mapIndexed { index, commit ->
                    GitStash(id = index, message = commit.shortMessage)
                }
        }.getOrDefault(emptyList())
    }

    override fun stashRestore(stashId: Int): GitOpResult {
        return run({ git ->
            val commit = git.stashList().call()
                .sortedByDescending { it.commitTime }
                .getOrNull(stashId)
            if (commit == null) return@run GitOpResult.fail("Stash no encontrado.")
            git.stashApply().setStashRef(commit.name).call()
            GitOpResult.ok("Cambios del stash restaurados.")
        }, "Git no está disponible en este entorno.")
    }

    override fun stashDrop(stashId: Int): GitOpResult {
        return run({ git ->
            git.stashDrop().setStashRef(stashId).call()
            GitOpResult.ok("Stash eliminado.")
        }, "Git no está disponible en este entorno.")
    }

    override fun remotes(): List<GitRemote> {
        val git = open() ?: return emptyList()
        return runCatching {
            git.remoteList().call().map { remote ->
                val url = remote.urIs.firstOrNull()?.toString().orEmpty()
                GitRemote(name = remote.name, url = url)
            }
        }.getOrDefault(emptyList())
    }

    override fun addRemote(name: String, url: String): GitOpResult {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty()) return GitOpResult.fail("Escribe un nombre para el remoto.")
        if (cleanUrl.isEmpty()) return GitOpResult.fail("Escribe la URL del repositorio remoto.")
        return run({ git ->
            git.remoteAdd().setName(cleanName).setUri(URIish(cleanUrl)).call()
            GitOpResult.ok("Repositorio remoto $cleanName conectado.")
        }, "Git no está disponible en este entorno.")
    }

    override fun removeRemote(name: String): GitOpResult {
        return run({ git ->
            val rmCmd = git.remoteRemove()
            rmCmd.setName(name)
            rmCmd.call()
            GitOpResult.ok("Repositorio remoto $name desconectado.")
        }, "Git no está disponible en este entorno.")
    }

    override fun lastCommit(): GitCommitInfo? {
        val git = open() ?: return null
        return runCatching {
            val commit = git.log().setMaxCount(1).call().firstOrNull() ?: return null
            GitCommitInfo(
                shortHash = commit.name.take(7),
                message = commit.shortMessage,
                author = commit.authorIdent.name,
                dateMillis = commit.commitTime * 1000L,
            )
        }.getOrNull()
    }

    override fun refresh() {}

    // ------------------------------------------------------------------ GitHub OAuth (device flow)

    override val githubAvailable: Boolean
        get() = true

    override fun githubDevice(): GitHubDeviceFlow? {
        if (GITHUB_CLIENT_ID == "TU_CLIENT_ID") return null
        val body = post("https://github.com/login/device/code", mapOf(
            "client_id" to GITHUB_CLIENT_ID,
            "scope" to "repo user",
        ))
        val obj = body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (obj == null) return null
        val flow = GitHubDeviceFlow(
            userCode = obj.str("user_code") ?: "",
            verificationUri = obj.str("verification_uri") ?: "",
            intervalSeconds = obj.str("interval")?.toLongOrNull() ?: 5L,
        )
        if (flow.userCode.isBlank() || flow.verificationUri.isBlank()) return null
        _deviceCode = obj.str("device_code")
        _deviceFlow = flow
        return flow
    }

    override fun githubPoll(): GitOpResult {
        val flow = _deviceFlow ?: return GitOpResult.fail("Inicia la conexión a GitHub primero.")
        val code = _deviceCode ?: return GitOpResult.fail("Inicia la conexión a GitHub primero.")
        val raw = runCatching {
            post("https://github.com/login/oauth/access_token", mapOf(
                "client_id" to GITHUB_CLIENT_ID,
                "device_code" to code,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ))
        }.getOrNull()
        val obj = raw?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (obj == null) return GitOpResult.fail("No se pudo contactar a GitHub: $raw".take(120))
        val token = obj.str("access_token")
        if (token != null) {
            val (login, name) = githubUser(token) ?: run {
                _deviceFlow = null
                _deviceCode = null
                return GitOpResult.fail("Token recibido pero no se pudo cargar tu perfil.")
            }
            val props = readSession() ?: Properties()
            props.setProperty("token", token)
            props.setProperty("login", login)
            props.setProperty("name", name ?: login)
            writeSession(props)
            _deviceFlow = null
            _deviceCode = null
            return GitOpResult.ok("Conectado a GitHub como $login.")
        }
        return when (obj.str("error")) {
            "authorization_pending" -> GitOpResult.fail("Aún no confirmas el código en GitHub.")
            "slow_down" -> GitOpResult.fail("Espera un momento e inténtalo de nuevo.")
            "expired_token" -> {
                _deviceFlow = null
                _deviceCode = null
                GitOpResult.fail("El código expiró. Vuelve a iniciar la conexión.")
            }
            "access_denied" -> {
                _deviceFlow = null
                _deviceCode = null
                GitOpResult.fail("Autorización rechazada en GitHub.")
            }
            else -> GitOpResult.fail("GitHub aún no confirma el código: ${obj.str("error_message") ?: "esperando…"}")
        }
    }

    override fun githubSession(): GitHubSession? {
        val props = readSession() ?: return null
        val login = props.getProperty("login") ?: return null
        return GitHubSession(
            login = login,
            name = props.getProperty("name") ?: login,
            repoFullName = props.getProperty("repo"),
            repoDefaultBranch = props.getProperty("defaultBranch"),
        )
    }

    override fun githubRepos(): List<GitHubRepo> {
        val token = sessionToken() ?: return emptyList()
        val raw = get("https://api.github.com/user/repos?per_page=100&affiliation=owner,collaborator,organization_member", token)
        val arr = raw?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            val fullName = obj.str("full_name") ?: return@mapNotNull null
            GitHubRepo(fullName = fullName, defaultBranch = obj.str("default_branch") ?: "main")
        }
    }

    override fun githubConnectRepo(fullName: String, defaultBranch: String): GitOpResult {
        val props = readSession() ?: return GitOpResult.fail("Conéctate a GitHub primero.")
        props.setProperty("repo", fullName)
        props.setProperty("defaultBranch", defaultBranch)
        if (!writeSession(props)) return GitOpResult.fail("No se pudo guardar la selección del repositorio.")
        val url = "https://github.com/$fullName.git"
        val git = open()
        if (git != null) {
            runCatching {
                val origin = git.remoteList().call().find { it.name == "origin" }
                if (origin != null) {
                    val rmCmd = git.remoteRemove()
                    rmCmd.setName("origin")
                    rmCmd.call()
                }
                val addCmd = git.remoteAdd()
                addCmd.setName("origin")
                addCmd.setUri(URIish(url))
                addCmd.call()
            }.onFailure {
                return GitOpResult.fail("Repositorio conectado, pero no se pudo configurar el remoto: ${it.message?.take(120)}")
            }
        }
        return GitOpResult.ok("Repositorio $fullName seleccionado.")
    }

    override fun githubDisconnect(): GitOpResult {
        clearSession()
        val git = open()
        if (git != null) {
            runCatching<Unit> {
                val cmd = git.remoteRemove()
                cmd.setName("origin")
                cmd.call()
            }
        }
        return GitOpResult.ok("Desconectado de GitHub.")
    }

    override fun publish(mode: PublishMode, branch: String, message: String): GitOpResult {
        val props = readSession() ?: return GitOpResult.fail("Conéctate a GitHub primero.")
        val repo = props.getProperty("repo")
        val token = props.getProperty("token")
        if (repo == null || token == null) return GitOpResult.fail("Elige un repositorio de GitHub primero.")
        val target = branch.trim().ifEmpty { props.getProperty("defaultBranch") ?: "main" }

        if (!available) {
            val initResult = init()
            if (!initResult.success) return initResult
        }

        return run({ git ->
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()

            val status = git.status().call()
            val hasStaged = status.added.isNotEmpty() || status.changed.isNotEmpty() ||
                status.removed.isNotEmpty() || status.modified.isNotEmpty() || status.missing.isNotEmpty()
            val hasHead = git.repository.resolve(Constants.HEAD) != null
            if (!hasStaged && !hasHead) return@run GitOpResult.fail("No hay contenido que subir a $repo.")

            val current = branch() ?: "main"
            if (!hasStaged && mode == PublishMode.CHANGES_ONLY) {
                return@run GitOpResult.fail("No hay cambios para subir.")
            }
            if (hasStaged) {
                val finalMessage = message.trim().ifEmpty {
                    if (hasHead) "Update from CodeAssist" else "Initial commit"
                }
                git.commit().setMessage(finalMessage).call()
            }
            pushTo(git, current, target, repo, token)
        }, "No se pudo publicar el proyecto.")
    }

    private fun pushTo(git: Git, local: String, target: String, repo: String, token: String): GitOpResult {
        return runCatching {
            val url = "https://github.com/$repo.git"
            val origin = git.remoteList().call().find { it.name == "origin" }
            if (origin == null || origin.urIs.firstOrNull()?.toString() != url) {
                if (origin != null) {
                    val rmCmd = git.remoteRemove()
                    rmCmd.setName("origin")
                    rmCmd.call()
                }
                val addCmd = git.remoteAdd()
                addCmd.setName("origin")
                addCmd.setUri(URIish(url))
                addCmd.call()
            }
            git.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/$local:refs/heads/$target"))
                .setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", token))
                .call()
            GitOpResult.ok("Proyecto subido a github.com/$repo (rama $target).")
        }.getOrElse {
            GitOpResult.fail("No se pudo subir a GitHub: ${it.message?.lineSequence()?.firstOrNull()?.take(120) ?: "error"}")
        }
    }

    private fun githubUser(token: String): Pair<String, String?>? {
        val raw = get("https://api.github.com/user", token)
        val obj = raw?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val login = obj.str("login") ?: return null
        return login to obj.str("name")
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content

    private fun post(url: String, form: Map<String, String>): String? =
        post(url, form, null)

    private fun post(url: String, form: Map<String, String>, token: String?): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        val body = form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    private fun get(url: String, token: String?): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.use { it.readText() }
    }.getOrNull()
}
