package dev.ide.core

import dev.ide.testkit.withTempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LocalHistoryStore] is the safety net that outlives the editor's undo stack, so the contract under test is
 * the one that protects work: a save always leaves a readable snapshot behind, nothing grows without bound,
 * and a corrupt store degrades to "no history" rather than throwing into the save path.
 *
 * The clock is injected because coalescing and age-pruning are time-based; with the real clock two back-to-back
 * saves in a test would land inside [LocalHistoryStore.MIN_INTERVAL_MS] and silently merge into one entry.
 */
class LocalHistoryStoreTest {

    /** A store on a fake clock the test advances by hand ([step] moves past the coalescing window). */
    private class Clock {
        var now = 1_700_000_000_000L
        fun step(ms: Long = LocalHistoryStore.MIN_INTERVAL_MS + 1_000L) { now += ms }
    }

    @Test
    fun aChangedSaveAlwaysLeavesAReadableSnapshot() = withTempDir("ide-local-history") { dir ->
        val clock = Clock()
        val store = LocalHistoryStore(root = { dir }, now = { clock.now })

        assertTrue(store.record("Main.java", "v1"), "first save must snapshot")
        clock.step()
        assertTrue(store.record("Main.java", "v2"), "changed save must snapshot")

        val entries = store.entries("Main.java")
        assertEquals(2, entries.size, "newest first, both kept")
        assertEquals("v2", store.read(entries[0].id), "newest is what was just saved")
        assertEquals("v1", store.read(entries[1].id), "the previous version is still recoverable")
    }

    @Test
    fun savingUnchangedContentDoesNotStackDuplicates() = withTempDir("ide-local-history") { dir ->
        val clock = Clock()
        val store = LocalHistoryStore(root = { dir }, now = { clock.now })

        assertTrue(store.record("Main.java", "same"))
        clock.step()
        assertFalse(store.record("Main.java", "same"), "identical content is not worth a second snapshot")
        assertEquals(1, store.entries("Main.java").size)
    }

    @Test
    fun aBurstOfSavesCoalescesIntoOneSnapshotInsteadOfFillingTheBudget() = withTempDir("ide-local-history") { dir ->
        val clock = Clock()
        val store = LocalHistoryStore(root = { dir }, now = { clock.now })

        assertTrue(store.record("Main.java", "v1"))
        // Same second — format-on-save firing twice, or a multi-file edit saving in quick succession.
        clock.now += 2_000L
        assertTrue(store.record("Main.java", "v2"), "a different content inside the window still replaces")

        val entries = store.entries("Main.java")
        assertEquals(1, entries.size, "the burst is coalesced, not stacked")
        assertEquals("v2", store.read(entries[0].id), "and it holds the LATEST content, so it still matches disk")
    }

    @Test
    fun aFileKeepsAtMostItsCapOfSnapshots() = withTempDir("ide-local-history") { dir ->
        val clock = Clock()
        val store = LocalHistoryStore(root = { dir }, now = { clock.now })

        val total = LocalHistoryStore.MAX_PER_FILE + 5
        repeat(total) { i ->
            clock.step()
            store.record("Main.java", "v$i")
        }

        val entries = store.entries("Main.java")
        assertEquals(LocalHistoryStore.MAX_PER_FILE, entries.size, "the oldest were dropped")
        assertEquals("v${total - 1}", store.read(entries.first().id), "newest survived")
        assertEquals("v${total - LocalHistoryStore.MAX_PER_FILE}", store.read(entries.last().id))
    }

    @Test
    fun snapshotsOlderThanTheRetentionAreDropped() = withTempDir("ide-local-history") { dir ->
        val clock = Clock()
        val day = 24L * 60 * 60 * 1000
        val store = LocalHistoryStore(root = { dir }, now = { clock.now }, maxAgeMs = { day })

        store.record("Main.java", "old")
        clock.step(day * 3)
        store.record("Main.java", "fresh")

        val entries = store.entries("Main.java")
        assertEquals(1, entries.size, "the three-day-old snapshot aged out")
        assertEquals("fresh", store.read(entries[0].id))
    }

