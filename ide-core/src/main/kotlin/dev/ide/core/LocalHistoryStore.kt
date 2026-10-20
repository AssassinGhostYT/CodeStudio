package dev.ide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Snapshots of a file's contents taken on every save, kept under `<project>/.platform/local-history/` — the same
 * per-project corner the agent sessions and the open-tab session already use, so a project's history never leaks
 * into another one. Layout: `index.json` (which paths have which snapshots) plus one raw-text file per snapshot
 * under `files/`, named `<timestamp>-<hash>`.
 *
 * It exists because the only other "undo" on a phone is the editor's in-buffer stack, which dies with the tab:
 * close the app mid-edit and the previous version of the file is simply gone. Snapshots are taken AFTER the save,
 * so the newest entry always matches what is on disk and older entries are what used to be there.
 *
 * Bounded on every axis (per-file count, age, and a project-wide byte budget) because this lives on a phone with
 * finite storage, and every write goes through a temp file + an atomic move so a crash or a dead battery mid-write
 * can never leave a half-written snapshot or index behind.
 */
internal class LocalHistoryStore(
    private val root: () -> Path?,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: () -> Long = { MAX_AGE_MS },
) {
    data class Snapshot(val id: String, val timestampMs: Long, val bytes: Long)

    private val lock = ReentrantLock()
    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(): Path? = root()?.resolve(".platform")?.resolve("local-history")
    private fun filesDir(): Path? = dir()?.resolve("files")
    private fun indexFile(): Path? = dir()?.resolve("index.json")

    /**
     * Remember [text] as the current contents of [path]. Returns true when a snapshot was actually written —
     * false when it was skipped (identical to the newest snapshot). A save landing inside [MIN_INTERVAL_MS] of
     * the previous one coalesces into it rather than adding a second entry. A no-op returning false when there
     * is no project, the store is unreadable, or anything fails: recording history must never be able to fail
     * the save that triggered it.
     */
    fun record(path: String, text: String): Boolean = runCatching { recordOrThrow(path, text) }.getOrDefault(false)

    private fun recordOrThrow(path: String, text: String): Boolean {
        val files = filesDir() ?: return false
        val index = indexFile() ?: return false
        val ts = now()
        val hash = sha256(text)
        val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()

        lock.withLock {
            val state = readIndex(index)
            val list = state.getOrPut(path) { mutableListOf() }
            val prev = list.lastOrNull()

            // Same content as the newest snapshot: nothing changed worth remembering.
            if (prev != null && prev.id.substringAfter('-', "") == hash.take(HASH_CHARS)) return false

            Files.createDirectories(files)

            // A save landing inside the coalescing window replaces the previous snapshot in place instead of
            // stacking a near-duplicate (a burst of saves — format-on-save, a multi-file edit — would otherwise
            // eat the byte budget). The snapshot keeps its original timestamp but its text moves to the new
            // content, so "the newest snapshot is what is on disk" stays true.
            if (prev != null && ts - prev.timestampMs < MIN_INTERVAL_MS) {
                val coalescedId = "${prev.timestampMs}-${hash.take(HASH_CHARS)}"
                filesDir()?.resolve("${prev.id}.txt")?.deleteIfExists()
                writeAtomic(files.resolve("$coalescedId.txt"), text)
                list.removeAt(list.lastIndex)
                list += Snapshot(coalescedId, prev.timestampMs, bytes)
            } else {
                val id = "$ts-${hash.take(HASH_CHARS)}"
                writeAtomic(files.resolve("$id.txt"), text)
                list += Snapshot(id, ts, bytes)
            }
            prune(list, ts)
            pruneProject(state)

            writeAtomic(index, encode(state))
            return true
        }
    }

    /** The snapshots for [path], newest first. */
    fun entries(path: String): List<Snapshot> {
        val index = indexFile() ?: return emptyList()
        if (!index.isRegularFile()) return emptyList()
        lock.withLock {
            return readIndex(index)[path].orEmpty().asReversed()
        }
    }

    /** The stored text of snapshot [id], or null when it is missing/corrupt. */
    fun read(id: String): String? {
        val file = filesDir()?.resolve("$id.txt") ?: return null
        if (!file.isRegularFile()) return null
        return runCatching { file.toFile().readText(Charsets.UTF_8) }.getOrNull()
    }

    /** Forget every snapshot of [path] (and its files). */
    fun clear(path: String) {
        val index = indexFile() ?: return
        if (!index.isRegularFile()) return
        lock.withLock {
            val state = readIndex(index)
            val removed = state.remove(path) ?: return
            removed.forEach { filesDir()?.resolve("${it.id}.txt")?.deleteIfExists() }
            writeAtomic(index, encode(state))
        }
    }

    /** Forget everything. Used by Settings ▸ Storage, and by the tests. */
    fun clearAll() {
        lock.withLock {
            dir()?.toFile()?.deleteRecursively()
        }
    }

    /** Bytes currently held by all snapshots — reported to the Storage screen. */
    fun totalBytes(): Long {
        val index = indexFile() ?: return 0L
        if (!index.isRegularFile()) return 0L
        lock.withLock {
            return readIndex(index).values.sumOf { list -> list.sumOf { it.bytes } }
        }
    }

    // --- pruning ------------------------------------------------------------------------------------------

    /** Drop snapshots of one file that are too old or beyond the per-file cap. */
    private fun prune(list: MutableList<Snapshot>, ts: Long) {
        val cutoff = ts - maxAgeMs()
        list.removeAll { it.timestampMs < cutoff }
        while (list.size > MAX_PER_FILE) list.removeAt(0)
    }

    /** Drop the oldest snapshots across the whole project until the byte budget is met. */
    private fun pruneProject(state: MutableMap<String, MutableList<Snapshot>>) {
        var total = state.values.sumOf { l -> l.sumOf { it.bytes } }
        if (total <= MAX_TOTAL_BYTES) return
        // Oldest first, everywhere; a file's last remaining snapshot is dropped too — the budget wins over
        // completeness, and the file's current content is on disk anyway.
        while (total > MAX_TOTAL_BYTES) {
            var oldestPath: String? = null
            var oldest: Snapshot? = null
            for ((p, list) in state) {
                val candidate = list.firstOrNull() ?: continue
                if (oldest == null || candidate.timestampMs < oldest.timestampMs) {
                    oldest = candidate
                    oldestPath = p
                }
            }
            val victim = oldest ?: break
            val victimPath = oldestPath ?: break
            state[victimPath]?.removeAt(0)
            filesDir()?.resolve("${victim.id}.txt")?.deleteIfExists()
            total -= victim.bytes
        }
        state.values.removeAll { it.isEmpty() }
    }

    // --- index codec --------------------------------------------------------------------------------------

    private fun readIndex(file: Path): MutableMap<String, MutableList<Snapshot>> {
        val text = runCatching { file.toFile().readText(Charsets.UTF_8) }.getOrNull() ?: return mutableMapOf()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return mutableMapOf()
        val files = obj["files"] as? JsonObject ?: return mutableMapOf()
        val out = mutableMapOf<String, MutableList<Snapshot>>()
        for ((path, value) in files) {
            val arr = value as? JsonArray ?: continue
            val list = mutableListOf<Snapshot>()
            for (element in arr) {
                val o = element as? JsonObject ?: continue
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val ts = (o["ts"] as? JsonPrimitive)?.longOrNull ?: continue
                val bytes = (o["bytes"] as? JsonPrimitive)?.longOrNull ?: 0L
                list += Snapshot(id, ts, bytes)
            }
            if (list.isNotEmpty()) out[path] = list
        }
        return out
    }

    private fun encode(state: Map<String, List<Snapshot>>): String = buildJsonObject {
        put("version", 1)
        put("files", buildJsonObject {
            for ((path, list) in state) {
                put(path, buildJsonArray {
                    for (s in list) {
                        addJsonObject {
                            put("id", s.id)
                            put("ts", s.timestampMs)
                            put("bytes", s.bytes)
                        }
                    }
                })
            }
        })
    }.toString()

    /** Write [text] to [file] via a sibling temp file and an atomic move, so a torn write is impossible. */
    private fun writeAtomic(file: Path, text: String) {
        val tmp = file.resolveSibling("${file.name}.tmp")
        Files.createDirectories(file.parent)
        tmp.toFile().writeText(text, Charsets.UTF_8)
        runCatching {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** Snapshots kept per file; the oldest is dropped past this. */
        const val MAX_PER_FILE = 20

        /** Snapshots older than this are dropped, however few are left. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** Project-wide budget; the oldest snapshots anywhere are dropped past this. */
        const val MAX_TOTAL_BYTES = 50L * 1024 * 1024

        /** Saves landing closer together than this are coalesced into the previous snapshot. */
        const val MIN_INTERVAL_MS = 15_000L

        private const val HASH_CHARS = 12
    }
}
