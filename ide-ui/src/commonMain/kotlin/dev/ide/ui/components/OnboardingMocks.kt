package dev.ide.ui.components

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.SyntaxColors

/**
 * The full-bleed feature mocks for the first-run onboarding hero. Each mock fills the 250dp
 * hero, owns its own background, and is a scaled slice of the real surface built from the same
 * design tokens. They are static (non-interactive): the only motion is a caret / pulse blink.
 *
 * All mocks share a "window card" shell (see [MockWindow]) so the tour reads as one coherent IDE
 * against a common backdrop, with each card highlighting a different surface: remote repo, console,
 * widget preview, assistant chat, dark theme, git history, snapshots, command palette and files.
 */

// ---------------------------------------------------------------------------
// Shared: syntax-colored code lines + a blinking caret
// ---------------------------------------------------------------------------

/** A colored run of code text. */
private class Tok(val text: String, val color: Color)

/** Terse token builders bound to the active syntax palette (`with(Syn(...)) { kw("if ") + … }`). */
private class Syn(val c: SyntaxColors) {
    fun kw(t: String) = Tok(t, c.keyword)
    fun fn(t: String) = Tok(t, c.func)
    fun ty(t: String) = Tok(t, c.type)
    fun st(t: String) = Tok(t, c.string)
    fun nu(t: String) = Tok(t, c.number)
    fun pn(t: String) = Tok(t, c.punctuation)
    fun pr(t: String) = Tok(t, c.property)
    fun df(t: String) = Tok(t, c.default)
}

/** Hard on/off caret blink (~1.05s steps); disabled-clock-safe (a frozen clock just shows it lit). */
@Composable
private fun caretAlpha(): Float {
    val t = rememberInfiniteTransition(label = "caret")
    val a by t.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = 1050; 1f at 0; 1f at 524; 0f at 525; 0f at 1049 },
            repeatMode = RepeatMode.Restart,
        ),
        label = "caretAlpha",
    )
    return a
}

/** One editor line: right-aligned gutter (30dp) + tokenized text. */
@Composable
private fun MockCodeLine(
    lineNumber: Int,
    tokens: List<Tok>,
    current: Boolean = false,
) {
    val mono = Ide.type.codeFamily
    Row(
        Modifier.fillMaxWidth().height(21.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            lineNumber.toString(),
            color = if (current) MaterialTheme.colorScheme.onSurfaceVariant else Ide.colors.gutterText,
            fontFamily = mono,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp).padding(end = 8.dp),
        )
        Text(
            buildAnnotatedString { tokens.forEach { withStyle(SpanStyle(color = it.color)) { append(it.text) } } },
            fontFamily = mono,
            fontSize = 12.5f.sp,
            lineHeight = 21.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

// ---------------------------------------------------------------------------
// Shared: animation helpers (continuous "video-like" motion)
// ---------------------------------------------------------------------------

/** Loops a progress value 0f→1f; disabled-clock-safe (frozen clock holds a mid value). */
@Composable
private fun loopProgress(durationMs: Int): Float {
    val t = rememberInfiniteTransition(label = "loop")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = durationMs
                0f at 0
                1f at durationMs
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "loopProgress",
    )
    return p
}

/** A gently pulsing alpha for status dots (0.35→1). */
@Composable
private fun pulseAlpha(durationMs: Int = 1400): Float {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = durationMs
                0.35f at 0
                1f at durationMs / 2
                0.35f at durationMs
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )
    return a
}

// ---------------------------------------------------------------------------
// Shared: a generic IDE window card (title/body), the backbone of every mock
// ---------------------------------------------------------------------------

private val MockPicText = 10f

/** A rounded "window" surface: a slim title bar over a body, used by every mock. */
@Composable
private fun MockWindow(
    title: String?,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Ide.colors.glassThick)
            .border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(16.dp)),
    ) {
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().background(Ide.colors.glassReg).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                icon?.invoke()
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontFamily = Ide.type.uiFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        body()
    }
}

/** A small "pill" label used for statuses, branches, chips, etc. */
@Composable
private fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = fg, fontFamily = Ide.type.uiFamily, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A line-fill bar (as in a diff or a progress indicator). */
@Composable
private fun FillBar(fill: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(2.dp)).background(color))
    }
}

// ---------------------------------------------------------------------------
// Mock 1 · GitHub: a remote repo card with branch + commit rows
// ---------------------------------------------------------------------------

