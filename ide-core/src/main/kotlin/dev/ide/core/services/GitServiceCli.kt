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
import org.eclipse.jgit.api.MergeResult.MergeStatus
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.transport.RefSpec
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
 * Integración con Git respaldada por JGit.
 *
 * JGit se ejecuta directamente sobre Java/Android y no requiere
 * tener instalado el ejecutable de Git en el sistema.
 */
internal class GitServiceCli(
    private val ctx: EngineContext,
) : GitService {

    private companion object {
        const val GITHUB_CLIENT_ID = "Ov23liR4ZOr83z3uusM7"
        const val SESSION_FILE = "github-session.properties"
    }

    private val root: File
        get() = ctx.workspaceRoot.toFile()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val sessionFile: File
        get() {
            val cached = ctx.sharedCachesRoot

            return if (cached != null) {
                cached.resolve(SESSION_FILE).toFile()
            } else {
                File(
                    File(
                        System.getProperty("java.io.tmpdir") ?: ".",
                    ),
                    SESSION_FILE,
                )
            }
        }

    private var deviceCode: String? = null
    private var deviceFlow: GitHubDeviceFlow? = null

    override val available: Boolean
        get() = File(root, ".git").exists()

    override val githubAvailable: Boolean
        get() = GITHUB_CLIENT_ID != "TU_CLIENT_ID"

    private fun open(): Git? {
        return runCatching {
            Git.open(root)
        }.getOrNull()
    }

    private fun run(
        block: (Git) -> GitOpResult,
        noRepo: String,
    ): GitOpResult {
        val git = open()
            ?: return GitOpResult.fail(noRepo)

        return try {
            block(git)
        } catch (error: Throwable) {
            GitOpResult.fail(
                "No se pudo completar la operación: " +
                    "${error.message?.lineSequence()?.firstOrNull()?.take(120) ?: "error"}",
            )
        } finally {
            runCatching {
                git.close()
            }
        }
    }

    private fun readSession(): Properties? {
        return runCatching {
            val file = sessionFile

            if (!file.isFile) {
                return null
            }

            Properties().apply {
                file.inputStream().use(::load)
            }
        }.getOrNull()
    }

    private fun writeSession(
        props: Properties,
    ): Boolean {
        return runCatching {
            sessionFile.parentFile?.mkdirs()

            sessionFile.outputStream().use {
                props.store(
                    it,
                    "CodeAssist GitHub session",
                )
            }
        }.isSuccess
    }

    private fun clearSession() {
        runCatching {
            sessionFile.delete()
        }

        deviceCode = null
        deviceFlow = null
    }

    private fun sessionToken(): String? {
        return readSession()
            ?.getProperty("token")
            ?.takeIf {
                it.isNotBlank() && it != GITHUB_CLIENT_ID
            }
    }

    private fun githubCredentials():
        UsernamePasswordCredentialsProvider? {
        val token = sessionToken() ?: return null

        return UsernamePasswordCredentialsProvider(
            "x-access-token",
            token,
        )
    }

    override fun init(): GitOpResult {
        if (available) {
            return GitOpResult.ok(
                "El repositorio ya está inicializado.",
            )
        }

        return runCatching {
            root.mkdirs()

            val git = Git.init()
                .setDirectory(root)
                .call()

            git.close()

            GitOpResult.ok(
                "Repositorio git inicializado en ${root.absolutePath}.",
            )
        }.getOrElse { error ->
            GitOpResult.fail(
                "No se pudo inicializar el repositorio: " +
                    "${error.message?.lineSequence()?.firstOrNull()?.take(120) ?: "error"}",
            )
        }
    }

    override fun branch(): String? {
        val git = open() ?: return null

        return try {
            val fullBranch = git.repository.fullBranch

            if (fullBranch != null &&
                fullBranch.startsWith(Constants.R_HEADS)
            ) {
                fullBranch.substring(Constants.R_HEADS.length)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching {
                git.close()
            }
        }
    }

    override fun status(): List<GitFile> {
        val git = open() ?: return emptyList()

        return try {
            val status = git.status().call()
            val files = mutableListOf<GitFile>()

            status.added.forEach {
                files += GitFile(
                    it,
                    GitStatus.Added,
                    staged = true,
                )
            }

            status.changed.forEach {
                files += GitFile(
                    it,
                    GitStatus.Modified,
                    staged = true,
                )
            }

            status.removed.forEach {
                files += GitFile(
                    it,
                    GitStatus.Deleted,
                    staged = true,
                )
            }

            status.modified.forEach {
                files += GitFile(
                    it,
                    GitStatus.Modified,
                    staged = false,
                )
            }

            status.missing.forEach {
                files += GitFile(
                    it,
                    GitStatus.Deleted,
                    staged = false,
                )
            }

            status.untracked.forEach {
                files += GitFile(
                    it,
                    GitStatus.Untracked,
                    staged = false,
                )
            }

            status.untrackedFolders.forEach {
                files += GitFile(
                    it,
                    GitStatus.Untracked,
                    staged = false,
                )
            }

            files
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching {
                git.close()
            }
        }
    }

    override fun diff(
        path: String,
        staged: Boolean,
    ): String {
        val git = open() ?: return ""

        return try {
            val command = git.diff()

            if (staged) {
                command.setCached(true)
            }

            command.setPathFilter(
                org.eclipse.jgit.treewalk.filter.PathFilter.create(path),
            )

            val entries: List<DiffEntry> = command.call()
            val output = ByteArrayOutputStream()

            DiffFormatter(output).apply {
                setRepository(git.repository)
                format(entries)
                flush()
            }

            output.toString(StandardCharsets.UTF_8.name())
        } catch (_: Throwable) {
            ""
        } finally {
            runCatching {
                git.close()
            }
        }
    }

    override fun stage(paths: List<String>) {
        if (paths.isEmpty()) return

        val git = open() ?: return

        try {
            val command = git.add()

            paths.forEach {
                command.addFilepattern(it)
            }

            command.call()
        } catch (_: Throwable) {
            // La interfaz informa el estado mediante status().
        } finally {
            runCatching {
                git.close()
            }
        }
    }

    override fun unstage(paths: List<String>) {
        if (paths.isEmpty()) return

        val git = open() ?: return

        try {
            val command = git.reset()

            paths.forEach {
                command.addPath(it)
            }

            command.call()
        } catch (_: Throwable) {
            // La interfaz informa el estado mediante status().
        } finally {
            runCatching {
                git.close()
            }
        }
    }

    override fun commit(
        message: String,
    ): GitOpResult {
        val cleanMessage = message.trim()

        if (cleanMessage.isEmpty()) {
            return GitOpResult.fail(
                "Escribe un mensaje de commit.",
            )
        }

        return run({ git ->
            val commit = git.commit()
                .setMessage(cleanMessage)
                .call()

            GitOpResult.ok(
                "Commit ${commit.name.take(7)} creado en " +
                    "${branch() ?: "la rama actual"}.",
            )
        }, "Git no está disponible en este entorno.")
    }

    override fun push(
        remote: String,
    ): GitOpResult {
        return run({ git ->
            val currentBranch = branch()
                ?: return@run GitOpResult.fail(
                    "No se pudo determinar la rama actual.",
                )

            val command = git.push()
                .setRemote(remote)

            githubCredentials()?.let {
                command.setCredentialsProvider(it)
            }

            command.call()

            GitOpResult.ok(
                "Push a $remote/$currentBranch completado.",
            )
        }, "Git no está disponible en este entorno.")
    }

    override fun pull(
        remote: String,
    ): GitOpResult {
        return run({ git ->
            val command = git.pull()
                .setRemote(remote)
                .setRebase(false)

            githubCredentials()?.let {
                command.setCredentialsProvider(it)
            }

            val result = command.call()
            val mergeResult = result.mergeResult

            val conflicts = mergeResult
                ?.conflicts
                ?.keys
                ?.toList()
                .orEmpty()

            when {
                result.isSuccessful -> {
                    GitOpResult.ok(
                        "Pull completado desde $remote.",
                    )
                }

                conflicts.isNotEmpty() -> {
                    GitOpResult(
                        success = false,
                        message = "Pull completado con conflictos.",
                        conflicts = conflicts,
                    )
                }

                else -> {
                    GitOpResult.fail(
                        "No se pudo hacer pull desde $remote: " +
                            "revisa la conexión o los cambios locales pendientes.",
                    )
                }
            }
        }, "Git no está disponible en este entorno.")
    }

    override fun fetch(
        remote: String,
    ): GitOpResult {
        return run({ git ->
            val command = git.fetch()
                .setRemote(remote)
                .setRemoveDeletedRefs(true)

            githubCredentials()?.let {
                command.setCredentialsProvider(it)
            }

            val result = command.call()

            val updated = result.trackingRefUpdates.filter { update ->
                when (update.result) {
                    RefUpdate.Result.NEW,
                    RefUpdate.Result.FAST_FORWARD,
                    RefUpdate.Result.FORCED -> true

                    else -> false
                }
            }

            val failed = result.trackingRefUpdates.filter { update ->
                update.result == RefUpdate.Result.REJECTED ||
                    update.result == RefUpdate.Result.LOCK_FAILURE ||
                    update.result == RefUpdate.Result.IO_FAILURE ||
                    update.result == RefUpdate.Result.NOT_ATTEMPTED
            }

            when {
                failed.isNotEmpty() -> {
                    GitOpResult.fail(
                        "Fetch completado con errores en " +
                            "${failed.size} referencia(s).",
                    )
                }

                updated.isEmpty() -> {
                    GitOpResult.ok(
                        "Fetch completado: sin cambios nuevos en $remote.",
                    )
                }

                else -> {
                    GitOpResult.ok(
                        "Fetch completado: ${updated.size} rama(s) " +
                            "actualizada(s) desde $remote.",
                    )
                }
            }
        }, "Git no está disponible en este entorno.")
    }

    override fun merge(
        branch: String,
    ): GitOpResult {
        return run({ git ->
            val localRef = "refs/heads/$branch"
            val remoteRef = "refs/remotes/$branch"

            val refName = when {
                git.repository.findRef(localRef) != null -> localRef
                git.repository.findRef(remoteRef) != null -> remoteRef
                else -> {
                    return@run GitOpResult.fail(
                        "No se encontró la rama $branch.",
                    )
                }
            }

            val ref = git.repository.findRef(refName)
                ?: return@run GitOpResult.fail(
                    "No se encontró la rama $branch.",
                )

            val result = git.merge()
                .include(ref)
                .call()

            when (result.mergeStatus) {
                MergeStatus.CONFLICTING -> {
                    val conflicts = result.conflicts
                        ?.keys
                        ?.toList()
                        .orEmpty()

                    GitOpResult(
                        success = false,
                        message = "Conflicto al fusionar $branch con " +
                            "${branch() ?: "la rama actual"}.",
                        conflicts = conflicts,
                    )
                }

                MergeStatus.ALREADY_UP_TO_DATE -> {
                    GitOpResult.ok(
                        "Ya está actualizada con $branch.",
                    )
                }

                else -> {
                    if (result.mergeStatus.isSuccessful) {
                        GitOpResult.ok(
                            "Rama $branch fusionada en " +
                                "${branch() ?: "la rama actual"}.",
                        )
                    } else {
                        GitOpResult.fail(
                            "No se pudo fusionar $branch: " +
                                result.mergeStatus,
                        )
                    }
                }
            }
        }, "Git no está disponible en este entorno.")
    }

    override fun branches(): List<GitBranch> {
        val git = open() ?: return emptyList()

        return try {
            val currentBranch = branch()

            git.branchList()
                .setListMode(ListMode.ALL)
                .call()
                .map { ref ->
                    val isRemote = ref.name.startsWith(
                        Constants.R_REMOTES,
                    )

                    val name = if (isRemote) {
                        ref.name.substring(
                            Constants.R_REMOTES.length,
                        )
                    } else {
                        ref.name.substring(
                            Constants.R_HEADS.length,
                        )
                    }

                    GitBranch(
                        name = name,
                        isCurrent = !isRemote && name == currentBranch,
                        isRemote = isRemote,
                    )
                }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching {
                git.close()
            }
        }
    }

    override fun createBranch(
        name: String,
    ): GitOpResult {
        val cleanName = name.trim()

        if (cleanName.isEmpty()) {
            return GitOpResult.fail(
                "Escribe un nombre para la rama.",
            )
        }

        return run({ git ->
            if (git.repository.findRef("refs/heads/$cleanName") != null) {
                return@run GitOpResult.fail(
                    "La rama $cleanName ya existe.",
                )
            }

            git.branchCreate()
                .setName(cleanName)
                .call()

            GitOpResult.ok(
                "Rama $cleanName creada correctamente.",
            )
        }, "Git no está disponible en este entorno.")
    }

    override fun checkoutBranch(
        name: String,
    ): GitOpResult {
        val cleanName = name.trim()

        if (cleanName.isEmpty()) {
            return GitOpResult.fail(
                "Elige una rama.",
            )
        }

        return run({ git ->
            val isRemote = cleanName.startsWith("origin/")

            if (!isRemote) {
                git.checkout()
                    .setName(cleanName)
                    .call()
            } else {
                val localName = cleanName.removePrefix("origin/")

                git.checkout()
                    .setCreateBranch(true)
                    .setName(localName)
                    .setUpstreamMode(SetupUpstreamMode.TRACK)
                    .setStartPoint(cleanName)
                    .call()
            }

            GitOpResult.ok(
                "Cambiado a la rama $cleanName.",
            )
        }, "Git no está disponible en este entorno.")
    }

    override fun deleteBranch(
        name: String,
    ): GitOpResult {
        val cleanName = name.trim()

        if (cleanName.isEmpty()) {
            return GitOpResult.fail(
                "Especifica la rama que deseas eliminar.",
            )
        }

        return run({ git ->
            git.branchDelete()
                .setBranchNames(cleanName)
                .setForce(true)
                .call()

            GitOpResult.ok(
                "Rama $cleanName eliminada.",
            )
        }, "Git no está disponible en este entorno.")
    }

    override fun stashSave(
        message: String,
    ): GitOpResult {
        return run({ git ->
            val commit = if (message.isBlank()) {
                git.stashCreate().call()
            } else {
                git.stashCreate()
                    .setWorkingDirectoryMessage(message.trim())
                    .call()
            }

            if (commit == null) {
                GitOpResult.fail(
                    "No hay cambios para guardar en el stash.",
                )
            } else {
                GitOpResult.ok(
                    "Cambios guardados en el stash.",
                )
            }
        }, "Git no está disponible en este entorno.")
    }

    override fun stashList(): List<GitStash> {
        val git = open() ?: return emptyList()

        return try {
            git.stashList()
                .call()
                .sortedByDescending { it.commitTime }
                .mapIndexed { index, commit ->
                    GitStash(
                        id = index,
                        message = commit.shortMessage,
                    )
                }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching {
                git.close()
            }
        }
    }

    override fun stashRestore(
        stashId: Int,
    ): GitOpResult {
        return run({ git ->
            val stash = git.stashList()
                .call()
                .sortedByDescending { it.commitTime }
                .getOrNull(stashId)
                ?: return@run GitOpResult.fail(
                    "Stash no encontrado.",
                )

            git.stashApply()
                .setStashRef(stash.name)
                .call()

            GitOpResult.ok(
                "Cambios del stash restaurados.",
            )
        }, "Git no está disponible en este entorno.")
    }

    override fun stashDrop(
        stashId: Int,
    ): GitOpResult {
        return run({ git ->
            val stashes = git.stashList()
                .call()
                .sortedByDescending { it.commitTime }

            if (stashId !in stashes.indices) {
                return@run GitOpResult.fail(
                    "Stash no encontrado.",
                )
            }

            git.stashDrop()
                .setStashRef(stashId)
                .call()

            GitOpResult.ok(
                "Stash eliminado.",
            )
        }, "Git no está disponible en este entorno.")
    }

    override fun remotes(): List<GitRemote> {
        val git = open() ?: return emptyList()

        return try {
            git.remoteList()
                .call()
                .map { remote ->
                    GitRemote(
                        name = remote.name,
                        url = remote.urIs
                            .firstOrNull()
                            ?.toString()
                            .orEmpty(),
                    )
                }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching {
                git.close()
            }
        }
    }

    override fun addRemote(
        name: String,
        url: String,
    ): GitOpResult {
        val cleanName = name.trim()
        val cleanUrl = url.trim()

        if (cleanName.isEmpty()) {
            return GitOpResult.fail(
                "Escribe un nombre para el remoto.",
            )
        }

        if (cleanUrl.isEmpty()) {
            return GitOpResult.fail(
                "Escribe la URL del repositorio remoto.",
            )
        }

        return run({ git ->
            git.remoteAdd()
                .setName(cleanName)
                .setUri(URIish(cleanUrl))
                .call()

            GitOpResult.ok(
                "Repositorio remoto $cleanName conectado.",
            )
        }, "Git no está disponible en este entorno.")
    }

    override fun removeRemote(
        name: String,
    ): GitOpResult {
        val cleanName = name.trim()

        if (cleanName.isEmpty()) {
            return GitOpResult.fail(
                "Especifica el remoto que deseas eliminar.",
            )
        }

        return run({ git ->
            git.remoteRemove()
                .setRemoteName(cleanName)
                .call()

            GitOpResult.ok(
                "Repositorio remoto $cleanName desconectado.",
            )
        }, "Git no está disponible en este entorno.")
    }

    override fun lastCommit(): GitCommitInfo? {
        val git = open() ?: return null

        return try {
            val commit = git.log()
                .setMaxCount(1)
                .call()
                .firstOrNull()
                ?: return null

            GitCommitInfo(
                shortHash = commit.name.take(7),
                message = commit.shortMessage,
                author = commit.authorIdent.name,
                dateMillis = commit.commitTime * 1000L,
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching {
                git.close()
            }
        }
    }

    override fun projectName(): String {
        val raw = root.name.ifBlank { "proyecto" }

        val sanitized = raw
            .replace(Regex("[^A-Za-z0-9_.-]"), "-")
            .trim('-')

        return sanitized.ifBlank { "proyecto" }
    }

    override fun refresh() {
        // No requiere estado interno adicional.
    }

    // ---------------------------------------------------------------------
    // GitHub OAuth Device Flow
    // ---------------------------------------------------------------------

    override fun githubDevice(): GitHubDeviceFlow? {
        if (!githubAvailable) {
            return null
        }

        val body = post(
            "https://github.com/login/device/code",
            mapOf(
                "client_id" to GITHUB_CLIENT_ID,
                "scope" to "repo user",
            ),
        ) ?: return null

        val objectValue = runCatching {
            json.parseToJsonElement(body).jsonObject
        }.getOrNull() ?: return null

        val flow = GitHubDeviceFlow(
            userCode = objectValue.value("user_code").orEmpty(),
            verificationUri = objectValue
                .value("verification_uri")
                .orEmpty(),
            intervalSeconds = objectValue
                .value("interval")
                ?.toLongOrNull()
                ?: 5L,
        )

        if (flow.userCode.isBlank() ||
            flow.verificationUri.isBlank()
        ) {
            return null
        }

        deviceCode = objectValue.value("device_code")
        deviceFlow = flow

        return flow
    }

    override fun githubPoll(): GitOpResult {
        if (deviceFlow == null || deviceCode == null) {
            return GitOpResult.fail(
                "Inicia la conexión a GitHub primero.",
            )
        }

        val code = deviceCode ?: return GitOpResult.fail(
            "Inicia la conexión a GitHub primero.",
        )

        val raw = post(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to GITHUB_CLIENT_ID,
                "device_code" to code,
                "grant_type" to
                    "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )

        val objectValue = raw?.let {
            runCatching {
                json.parseToJsonElement(it).jsonObject
            }.getOrNull()
        }

        if (objectValue == null) {
            return GitOpResult.fail(
                "No se pudo contactar a GitHub.",
            )
        }

        val token = objectValue.value("access_token")

        if (token != null) {
            val user = githubUser(token)

            if (user == null) {
                deviceFlow = null
                deviceCode = null

                return GitOpResult.fail(
                    "Token recibido, pero no se pudo cargar tu perfil.",
                )
            }

            val props = readSession() ?: Properties()

            props.setProperty("token", token)
            props.setProperty("login", user.first)
            props.setProperty("name", user.second ?: user.first)

            if (!writeSession(props)) {
                return GitOpResult.fail(
                    "No se pudo guardar la sesión de GitHub.",
                )
            }

            deviceFlow = null
            deviceCode = null

            return GitOpResult.ok(
                "Conectado a GitHub como ${user.first}.",
            )
        }

        return when (objectValue.value("error")) {
            "authorization_pending" -> {
                GitOpResult.fail(
                    "Aún no confirmas el código en GitHub.",
                )
            }

            "slow_down" -> {
                GitOpResult.fail(
                    "Espera un momento e inténtalo de nuevo.",
                )
            }

            "expired_token" -> {
                deviceFlow = null
                deviceCode = null

                GitOpResult.fail(
                    "El código expiró. Vuelve a iniciar la conexión.",
                )
            }

            "access_denied" -> {
                deviceFlow = null
                deviceCode = null

                GitOpResult.fail(
                    "Autorización rechazada en GitHub.",
                )
            }

            else -> {
                GitOpResult.fail(
                    "GitHub aún no confirma el código: " +
                        "${objectValue.value("error_description") ?: "esperando..."}",
                )
            }
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

        val raw = get(
            "https://api.github.com/user/repos" +
                "?per_page=100&affiliation=owner,collaborator,organization_member",
            token,
        ) ?: return emptyList()

        val array = runCatching {
            json.parseToJsonElement(raw).jsonArray
        }.getOrNull() ?: return emptyList()

        return array.mapNotNull { element ->
            val objectValue = element.jsonObject

            val fullName = objectValue.value("full_name")
                ?: return@mapNotNull null

            GitHubRepo(
                fullName = fullName,
                defaultBranch = objectValue
                    .value("default_branch")
                    ?: "main",
            )
        }
    }

    override fun githubConnectRepo(
        fullName: String,
        defaultBranch: String,
    ): GitOpResult {
        val props = readSession()
            ?: return GitOpResult.fail(
                "Conéctate a GitHub primero.",
            )

        val token = props.getProperty("token")
            ?: return GitOpResult.fail(
                "Conéctate a GitHub primero.",
            )

        props.setProperty("repo", fullName)
        props.setProperty("defaultBranch", defaultBranch)

        if (!writeSession(props)) {
            return GitOpResult.fail(
                "No se pudo guardar la selección del repositorio.",
            )
        }

        val url = "https://github.com/$fullName.git"

        return if (!available) {
            cloneInto(
                url = url,
                defaultBranch = defaultBranch,
                token = token,
                fullName = fullName,
            )
        } else {
            pointExistingRepoTo(url, fullName)
        }
    }

    /**
     * Points the already-existing local repository's `origin` to [url] without touching the working
     * tree. Used when the workspace already has commits (e.g. a project created on-device): connecting
     * a GitHub repo here only wires the remote so Push/Pull work; it does not merge histories.
     */
    private fun pointExistingRepoTo(
        url: String,
        fullName: String,
    ): GitOpResult {
        val git = open()
            ?: return GitOpResult.ok(
                "Repositorio $fullName seleccionado.",
            )

        return try {
            val origin = git.remoteList()
                .call()
                .find { it.name == "origin" }

            if (origin != null) {
                git.remoteRemove()
                    .setRemoteName("origin")
                    .call()
            }

            git.remoteAdd()
                .setName("origin")
                .setUri(URIish(url))
                .call()

            GitOpResult.ok(
                "Repositorio $fullName conectado. Usa Pull para traer sus cambios " +
                    "o Push para subir los tuyos.",
            )
        } catch (error: Throwable) {
            GitOpResult.fail(
                "Repositorio conectado, pero no se pudo configurar " +
                    "el remoto: ${error.message?.take(120)}",
            )
        } finally {
            runCatching {
                git.close()
            }
        }
    }

    /**
     * Downloads the actual contents of [fullName] into the workspace root via a real JGit clone
     * (`git clone` equivalent). Only safe to call when the workspace has no repository yet; if the
     * folder already contains files, JGit refuses to clone on top of them, so we check first and
     * report a clear, honest error instead of silently failing or corrupting the project.
     */
    private fun cloneInto(
        url: String,
        defaultBranch: String,
        token: String,
        fullName: String,
    ): GitOpResult {
        val existingFiles = root.listFiles()

        if (root.exists() && !existingFiles.isNullOrEmpty()) {
            return GitOpResult.fail(
                "No se puede clonar $fullName: la carpeta del proyecto ya tiene " +
                    "archivos. Crea un proyecto vacío nuevo para clonar ahí, o usa " +
                    "un repositorio nuevo para este proyecto.",
            )
        }

        return runCatching {
            root.mkdirs()

            Git.cloneRepository()
                .setURI(url)
                .setDirectory(root)
                .setBranch(defaultBranch)
                .setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(
                        "x-access-token",
                        token,
                    ),
                )
                .call()
                .close()

            GitOpResult.ok(
                "Repositorio $fullName clonado correctamente.",
            )
        }.getOrElse { error ->
            GitOpResult.fail(
                "No se pudo clonar $fullName: " +
                    "${error.message?.lineSequence()?.firstOrNull()?.take(150) ?: "error"}",
            )
        }
    }

    override fun githubCreateRepo(
        name: String,
        isPrivate: Boolean,
    ): GitOpResult {
        val cleanName = name.trim()

        if (cleanName.isEmpty()) {
            return GitOpResult.fail(
                "Escribe un nombre para el repositorio.",
            )
        }

        if (!cleanName.matches(Regex("^[A-Za-z0-9_.-]+$"))) {
            return GitOpResult.fail(
                "El nombre solo puede tener letras, números, guiones, puntos y guion bajo.",
            )
        }

        val token = sessionToken()
            ?: return GitOpResult.fail(
                "Conéctate a GitHub primero.",
            )

        val payload = buildString {
            append('{')
            append("\"name\":\"").append(jsonEscape(cleanName)).append("\",")
            append("\"private\":").append(isPrivate)
            append('}')
        }

        val raw = postJson(
            "https://api.github.com/user/repos",
            payload,
            token,
        ) ?: return GitOpResult.fail(
            "No se pudo contactar a GitHub.",
        )

        val objectValue = runCatching {
            json.parseToJsonElement(raw).jsonObject
        }.getOrNull() ?: return GitOpResult.fail(
            "Respuesta inesperada de GitHub.",
        )

        val fullName = objectValue.value("full_name")

        if (fullName == null) {
            val apiMessage = objectValue.value("message")

            return GitOpResult.fail(
                when {
                    apiMessage == null -> "No se pudo crear el repositorio."
                    apiMessage.contains("already exists", ignoreCase = true) ->
                        "Ya existe un repositorio con ese nombre en tu cuenta."
                    else -> apiMessage
                },
            )
        }

        val defaultBranch = objectValue.value("default_branch") ?: "main"

        val props = readSession() ?: Properties()
        props.setProperty("repo", fullName)
        props.setProperty("defaultBranch", defaultBranch)

        if (!writeSession(props)) {
            return GitOpResult.fail(
                "Repositorio creado, pero no se pudo guardar la selección.",
            )
        }

        // The repo we just created is empty (no commits): there is nothing to clone, so we only need
        // to make sure a local repo exists and point it at the new remote — never attempt a real
        // clone here, since that would wrongly refuse to run on a workspace that already has files.
        if (!available) {
            val initResult = init()

            if (!initResult.success) {
                return initResult
            }
        }

        val url = "https://github.com/$fullName.git"
        val wireResult = pointExistingRepoTo(url, fullName)

        return if (wireResult.success) {
            GitOpResult.ok(
                "Repositorio $fullName creado y conectado. Usa Push para subir tu proyecto.",
            )
        } else {
            wireResult
        }
    }

    override fun githubDisconnect(): GitOpResult {
        clearSession()

        val git = open()

        if (git != null) {
            try {
                runCatching {
                    git.remoteRemove()
                        .setRemoteName("origin")
                        .call()
                }
            } finally {
                runCatching {
                    git.close()
                }
            }
        }

        return GitOpResult.ok(
            "Desconectado de GitHub.",
        )
    }

    override fun githubClearRepo(): GitOpResult {
        val props = readSession()
            ?: return GitOpResult.fail(
                "Conéctate a GitHub primero.",
            )

        props.remove("repo")
        props.remove("defaultBranch")

        if (!writeSession(props)) {
            return GitOpResult.fail(
                "No se pudo actualizar la sesión de GitHub.",
            )
        }

        val git = open()

        if (git != null) {
            try {
                val origin = git.remoteList()
                    .call()
                    .find { it.name == "origin" }

                if (origin != null) {
                    git.remoteRemove()
                        .setRemoteName("origin")
                        .call()
                }
            } catch (error: Throwable) {
                return GitOpResult.fail(
                    "No se pudo quitar el remoto anterior: " +
                        "${error.message?.take(120)}",
                )
            } finally {
                runCatching {
                    git.close()
                }
            }
        }

        return GitOpResult.ok(
            "Repositorio olvidado. Elige otro de la lista.",
        )
    }

    override fun publish(
        mode: PublishMode,
        branch: String,
        message: String,
    ): GitOpResult {
        val props = readSession()
            ?: return GitOpResult.fail(
                "Conéctate a GitHub primero.",
            )

        val repo = props.getProperty("repo")
        val token = props.getProperty("token")

        if (repo == null || token == null) {
            return GitOpResult.fail(
                "Elige un repositorio de GitHub primero.",
            )
        }

        val targetBranch = branch.trim().ifEmpty {
            props.getProperty("defaultBranch") ?: "main"
        }

        if (!available) {
            val initResult = init()

            if (!initResult.success) {
                return initResult
            }
        }

        return run({ git ->
            git.add()
                .addFilepattern(".")
                .call()

            git.add()
                .addFilepattern(".")
                .setUpdate(true)
                .call()

            val status = git.status().call()

            val hasStagedChanges =
                status.added.isNotEmpty() ||
                    status.changed.isNotEmpty() ||
                    status.removed.isNotEmpty() ||
                    status.modified.isNotEmpty() ||
                    status.missing.isNotEmpty()

            val hasHead = git.repository.resolve(Constants.HEAD) != null

            if (!hasStagedChanges && !hasHead) {
                return@run GitOpResult.fail(
                    "No hay contenido que subir a $repo.",
                )
            }

            if (!hasStagedChanges &&
                mode == PublishMode.CHANGES_ONLY
            ) {
                return@run GitOpResult.fail(
                    "No hay cambios para subir.",
                )
            }

            if (hasStagedChanges) {
                val finalMessage = message.trim().ifEmpty {
                    if (hasHead) {
                        "Update from CodeAssist"
                    } else {
                        "Initial commit"
                    }
                }

                git.commit()
                    .setMessage(finalMessage)
                    .call()
            }

            val currentBranch = branch().orEmpty().ifBlank {
                "main"
            }

            pushTo(
                git = git,
                local = currentBranch,
                target = targetBranch,
                repo = repo,
                token = token,
            )
        }, "No se pudo publicar el proyecto.")
    }

    private fun pushTo(
        git: Git,
        local: String,
        target: String,
        repo: String,
        token: String,
    ): GitOpResult {
        return runCatching {
            val url = "https://github.com/$repo.git"

            val origin = git.remoteList()
                .call()
                .find { it.name == "origin" }

            val currentUrl = origin
                ?.urIs
                ?.firstOrNull()
                ?.toString()

            if (origin == null || currentUrl != url) {
                if (origin != null) {
                    git.remoteRemove()
                        .setRemoteName("origin")
                        .call()
                }

                git.remoteAdd()
                    .setName("origin")
                    .setUri(URIish(url))
                    .call()
            }

            git.push()
                .setRemote("origin")
                .setRefSpecs(
                    RefSpec(
                        "refs/heads/$local:refs/heads/$target",
                    ),
                )
                .setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(
                        "x-access-token",
                        token,
                    ),
                )
                .call()

            GitOpResult.ok(
                "Proyecto subido a github.com/$repo " +
                    "(rama $target).",
            )
        }.getOrElse { error ->
            GitOpResult.fail(
                "No se pudo subir a GitHub: " +
                    "${error.message?.lineSequence()?.firstOrNull()?.take(120) ?: "error"}",
            )
        }
    }

    private fun githubUser(
        token: String,
    ): Pair<String, String?>? {
        val raw = get(
            "https://api.github.com/user",
            token,
        ) ?: return null

        val objectValue = runCatching {
            json.parseToJsonElement(raw).jsonObject
        }.getOrNull() ?: return null

        val login = objectValue.value("login")
            ?: return null

        return login to objectValue.value("name")
    }

    private fun JsonObject.value(
        key: String,
    ): String? {
        return this[key]
            ?.jsonPrimitive
            ?.content
    }

    private fun post(
        url: String,
        form: Map<String, String>,
    ): String? {
        return post(url, form, null)
    }

    private fun postJson(
        url: String,
        jsonBody: String,
        token: String,
    ): String? {
        return runCatching {
            val connection = URL(url)
                .openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.doOutput = true

            connection.setRequestProperty(
                "Accept",
                "application/vnd.github+json",
            )

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8",
            )

            connection.setRequestProperty(
                "Authorization",
                "Bearer $token",
            )

            connection.outputStream.use {
                it.write(
                    jsonBody.toByteArray(
                        StandardCharsets.UTF_8,
                    ),
                )
            }

            val responseCode = connection.responseCode

            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            stream?.bufferedReader()?.use {
                it.readText()
            }
        }.getOrNull()
    }

    private fun jsonEscape(
        value: String,
    ): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun post(
        url: String,
        form: Map<String, String>,
        token: String?,
    ): String? {
        return runCatching {
            val connection = URL(url)
                .openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.doOutput = true

            connection.setRequestProperty(
                "Accept",
                "application/json",
            )

            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded",
            )

            if (token != null) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token",
                )
            }

            val body = form.entries.joinToString("&") { entry ->
                val key = URLEncoder.encode(
                    entry.key,
                    "UTF-8",
                )

                val value = URLEncoder.encode(
                    entry.value,
                    "UTF-8",
                )

                "$key=$value"
            }

            connection.outputStream.use {
                it.write(
                    body.toByteArray(
                        StandardCharsets.UTF_8,
                    ),
                )
            }

            val responseCode = connection.responseCode

            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            stream?.bufferedReader()?.use {
                it.readText()
            }
        }.getOrNull()
    }

    private fun get(
        url: String,
        token: String?,
    ): String? {
        return runCatching {
            val connection = URL(url)
                .openConnection() as HttpURLConnection

            connection.requestMethod = "GET"

            connection.setRequestProperty(
                "Accept",
                "application/json",
            )

            if (token != null) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token",
                )
            }

            val responseCode = connection.responseCode

            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            stream?.bufferedReader()?.use {
                it.readText()
            }
        }.getOrNull()
    }
}
