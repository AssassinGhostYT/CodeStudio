package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.model.ContentRole
import dev.ide.model.Module
import dev.ide.platform.Disposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlin.io.path.readText

/**
 * WORKSPACE-scoped Dart/Flutter package resolution. Reads each dart-console / flutter-app module's
 * `pubspec.yaml`, resolves its `dependencies:` from the pub.dev API, downloads + extracts each package
 * archive into `<module>/.dart_tool/pub-cache/<name>-<version>/`, and registers the package dirs as SOURCE
 * content roots on the module so the analyzer/index sees the real package sources.
 *
 * Mirrors [DependencyService]'s deferral: kicked off by the host once the project is open
 * ([DependencyBackend.startPendingDependencyResolution]), idempotent (a `.resolved` marker skips an
 * unchanged project), and never blocks creation — the whole walk runs on [pubScope] ([Dispatchers.IO]).
 */
internal class DartPubService(private val ctx: EngineContext) : Disposable {

    private val pubScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val started = AtomicBoolean(false)

    @Volatile
    private var cachedTree: Tree? = null

    /** The module types this resolver serves. */
    private fun isDartModule(module: Module): Boolean =
        module.type.id == "dart-console" || module.type.id == "flutter-app"

    /** Resolve pub.dev packages for every dart/flutter module in the workspace. Idempotent per session. */
    fun resolveDartModules() {
        if (!started.compareAndSet(false, true)) return
        val modules = ctx.modules().filter { isDartModule(it) }
        if (modules.isEmpty()) return
        pubScope.launch {
            for (module in modules) {
                runCatching { resolveModule(module) }
                    .onFailure { log.warn("pub resolve ${module.name} aborted: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    }

    private fun resolveModule(module: Module) {
        val root = ctx.moduleRoot(module) ?: return
        val pubspec = root.resolve("pubspec.yaml")
        if (!Files.isRegularFile(pubspec)) return
        // The Flutter framework is independent of pub.dev deps (it lives in the SDK, not pub.dev) — resolve it
        // first so an app whose pubspec only pins `flutter: sdk: flutter` still gets the framework sources.
        if (module.type.id == "flutter-app") resolveFlutterFramework(module, root)
        val cacheRoot = root.resolve(".dart_tool").resolve("pub-cache")
        val marker = cacheRoot.resolve(".resolved")

        // Fast path: the marker's package set is present on disk and covers every declared dependency.
        val deps = parsePubspecDeps(Files.readString(pubspec))
        if (deps.isEmpty()) return
        val prev = runCatching { marker.readText() }.getOrNull() ?: ""
        val prevMap = parseMarker(prev)
        if (prevMap.isNotEmpty() && prevMap.keys.containsAll(deps.map { it.name })) {
            val allPresent = prevMap.entries.all { (name, version) ->
                Files.isDirectory(cacheRoot.resolve("$name-$version").resolve("lib"))
            }
            if (allPresent) {
                attachSourceRoots(module, root, prevMap.values.map { v -> pkgDir(cacheRoot, prevMap, v) })
                return
            }
        }

        val resolved = LinkedHashMap<String, String>()
        for (dep in deps) {
            val version = resolveVersion(dep.name, dep.constraint) ?: continue
            resolved[dep.name] = version
            val dir = pkgDir(cacheRoot, resolved, version)
            if (!Files.isDirectory(dir.resolve("lib"))) fetchPackage(dep.name, version, cacheRoot, dir)
        }
        if (resolved.isNotEmpty()) {
            attachSourceRoots(module, root, resolved.values.map { v -> pkgDir(cacheRoot, resolved, v) })
            Files.createDirectories(cacheRoot)
            runCatching {
                Files.writeString(marker, resolved.entries.joinToString("\n") { "${it.key}:${it.value}" })
            }
        }
    }

    private fun pkgDir(cacheRoot: Path, packages: Map<String, String>, version: String): Path {
        val name = packages.entries.firstOrNull { it.value == version }?.key ?: return cacheRoot
        return cacheRoot.resolve("$name-$version")
    }

    /** Attach [dirs] as SOURCE content roots (skipping already-registered ones), then publish the library change
     *  so analyzers/index re-sync exactly once. When [requireLibChild] the dir only attaches if it contains a
     *  `lib` directory (a pub.dev package root); when false the dir itself is the source root (a Flutter
     *  framework `lib` dir). */
    private fun attachSourceRoots(module: Module, moduleRoot: Path, dirs: List<Path>, requireLibChild: Boolean = true) {
        var changed = false
        for (pkgDir in dirs) {
            if (requireLibChild && !Files.isDirectory(pkgDir.resolve("lib"))) continue
            if (!Files.isDirectory(pkgDir)) continue
            val rel = runCatching {
                moduleRoot.toAbsolutePath().normalize()
                    .relativize(pkgDir.toAbsolutePath().normalize()).toString().replace('\\', '/')
            }.getOrNull() ?: continue
            if (rel.isEmpty() || rel.startsWith("..")) continue
            if (isRegistered(module, rel)) continue
            if (addSourceRoot(module, rel)) changed = true
        }
        if (changed) {
            ctx.store.save()
            ctx.events.librariesChanged()
        }
    }

    private fun isRegistered(module: Module, relPath: String): Boolean =
        module.sourceSets.firstOrNull { it.name == "main" }
            ?.contentRoots?.any { it.dir.path.replace('\\', '/') == relPath } == true

    /** Register [relPath] (under the module root) as a SOURCE root on the module's `main` source set. */
    private fun addSourceRoot(module: Module, relPath: String): Boolean {
        val project = ctx.projectOf(module) ?: return false
        return runCatching {
            project.beginModification().apply {
                module(module.id).addContentRoot("main", relPath, setOf(ContentRole.SOURCE))
                commit()
            }
            true
        }.getOrDefault(false)
    }

    // ---- Flutter framework (package:flutter & friends live in the Flutter SDK, not on pub.dev) ----

    private fun isFlutterApp(module: Module): Boolean = module.type.id == "flutter-app"

    /** Download the core Flutter framework packages (`flutter`, `flutter_test`, `flutter_localizations`) from
     *  the GitHub `stable` branch into `<module>/.dart_tool/flutter-sdk/` and attach their `lib` dirs as SOURCE
     *  roots — so `import 'package:flutter/material.dart'` (and the Android host's `FlutterActivity`) resolve
     *  without a full Flutter SDK. Idempotent via a marker keyed by the tree sha; only progressed when the
     *  workspace is opened once for the module. */
    private fun resolveFlutterFramework(module: Module, root: Path) {
        val sdkRoot = root.resolve(".dart_tool").resolve("flutter-sdk")
        val marker = sdkRoot.resolve(".resolved")
        if (!isFlutterApp(module)) return

        val tree = fetchFlutterTree() ?: return
        val sha = tree.sha
        if (sha.isNotEmpty()) {
            val prev = runCatching { marker.readText() }.getOrNull() ?: ""
            if (prev == sha && Files.isDirectory(sdkRoot.resolve("packages/flutter").resolve("lib"))) {
                attachFrameworkRoots(module, root, sdkRoot)
                return
            }
        }

        val files = tree.paths.filter { it.endsWith(".dart") && FRAMEWORK_PREFIXES.any { p -> it.startsWith(p) } }
        var written = 0
        for (path in files.take(FRAMEWORK_FILE_CAP)) {
            val target = sdkRoot.resolve(path) // keep the `packages/flutter/…` SDK layout
            if (Files.isRegularFile(target)) { written++; continue } // reuse an existing download
            if (fetchRaw("flutter/flutter", path, target)) written++
        }
        if (written > 0) {
            attachFrameworkRoots(module, root, sdkRoot)
            if (sha.isNotEmpty()) runCatching { Files.createDirectories(sdkRoot); Files.writeString(marker, sha) }
        }
    }

    private fun attachFrameworkRoots(module: Module, moduleRoot: Path, sdkRoot: Path) {
        val roots = FRAMEWORK_PREFIXES.map { p -> sdkRoot.resolve(p) } // → `<sdk>/packages/flutter/lib` etc.
        attachSourceRoots(module, moduleRoot, roots, requireLibChild = false)
    }

    /** The recursive `stable` git tree (path list + sha) of flutter/flutter, cached per session so an app with
     *  several flutter modules makes one GitHub API call, not several. */
    private fun fetchFlutterTree(): Tree? {
        cachedTree?.let { return it }
        val doc = fetchText(FLUTTER_TREE_URL) ?: return null
        val tree = runCatching {
            val root = Json.parseToJsonElement(doc).jsonObject
            Tree(
                sha = root["sha"]?.jsonPrimitive?.content ?: "",
                paths = root["tree"]?.jsonArray?.mapNotNull { el ->
                    val obj = el.jsonObject
                    val type = obj["type"]?.jsonPrimitive?.content
                    val path = obj["path"]?.jsonPrimitive?.content ?: ""
                    if (type == "blob" && path.isNotEmpty()) path else null
                } ?: emptyList(),
            )
        }.getOrNull() ?: return null
        cachedTree = tree
        return tree
    }

    /** Download [path] from the flutter/flutter repo on the `stable` branch to [target]. */
    private fun fetchRaw(repo: String, path: String, target: Path): Boolean = runCatching {
        val url = "https://raw.githubusercontent.com/$repo/stable/$path"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CodeStudio")
        }
        if (conn.responseCode != 200) return@runCatching false
        Files.createDirectories(target.parent)
        conn.inputStream.use { input ->
            Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                .use { out -> input.copyTo(out) }
        }
        true
    }.getOrDefault(false)

    private data class Tree(val sha: String, val paths: List<String>)

    // ---- pubspec.yaml parsing ----

    private data class PubDep(val name: String, val constraint: String)

    /** The `dependencies:` block's `name: constraint` entries; skips map-style entries (`flutter: sdk: flutter`,
     *  git/path/hosted deps) that pub.dev can't resolve from the archive API. */
    private fun parsePubspecDeps(text: String): List<PubDep> {
        val result = ArrayList<PubDep>()
        val lines = text.lines()
        var inDeps = false
        var pending: Pair<String, Int>? = null // a map-style entry's name + indent, until a sibling line
        for (line in lines) {
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            val trimmed = line.trim()
            if (indent == 0) {
                inDeps = trimmed.substringBefore(':').trim() == "dependencies"
                pending = null
                continue
            }
            if (!inDeps) continue
            val m = Regex("^([A-Za-z0-9_]+):\\s*(.*)$").find(trimmed) ?: continue
            val name = m.groupValues[1]
            val value = m.groupValues[2].trim()
            val cur = pending
            if (cur != null && indent > cur.second) continue // still inside the pending map → drop it
            pending = null
            if (value.isEmpty()) {
                pending = name to indent // map-style entry (flutter/git/path/hosted) → skip its whole block
                continue
            }
            if (name == "flutter") continue // the Flutter SDK pin, not a pub.dev package
            result.add(PubDep(name, value))
        }
        return result
    }

    private fun parseMarker(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
        }
        .toMap()

    // ---- pub.dev API ----

    private fun fetchText(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        }
        conn.inputStream.use { it.readBytes().decodeToString() }
    }.getOrNull()

    /** Pick the best archive version for [constraint] from the pub.dev package response. */
    private fun resolveVersion(name: String, constraint: String): String? {
        val doc = fetchText("https://pub.dev/api/packages/$name") ?: return null
        val versions = runCatching {
            Json.parseToJsonElement(doc).jsonObject["versions"]?.jsonArray
        }.getOrNull() ?: return null
        val candidates = versions.mapNotNull { it.jsonObject["version"]?.jsonPrimitive?.content }
        return pickVersion(candidates, constraint)
    }

    private fun pickVersion(candidates: List<String>, constraint: String): String? {
        val stable = candidates.filter { isStable(it) }
        val pool = stable.ifEmpty { candidates }
        return when {
            constraint.isEmpty() || constraint in setOf("any", "*") -> pool.maxWithOrNull(compareVersions)
            constraint.startsWith("^") -> {
                val base = semverOf(constraint.substring(1)) ?: return pool.maxWithOrNull(compareVersions)
                pool.filter { semverOf(it)?.let { v -> v.first == base.first && compareTriples(v, base) >= 0 } == true }
                    .maxWithOrNull(compareVersions)
            }
            constraint.startsWith(">=") -> {
                val bounds = Regex(">=\\s*([0-9][^ ]*)(?:\\s*<\\s*([0-9][^ ]*))?").find(constraint)
                val low = bounds?.groupValues?.get(1)?.let { semverOf(it) }
                    ?: return pool.maxWithOrNull(compareVersions)
                val high = bounds?.groupValues?.get(2)?.let { semverOf(it) }
                pool.filter {
                    semverOf(it)?.let { v ->
                        compareTriples(v, low) >= 0 && (high == null || compareTriples(v, high) < 0)
                    } == true
                }.maxWithOrNull(compareVersions)
            }
            else -> {
                val exact = semverOf(constraint)
                if (exact != null && candidates.any { it == constraint }) constraint
                else pool.maxWithOrNull(compareVersions)
            }
        }
    }

    private fun isStable(v: String): Boolean = !v.contains('-')

    /** Numeric major/minor/patch (prerelease + build metadata dropped), or null when not a plain semver. */
    private fun semverOf(v: String): Triple<Int, Int, Int>? {
        val parts = v.substringBefore('-').substringBefore('+').split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return Triple(nums.getOrElse(0) { 0 }, nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
    }

    private val compareVersions = Comparator<String> { a, b ->
        val x = semverOf(a) ?: return@Comparator 0
        val y = semverOf(b) ?: return@Comparator 0
        compareTriples(x, y).let { if (it != 0) it else compareSequence(a, b) }
    }

    private fun compareTriples(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        val first = a.first.compareTo(b.first)
        if (first != 0) return first
        val second = a.second.compareTo(b.second)
        if (second != 0) return second
        return a.third.compareTo(b.third)
    }

    /** Tiebreak on the original string (stable vs prerelease) after an equal numeric version. */
    private fun compareSequence(a: String, b: String): Int = when {
        isStable(a) && !isStable(b) -> 1
        !isStable(a) && isStable(b) -> -1
        else -> a.compareTo(b)
    }

    // ---- download + extraction ----

    private fun fetchPackage(name: String, version: String, cacheRoot: Path, pkgDir: Path) {
        val archive = cacheRoot.resolve("$name-$version.tar.gz")
        val url = "https://pub.dev/api/archives/$name-$version.tar.gz"
        if (!download(url, archive)) return
        Files.createDirectories(pkgDir)
        extractTarGz(archive, pkgDir)
    }

    /** Resume-capable download to [dest]; returns true when a complete/covered file is present. */
    private fun download(url: String, dest: Path): Boolean = runCatching {
        val existing = if (Files.isRegularFile(dest)) Files.size(dest) else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        val code = conn.responseCode
        if (code == 416) return@runCatching Files.isRegularFile(dest)
        val resume = code == HttpURLConnection.HTTP_PARTIAL && existing > 0
        val opts = if (resume) arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                   else arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        conn.inputStream.use { input ->
            Files.newOutputStream(dest, *opts).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        Files.isRegularFile(dest)
    }.getOrDefault(false)

    /** Extract a pub.dev `.tar.gz` (entries at the archive root) in pure Java. Skips directory entries,
     *  sanitizes paths (no `..` escape), and handles GNU long-name entries. */
    private fun extractTarGz(archive: Path, dest: Path) {
        GZIPInputStream(BufferedInputStream(Files.newInputStream(archive))).use { gz ->
            val header = ByteArray(512)
            var pendingName: String? = null
            while (readBlock(gz, header)) {
                if (header.all { it == 0.toByte() }) break
                val type = header[156]
                val size = tarSize(header)
                if (type == 'L'.code.toByte()) {
                    val nameBytes = ByteArray(size.toInt())
                    if (!readBlock(gz, nameBytes)) break
                    pendingName = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
                    skipFully(gz, padding(size))
                    continue
                }
                val name = pendingName ?: tarName(header)
                pendingName = null
                val isFile = type == 0.toByte() || type == '0'.code.toByte()
                if (isFile && name.isNotEmpty()) {
                    val clean = name.replace('\\', '/')
                    if (!clean.startsWith("../") && !clean.contains("/../")) {
                        val target = dest.resolve(clean).normalize()
                        if (target.startsWith(dest)) {
                            Files.createDirectories(target.parent)
                            Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                                .use { out -> copyN(gz, out, size) }
                            continue
                        }
                    }
                }
                skipFully(gz, size + padding(size))
            }
        }
    }

    // ---- minimal tar helpers (mirrors JdkManager) ----

    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off == buf.size
    }

    private fun tarName(h: ByteArray): String {
        val name = cstr(h, 0, 100)
        val prefix = cstr(h, 345, 155)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun tarSize(h: ByteArray): Long {
        val s = cstr(h, 124, 12).trim()
        return if (s.isEmpty()) 0L else s.toLong(8)
    }

    private fun cstr(h: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = off + len
        while (end < limit && h[end].toInt() != 0) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun padding(size: Long): Long = (512 - (size % 512)) % 512

    private fun skipFully(input: InputStream, n: Long) {
        var rem = n
        val buf = ByteArray(8192)
        while (rem > 0) {
            val read = input.read(buf, 0, minOf(rem, buf.size.toLong()).toInt())
            if (read < 0) break
            rem -= read
        }
    }

    private fun copyN(input: InputStream, out: OutputStream, n: Long) {
        var rem = n
        val buf = ByteArray(64 * 1024)
        while (rem > 0) {
            val read = input.read(buf, 0, minOf(rem, buf.size.toLong()).toInt())
            if (read < 0) break
            out.write(buf, 0, read)
            rem -= read
        }
    }

    override fun dispose() = pubScope.cancel()

    private companion object {
        private val log = dev.ide.platform.log.Log.logger("ide.service.dartPub")

        /** The recursive git tree of flutter/flutter's `stable` branch (paths + sha). */
        private const val FLUTTER_TREE_URL =
            "https://api.github.com/repos/flutter/flutter/git/trees/stable?recursive=1"

        /** Framework packages pulled from the SDK: `package:flutter` (material/widgets/…), `flutter_test`, and
         *  `flutter_localizations` — the set a fresh `flutter create` project imports by default. */
        private val FRAMEWORK_PREFIXES = listOf(
            "packages/flutter/lib/",
            "packages/flutter_test/lib/",
            "packages/flutter_localizations/lib/",
        )

        /** Upper bound on framework files pulled (material widgets etc. total well under this). */
        private const val FRAMEWORK_FILE_CAP = 2500
    }
}