@Composable
internal fun GithubMock() {
    val mono = Ide.type.codeFamily
    GridBackdrop {
        MockWindow(
            title = "assassin/CodeStudio",
            icon = { Icon(CaIcons.github, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.fillMaxSize().padding(18.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(CaIcons.gitBranch, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("main", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = MockPicText.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Pill("In sync", Ide.colors.gitAdded, Ide.colors.run)
                }
                Spacer(Modifier.height(10.dp))
                CommitRow(mono, "Refactor build pipeline", "+142 −31")
                CommitRow(mono, "Add dark theme tokens", "+68 −12")
                CommitRow(mono, "Fix dart-run on device", "+23 −9")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primary).padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(CaIcons.download, null, Modifier.size(13.dp), tint = Color.White)
                        Text("Pull", color = Color.White, fontFamily = Ide.type.uiFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(CaIcons.upload, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Push", color = MaterialTheme.colorScheme.onSurface, fontFamily = Ide.type.uiFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitRow(mono: FontFamily, message: String, delta: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha()), CircleShape))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 10.5f.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(delta, color = Ide.colors.gitModified, fontFamily = mono, fontSize = 10.sp)
    }
}

// ---------------------------------------------------------------------------
// Mock 2 · Dart: a console card running `dart run` successfully
// ---------------------------------------------------------------------------

@Composable
internal fun DartMock() {
    val mono = Ide.type.codeFamily
    val s = Syn(Ide.colors.syntax)
    GridBackdrop {
        MockWindow(
            title = "main.dart — run",
            modifier = Modifier.fillMaxSize().padding(18.dp),
            icon = { Icon(CaIcons.terminal, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        ) {
            Column(Modifier.background(Ide.colors.consoleBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$ dart run main.dart", color = MaterialTheme.colorScheme.primary, fontFamily = mono, fontSize = 10.5f.sp)
                Text("Building package executable…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 10.sp)
                MockCodeLine(1, listOf(Tok("   ", MaterialTheme.colorScheme.onSurfaceVariant), s.kw("void "), s.fn("main"), s.pn("() {")))
                MockCodeLine(2, listOf(Tok("     ", MaterialTheme.colorScheme.onSurfaceVariant), s.kw("final "), s.df("greeting = "), s.st("\"Hola — from Dart\""), s.pn(";")))
                MockCodeLine(3, listOf(Tok("     ", MaterialTheme.colorScheme.onSurfaceVariant), s.fn("print"), s.pn("(greeting);")))
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(CaIcons.check, null, Modifier.size(13.dp), tint = Ide.colors.run)
                    Text("Exited cleanly · 5 ms", color = Ide.colors.run, fontFamily = mono, fontSize = 10.5f.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                FillBar(loopProgress(2200), Ide.colors.run)
                Box(Modifier.padding(start = 1.dp).width(1.5.dp).height(13.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = caretAlpha())))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mock 3 · Flutter: mini phone frame + a widget tree on the left
// ---------------------------------------------------------------------------

@Composable
internal fun FlutterMock() {
    val mono = Ide.type.codeFamily
    val s = Syn(Ide.colors.syntax)
    GridBackdrop {
        Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1.15f).clip(RoundedCornerShape(12.dp)).background(Ide.colors.glassThick).border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("widgets/main.dart", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 9.5f.sp)
                MockCodeLine(1, listOf(Tok("", MaterialTheme.colorScheme.onSurfaceVariant)))
                MockCodeLine(2, listOf(Tok("   ", MaterialTheme.colorScheme.onSurfaceVariant), s.ty("MaterialApp"), s.pn("(")))
                MockCodeLine(3, listOf(Tok("     ", MaterialTheme.colorScheme.onSurfaceVariant), s.df("home: "), s.ty("HomePage"), s.pn("(),")))
                MockCodeLine(4, listOf(Tok("   ", MaterialTheme.colorScheme.onSurfaceVariant), s.pn(");")))
            }
            // A tiny phone preview.
            Column(
                Modifier.width(88.dp).clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant))).border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(2.dp))
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.8f)))
                Spacer(Modifier.height(10.dp))
                Text("Hello!", color = Color.White, fontFamily = Ide.type.uiFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("world", color = Color.White.copy(alpha = 0.9f), fontFamily = Ide.type.uiFamily, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    Text("!", color = Color.White, fontFamily = Ide.type.uiFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { Box(Modifier.size(5.dp).background(Color.White.copy(alpha = 0.85f), CircleShape)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mock 4 · Assistant (opencode): chat bubbles with a code answer
// ---------------------------------------------------------------------------

@Composable
internal fun AssistantMock() {
    val mono = Ide.type.codeFamily
    val s = Syn(Ide.colors.syntax)
    GridBackdrop {
        MockWindow(
            title = "Assistant · opencode",
            modifier = Modifier.fillMaxSize().padding(18.dp),
            icon = { Icon(CaIcons.sparkle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(
                    Modifier.align(Alignment.End).widthIn(max = 160.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Haz que salude en Dart", color = Color.White, fontFamily = Ide.type.uiFamily, fontSize = 10.5f.sp)
                }
                Column(
                    Modifier.align(Alignment.Start).clip(RoundedCornerShape(12.dp)).background(Ide.colors.consoleBg).border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(12.dp)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Claro — así es como queda:", color = MaterialTheme.colorScheme.onSurface, fontFamily = Ide.type.uiFamily, fontSize = 10.5f.sp)
                    MockCodeLine(0, listOf(s.kw("final "), s.df("greeting = "), s.st("\"Hola from Dart\""), s.pn(";")))
                    MockCodeLine(0, listOf(s.fn("print"), s.pn("(greeting);")))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Explain", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                    Pill("Refactor", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(6.dp).background(Ide.colors.run.copy(alpha = pulseAlpha(1200)), CircleShape))
                    Text("typing…", color = MaterialTheme.colorScheme.outline, fontFamily = Ide.type.uiFamily, fontSize = 9.5f.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mock 5 · Dark mode: theme swatches with a moon toggle
// ---------------------------------------------------------------------------

@Composable
internal fun DarkMock() {
    GridBackdrop {
        MockWindow(
            title = "Appearance",
            modifier = Modifier.fillMaxSize().padding(18.dp),
            icon = { Icon(CaIcons.moon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp)).background(Ide.colors.editorBg).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF9AA0A6)))
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF9AA0A6).copy(alpha = 0.7f)))
                    }
                    Column(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF121212)).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF565656)))
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF424242)))
                    }
                }
                Row {
                    Pill("Dark", MaterialTheme.colorScheme.primary, Color.White)
                    Spacer(Modifier.weight(1f))
                    Icon(CaIcons.moon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Follows your system", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = Ide.type.uiFamily, fontSize = 10.5f.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mock 6 · Git history: a branch timeline with merge points
// ---------------------------------------------------------------------------

@Composable
internal fun GitHistoryMock() {
    val mono = Ide.type.codeFamily
    GridBackdrop {
        MockWindow(
            title = "git history",
            modifier = Modifier.fillMaxSize().padding(18.dp),
            icon = { Icon(CaIcons.gitBranch, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GitNode(mono, "Merge feature/dark into main", "HEAD", highlight = true)
                GitNode(mono, "Add dart SDK bootstrap", null)
                GitNode(mono, "Wire flutter build stub", null)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("main", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                    Pill("feature/dark", MaterialTheme.colorScheme.primary, Color.White)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)).padding(8.dp)) {
                        Text("Refactor build pipeline", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("+142 −31", color = Ide.colors.gitModified, fontFamily = mono, fontSize = 9.5f.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GitNode(mono: FontFamily, message: String, tag: String?, highlight: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(14.dp).height(20.dp)) {
            Box(
                Modifier.size(9.dp).align(Alignment.CenterStart)
                    .background(
                        if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha(1500))
                        else Ide.colors.gitModified.copy(alpha = 0.7f),
                        CircleShape,
                    ),
            )
            Box(Modifier.fillMaxHeight().align(Alignment.CenterStart).padding(start = 4.dp).width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Text(message, color = if (highlight) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 10.5f.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tag != null) Text(tag, color = MaterialTheme.colorScheme.primary, fontFamily = Ide.type.uiFamily, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------
// Mock 7 · Gradle import: the "how should we open this Gradle build?" dialog
// ---------------------------------------------------------------------------

@Composable
internal fun GradleImportMock() {
    val mono = Ide.type.codeFamily
    GridBackdrop {
        MockWindow(
            title = "Import Gradle project",
            modifier = Modifier.fillMaxSize().padding(18.dp),
            icon = { Icon(CaIcons.github, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Open an existing Gradle build (best effort)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = Ide.type.uiFamily, fontSize = 10.5f.sp)
                Spacer(Modifier.height(2.dp))
                GradleOption("Compatibility mode", "Keep the Gradle files and re-sync from them. You can convert later.", selected = false)
                GradleOption("Convert to CodeStudio", "Switch to the module system. Gradle files move to a backup folder.", selected = true)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(CaIcons.folderOpen, null, Modifier.size(13.dp), tint = Color.White)
                    Text("Select build.gradle", color = Color.White, fontFamily = Ide.type.uiFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).background(Ide.colors.gitModified, CircleShape))
                    Text("Your existing Gradle project, ready", color = MaterialTheme.colorScheme.outline, fontFamily = Ide.type.uiFamily, fontSize = 9.5f.sp)
                }
            }
        }
    }
}

@Composable
private fun GradleOption(title: String, sub: String, selected: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.25f)).border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(11.dp).border(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(2.dp)) {
            if (selected) Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontFamily = Ide.type.uiFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = Ide.type.uiFamily, fontSize = 9.5f.sp, maxLines = 2)
        }
    }
}

@Composable
private fun SnapRow(mono: FontFamily, time: String, file: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(time, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 10.sp)
        Text(file, color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.5f.sp, modifier = Modifier.weight(1f))
        Icon(if (ok) CaIcons.check else CaIcons.warning, null, Modifier.size(12.dp), tint = if (ok) Ide.colors.run else Ide.colors.warning)
    }
}

// ---------------------------------------------------------------------------
// Mock 8 · Shortcuts / palette: a command list with KeyCaps
// ---------------------------------------------------------------------------

@Composable
internal fun ShortcutsMock() {
    val mono = Ide.type.codeFamily
    val s = Syn(Ide.colors.syntax)
    GridBackdrop {
        MockWindow(
            title = "Command palette",
            modifier = Modifier.fillMaxSize().padding(18.dp),
            icon = { Icon(CaIcons.command, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(10.dp)).background(Ide.colors.consoleBg).border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(CaIcons.search, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("build", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 11.sp)
                    Box(Modifier.padding(start = 2.dp).width(1.5.dp).height(14.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = caretAlpha())))
                }
                PaletteRow("Build APK", "Ctrl B")
                PaletteRow("Run on device", "Ctrl R")
                PaletteRow("Format code", "Ctrl L", selected = true)
                PaletteRow("Open file", "Ctrl P")
            }
        }
    }
}

@Composable
private fun PaletteRow(label: String, key: String, selected: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.25f)).padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontFamily = Ide.type.uiFamily, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Pill(key, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------
// Mock 9 · Your files: a real folder tree (final CTA step)
// ---------------------------------------------------------------------------

@Composable
internal fun FilesMock() {
    val mono = Ide.type.codeFamily
    val s = Syn(Ide.colors.syntax)
    GridBackdrop {
        MockWindow(
            title = "CodeStudio / projects",
            modifier = Modifier.fillMaxSize().padding(18.dp),
            icon = { Icon(CaIcons.folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        ) {
            Row(Modifier.fillMaxSize().padding(12.dp)) {
                Column(Modifier.width(96.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    TreeRow("my_notes", isFolder = true, open = true)
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("android", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 10.sp)
                    }
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("dart", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(2.dp))
                    TreeRow("hello_flutter", isFolder = true)
                    TreeRow("notes.dat", isFolder = false)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(CaIcons.sidebar, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("You can open this from any file manager", color = MaterialTheme.colorScheme.outline, fontFamily = Ide.type.uiFamily, fontSize = 9.sp, maxLines = 2)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Ide.colors.editorBg).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Main", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Icon(CaIcons.play, null, Modifier.size(10.dp), tint = Ide.colors.run.copy(alpha = pulseAlpha(900)))
                    }
                    MockCodeLine(1, listOf(Tok("", MaterialTheme.colorScheme.onSurfaceVariant)))
                    MockCodeLine(2, listOf(Tok("   ", MaterialTheme.colorScheme.onSurfaceVariant), s.fn("print"), s.pn("(\"hi\");")))
                }
            }
        }
    }
}

@Composable
private fun TreeRow(name: String, isFolder: Boolean, open: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (isFolder) CaIcons.folderOpen else CaIcons.docText, null, Modifier.size(13.dp), tint = if (isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, color = MaterialTheme.colorScheme.onSurface, fontFamily = Ide.type.codeFamily, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------
// Shared backdrop: a faint dotted/padded surface behind every mock card
// ---------------------------------------------------------------------------

/** A subtle editor-ish backdrop so cards don't float on the raw sheet. */
@Composable
private fun GridBackdrop(content: @Composable () -> Unit) {
    val dotColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Ide.colors.editorBg)
            .drawBehind {
                val step = 22.dp.toPx()
                var y = step
                while (y < size.height) {
                    var x = step
                    while (x < size.width) {
                        drawCircle(dotColor, radius = 1.2f, center = Offset(x, y))
                        x += step
                    }
                    y += step
                }
            },
    ) {
        content()
    }
}
