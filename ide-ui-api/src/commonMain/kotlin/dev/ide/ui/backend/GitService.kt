package dev.ide.ui.backend

/** A working-tree change: [path] with [status], [staged] when prepared in the index. */
data class GitFile(
    val path: String,
    val status: GitStatus,
    /** True when the change is staged in the index (prepared for the next commit). */
    val staged: Boolean,
)

/**
 * Structured outcome of a git operation: human-readable [message] plus — for merge/pull — the [conflicts]
 * (file paths) when the operation hit a merge conflict.
 */
data class GitOpResult(
    val success: Boolean,
    val message: String,
    val conflicts: List<String> = emptyList(),
) {
    companion object {
        fun ok(message: String) = GitOpResult(success = true, message = message)
        fun fail(message: String) = GitOpResult(success = false, message = message)
    }
}

/** A git branch: [name] (local `main` or remote `origin/main`), [isCurrent] and whether it's a remote ref. */
data class GitBranch(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean,
)

/** One stash entry: [id] matches git's `stash@{id}` numbering (0 = most recent). */
data class GitStash(val id: Int, val message: String)

/** A configured remote: [name] (e.g. `origin`) and its [url]. */
data class GitRemote(val name: String, val url: String)

/** The repository's HEAD commit: short hash, subject, author and timestamp. */
data class GitCommitInfo(
    val shortHash: String,
    val message: String,
    val author: String,
    val dateMillis: Long,
)

/** GitHub device-flow step 1: the code the user enters at [verificationUri]. */
data class GitHubDeviceFlow(
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Long,
)

/** A GitHub repository the connected account can push to. */
data class GitHubRepo(
    val fullName: String,
    val defaultBranch: String,
)

/** The active GitHub session: who is connected and which repo is selected (null until one is picked). */
data class GitHubSession(
    val login: String,
    val name: String,
    val repoFullName: String? = null,
    val repoDefaultBranch: String? = null,
)

/** What a "subir al remoto" (publish) operation should upload. */
enum class PublishMode {
    /** Init the repo (if needed), commit EVERYTHING in the workspace and push. */
    FULL_PROJECT,
    /** Commit and push only the working-tree changes. */
    CHANGES_ONLY,
}

/**
 * Source-control integration surfaced by the Source-control panel. Operations run against the workspace's
 * git repository (via JGit on Android — there is no `git` CLI on device). [NoopGitService] is the default so
 * backends that don't wire a real implementation keep compiling and degrade gracefully.
 */
interface GitService {
    /** False when git isn't wired or the workspace isn't inside a repository. */
    val available: Boolean

    /** Current branch name, or null when unavailable. */
    fun branch(): String?

    /** Working-tree changes, split into staged (prepared) and unstaged entries. */
    fun status(): List<GitFile>

    /** Unified diff for [path]; [staged] selects the index (prepared) diff. */
    fun diff(path: String, staged: Boolean): String

    fun stage(paths: List<String>)
    fun unstage(paths: List<String>)

    /** Create a commit with [message]; reports success/error. */
    fun commit(message: String): GitOpResult

    /** Push the current branch to [remote]; reports success/error. */
    fun push(remote: String = "origin"): GitOpResult

    /** Pull [remote] into the current branch; [conflicts] carries the affected files when it conflicted. */
    fun pull(remote: String = "origin"): GitOpResult

    /** Fetch [remote] refs without touching the working tree; reports what changed. */
    fun fetch(remote: String = "origin"): GitOpResult

    /** Merge [branch] into the current branch; [conflicts] carries the affected files when it conflicted. */
    fun merge(branch: String): GitOpResult

    /** All local + remote branches, current one flagged. */
    fun branches(): List<GitBranch>

    fun createBranch(name: String): GitOpResult
    fun checkoutBranch(name: String): GitOpResult
    fun deleteBranch(name: String): GitOpResult

    fun stashSave(message: String): GitOpResult
    fun stashList(): List<GitStash>
    fun stashRestore(stashId: Int): GitOpResult
    fun stashDrop(stashId: Int): GitOpResult

    /** Configured remotes (e.g. `origin`). */
    fun remotes(): List<GitRemote>

