package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.core.LocalHistoryStore
import dev.ide.core.settings.SettingsStore
import dev.ide.ui.backend.LocalHistoryService
import dev.ide.ui.backend.UiLocalHistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * [LocalHistoryService] over a [LocalHistoryStore] rooted at the open project's `.platform/local-history/`.
 *
 * Recording is deliberately fire-and-forget on a background scope: it is called from the editor's save path, so
 * it must never block the save, throw into it, or make a save slower. Every call is wrapped in `runCatching` for
 * the same reason — history that fails to record is a lost convenience, a save that fails is lost work.
 */
internal class LocalHistoryBackend(private val ctx: BackendContext) : LocalHistoryService {

    private val settingsStore = SettingsStore(
        get = { k -> ctx.manager?.preference(k) },
        set = { k, v -> ctx.manager?.setPreference(k, v) },
    )

    private val store = LocalHistoryStore(
        root = { ctx.servicesOrNull?.workspaceRoot },
        maxAgeMs = { settingsStore.load().localHistoryRetentionDays.coerceIn(1, MAX_RETENTION_DAYS) * DAY_MS },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun record(path: String, text: String) {
        if (!enabled()) return
        scope.launch { store.record(path, text) }
    }

    override fun entries(path: String): List<UiLocalHistoryEntry> {
        if (!enabled()) return emptyList()
        val snapshots = store.entries(path)
        if (snapshots.isEmpty()) return emptyList()
        // Newest first; the newest is the one that matches disk (saves record AFTER writing), so it is flagged
        // as `current` and the viewer can label it instead of guessing.
        return snapshots.mapIndexed { index, s ->
            UiLocalHistoryEntry(id = s.id, timestampMs = s.timestampMs, bytes = s.bytes, current = index == 0)
        }
    }

    override fun read(id: String): String? = store.read(id)

    override fun isSupported(): Boolean = enabled()

    override fun clear(path: String) {
        scope.launch { store.clear(path) }
    }

    /** Bytes held by this project's snapshots — surfaced on the Storage screen. */
    fun totalBytes(): Long = runCatching { store.totalBytes() }.getOrDefault(0L)

    /** Forget this project's history (Settings ▸ Storage). */
    fun clearAll() {
        scope.launch { store.clearAll() }
    }

    fun close() {
        runCatching { scope.cancel() }
    }

    private fun enabled(): Boolean = settingsStore.load().localHistoryEnabled

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MAX_RETENTION_DAYS = 30
    }
}