    @Test
    fun aCorruptIndexDegradesToNoHistoryInsteadOfThrowing() = withTempDir("ide-local-history") { dir ->
        val clock = Clock()
        val store = LocalHistoryStore(root = { dir }, now = { clock.now })
        store.record("Main.java", "v1")

        val index = dir.resolve(".platform/local-history/index.json")
        index.toFile().writeText("{ this is not json")

        assertEquals(emptyList(), store.entries("Main.java"), "unreadable index reads as empty")
        assertEquals(0L, store.totalBytes())
        // …and the store keeps working: the next save rewrites a fresh index.
        clock.step()
        assertTrue(store.record("Main.java", "v2"))
        assertEquals(1, store.entries("Main.java").size)
    }

    @Test
    fun clearingOneFileLeavesTheOthersAlone() = withTempDir("ide-local-history") { dir ->
        val clock = Clock()
        val store = LocalHistoryStore(root = { dir }, now = { clock.now })
        store.record("A.java", "a1")
        clock.step()
        store.record("B.java", "b1")

        store.clear("A.java")

        assertEquals(emptyList(), store.entries("A.java"))
        assertEquals(1, store.entries("B.java").size)
    }

    @Test
    fun clearAllForgetsEverything() = withTempDir("ide-local-history") { dir ->
        val clock = Clock()
        val store = LocalHistoryStore(root = { dir }, now = { clock.now })
        store.record("A.java", "a1")
        clock.step()
        store.record("B.java", "b1")
        assertTrue(store.totalBytes() > 0)

        store.clearAll()

        assertEquals(emptyList(), store.entries("A.java"))
        assertEquals(emptyList(), store.entries("B.java"))
        assertEquals(0L, store.totalBytes())
        assertFalse(dir.resolve(".platform/local-history").toFile().exists())
    }

    @Test
    fun totalBytesIsTheSumOfWhatIsHeld() = withTempDir("ide-local-history") { dir ->
        val clock = Clock()
        val store = LocalHistoryStore(root = { dir }, now = { clock.now })
        store.record("A.java", "hello") // 5 bytes
        clock.step()
        store.record("B.java", "hi!") // 3 bytes
        assertEquals(8L, store.totalBytes())

        clock.step()
        store.record("A.java", "hello, world!") // 13 bytes, replaces A's only snapshot by coalescing? no — interval passed
        // Two snapshots for A now: "hello" (5) and "hello, world!" (13), plus B's "hi!" (3) = 21
        assertEquals(21L, store.totalBytes())
    }

    @Test
    fun aStoreWithNoProjectIsANoOpThatNeverThrows() {
        val store = LocalHistoryStore(root = { null })
        assertFalse(store.record("Main.java", "v1"))
        assertEquals(emptyList(), store.entries("Main.java"))
        assertNull(store.read("whatever"))
        assertEquals(0L, store.totalBytes())
        store.clear("Main.java")
        store.clearAll()
    }

    @Test
    fun theOldestSnapshotIsWhatGetsEvictedByTheProjectByteBudget() = withTempDir("ide-local-history") { dir ->
        val clock = Clock()
        val store = LocalHistoryStore(root = { dir }, now = { clock.now })
        // Three files of 20 MB blow past the 50 MB project budget; the oldest snapshots anywhere are dropped
        // first, so the oldest file pays — its content is on disk anyway.
        val chunk = "x".repeat(20 * 1024 * 1024)
        repeat(3) { i ->
            clock.step()
            store.record("F$i.java", chunk)
        }
        assertTrue(store.totalBytes() <= LocalHistoryStore.MAX_TOTAL_BYTES, "the project budget is enforced")
        assertEquals(emptyList(), store.entries("F0.java"), "the oldest file was evicted whole")
        assertEquals(1, store.entries("F1.java").size)
        assertEquals(1, store.entries("F2.java").size, "the newest file is intact")
    }
}