    fun addRemote(name: String, url: String): GitOpResult
    fun removeRemote(name: String): GitOpResult

    /** HEAD commit when the repo has at least one, else null. */
    fun lastCommit(): GitCommitInfo?

    /** Initialize a git repository at the workspace root (no-op if one already exists). */
    fun init(): GitOpResult

    /**
     * GitHub login (OAuth device flow):
     * [githubDevice] starts the flow and returns the code to show; poll [githubPoll] (every `intervalSeconds`)
     * until the user approves. Once it succeeds, [githubSession] reflects the logged-in account.
     */
    val githubAvailable: Boolean
    fun githubDevice(): GitHubDeviceFlow?
    fun githubPoll(): GitOpResult
    fun githubSession(): GitHubSession?
    fun githubRepos(): List<GitHubRepo>
    fun githubConnectRepo(fullName: String, defaultBranch: String): GitOpResult

    /**
     * Creates a new repository named [name] on the connected GitHub account ([isPrivate] controls
     * visibility), then connects it the same way [githubConnectRepo] does.
     */
    fun githubCreateRepo(name: String, isPrivate: Boolean): GitOpResult

    /** The workspace folder's name, used to suggest a repository name when creating one. */
    fun projectName(): String

    fun githubDisconnect(): GitOpResult

    /**
     * Forgets the currently selected GitHub repo (keeps the account session) and removes the locally
     * configured `origin` remote, if any, so the repo picker is shown again.
     */
    fun githubClearRepo(): GitOpResult

    /**
     * Publish the workspace to the connected GitHub repo: [FULL_PROJECT] initializes the repo (if needed),
     * stages everything and creates an initial commit; [CHANGES_ONLY] commits only the working-tree changes.
     * [branch] is the remote target branch, [message] overrides the auto commit message.
     */
    fun publish(mode: PublishMode, branch: String, message: String): GitOpResult

    /** Drop any cached state (called after operations that change the tree). */
    fun refresh()
}

object NoopGitService : GitService {
    override val available: Boolean get() = false
    override fun branch(): String? = null
    override fun status(): List<GitFile> = emptyList()
    override fun diff(path: String, staged: Boolean): String = ""
    override fun stage(paths: List<String>) {}
    override fun unstage(paths: List<String>) {}
    override fun commit(message: String): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun push(remote: String): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun pull(remote: String): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun fetch(remote: String): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun merge(branch: String): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun branches(): List<GitBranch> = emptyList()
    override fun createBranch(name: String): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun checkoutBranch(name: String): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun deleteBranch(name: String): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun stashSave(message: String): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun stashList(): List<GitStash> = emptyList()
    override fun stashRestore(stashId: Int): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun stashDrop(stashId: Int): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun remotes(): List<GitRemote> = emptyList()
    override fun addRemote(name: String, url: String): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun removeRemote(name: String): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override fun lastCommit(): GitCommitInfo? = null
    override fun init(): GitOpResult = GitOpResult.fail("Git no está disponible en este entorno.")
    override val githubAvailable: Boolean get() = false
    override fun githubDevice(): GitHubDeviceFlow? = null
    override fun githubPoll(): GitOpResult = GitOpResult.fail("GitHub no está configurado en este entorno.")
    override fun githubSession(): GitHubSession? = null
    override fun githubRepos(): List<GitHubRepo> = emptyList()
    override fun githubConnectRepo(fullName: String, defaultBranch: String): GitOpResult = GitOpResult.fail("GitHub no está configurado en este entorno.")
    override fun githubCreateRepo(name: String, isPrivate: Boolean): GitOpResult = GitOpResult.fail("GitHub no está configurado en este entorno.")
    override fun projectName(): String = "proyecto"
    override fun githubDisconnect(): GitOpResult = GitOpResult.fail("GitHub no está configurado en este entorno.")
    override fun githubClearRepo(): GitOpResult = GitOpResult.fail("GitHub no está configurado en este entorno.")
    override fun publish(mode: PublishMode, branch: String, message: String): GitOpResult = GitOpResult.fail("GitHub no está configurado en este entorno.")
    override fun refresh() {}
}
