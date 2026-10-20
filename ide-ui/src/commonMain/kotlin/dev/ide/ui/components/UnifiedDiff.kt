package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * A line-level diff between two texts, used by Local History to show what a snapshot changed before the user
 * puts it back. Pure Kotlin (commonMain) on purpose — no java.util, no CLI: this has to run on every host.
 *
 * Only the middle of the two texts is compared with the LCS after the common prefix and suffix are trimmed
 * first, which is what makes a typical edit (a few lines changed in a long file) cost almost nothing instead
 * of a full O(n·m) pass over the whole file.
 */
object LineDiff {

    enum class Kind { CONTEXT, ADDED, REMOVED }

    data class Row(val kind: Kind, val text: String, val oldLine: Int?, val newLine: Int?)

    /** The rows rendering [old] → [new] as a unified diff. An empty list means the texts are identical. */
    fun rows(old: String, new: String): List<Row> {
        val a = old.split('\n')
        val b = new.split('\n')

        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++

        var suffix = 0
        while (
            suffix < a.size - prefix &&
            suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) suffix++

        val out = ArrayList<Row>(a.size + b.size)
        for (i in 0 until prefix) out += Row(Kind.CONTEXT, a[i], i + 1, i + 1)

        val oldMid = a.subList(prefix, a.size - suffix)
        val newMid = b.subList(prefix, b.size - suffix)
        out += middle(oldMid, newMid, prefix)

        for (i in suffix downTo 1) {
            val ai = a.size - i
            out += Row(Kind.CONTEXT, a[ai], ai + 1, b.size - i + 1)
        }
        return out
    }

    private fun middle(a: List<String>, b: List<String>, offset: Int): List<Row> {
        if (a.isEmpty() && b.isEmpty()) return emptyList()
        if (a.isEmpty()) return b.mapIndexed { i, t -> Row(Kind.ADDED, t, null, offset + i + 1) }
        if (b.isEmpty()) return a.mapIndexed { i, t -> Row(Kind.REMOVED, t, offset + i + 1, null) }

        // Classic LCS table over the middle only. Guarded: a pathologically large middle (a whole-file rewrite)
        // would be quadratic, so past the cap we degrade to "all old out, all new in" — still correct, just no
        // clever pairing.
        if (a.size.toLong() * b.size > MAX_CELLS) {
            val removed = a.mapIndexed { i, t -> Row(Kind.REMOVED, t, offset + i + 1, null) }
            val added = b.mapIndexed { i, t -> Row(Kind.ADDED, t, null, offset + i + 1) }
            return removed + added
        }

        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1
                else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }

        val out = ArrayList<Row>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> {
                    out += Row(Kind.CONTEXT, a[i], offset + i + 1, offset + j + 1)
                    i++; j++
                }
                table[i + 1][j] >= table[i][j + 1] -> {
                    out += Row(Kind.REMOVED, a[i], offset + i + 1, null)
                    i++
                }
                else -> {
                    out += Row(Kind.ADDED, b[j], null, offset + j + 1)
                    j++
                }
            }
        }
        while (i < a.size) out += Row(Kind.REMOVED, a[i], offset + i + 1, null).also { i++ }
        while (j < b.size) out += Row(Kind.ADDED, b[j], null, offset + j + 1).also { j++ }
        return out
    }

    /** Above this many LCS cells the pairing is skipped (see [middle]). */
    private const val MAX_CELLS = 4_000_000L
}

/**
 * Renders [old] → [new] as a coloured unified diff: removed rows on a red tint, added on green, context plain.
 * Monospace with a horizontal scroll so a long line doesn't wrap and break the alignment of its +/-.
 */
@Composable
fun UnifiedDiffView(
    old: String,
    new: String,
    modifier: Modifier = Modifier,
    emptyText: String = "Sin diferencias.",
) {
    val rows = remember(old, new) { LineDiff.rows(old, new) }
    // `rows` always has at least one row (a split never yields zero parts), so "identical" is "nothing was
    // added or removed" rather than an empty list.
    if (rows.none { it.kind != LineDiff.Kind.CONTEXT }) {
        Text(
            emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp),
        )
        return
    }
    val hScroll = rememberScrollState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxSize().horizontalScroll(hScroll)) {
        val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().background(tintOf(row.kind))) {
                Text(
                    gutterOf(row),
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                )
                Text(
                    signOf(row.kind) + row.text,
                    style = style,
                    color = when (row.kind) {
                        LineDiff.Kind.REMOVED -> Color(0xFFFF8A80)
                        LineDiff.Kind.ADDED -> Color(0xFFB9F6CA)
                        LineDiff.Kind.CONTEXT -> onSurface
                    },
                )
            }
        }
    }
}

/** Context rows stay untinted — a background under every unchanged row makes a long file unreadable. */
private fun tintOf(kind: LineDiff.Kind): Color = when (kind) {
    LineDiff.Kind.REMOVED -> Color(0x33FF5252)
    LineDiff.Kind.ADDED -> Color(0x332E7D32)
    LineDiff.Kind.CONTEXT -> Color.Transparent
}

private fun signOf(kind: LineDiff.Kind): String = when (kind) {
    LineDiff.Kind.REMOVED -> "− "
    LineDiff.Kind.ADDED -> "+ "
    LineDiff.Kind.CONTEXT -> "  "
}

private fun gutterOf(row: LineDiff.Row): String = when (row.kind) {
    LineDiff.Kind.REMOVED -> (row.oldLine ?: 0)
    LineDiff.Kind.ADDED -> (row.newLine ?: 0)
    LineDiff.Kind.CONTEXT -> (row.newLine ?: row.oldLine ?: 0)
}.toString().padStart(5)
