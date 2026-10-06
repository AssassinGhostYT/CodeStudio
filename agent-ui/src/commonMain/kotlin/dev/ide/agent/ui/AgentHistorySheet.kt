package dev.ide.agent.ui

import dev.ide.ui.components.CenteredDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_close
import dev.ide.agent.ui.generated.resources.chat_delete
import dev.ide.agent.ui.generated.resources.chat_history
import dev.ide.agent.ui.generated.resources.chat_history_empty
import dev.ide.agent.ui.generated.resources.chat_history_for
import dev.ide.agent.ui.generated.resources.chat_history_messages
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The saved conversations of the project currently open, newest first: tap one to resume it, or delete it.
 *
 * The project name is on the header on purpose. Sessions live under the project folder, so the list is only
 * ever this project's chats — and saying which project it is keeps a resumed conversation from looking like it
 * belongs to whatever project happens to be open later.
 */
@Composable
internal fun AgentHistorySheet(backend: IdeBackend, projectName: String, onClose: () -> Unit) {
    var sessions by remember { mutableStateOf(backend.agent.sessions()) }
    val current = backend.agent.chatState.value.sessionId
    CenteredDialog(visible = true, onDismiss = onClose) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CaIcons.clock, null, Modifier.size(17.dp), tint = Ca.colors.accent)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.chat_history),
                        color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(Res.string.chat_history_for) + ": " + projectName,
                        color = Ca.colors.textTertiary, style = Ca.type.caption, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HistoryIconButton(CaIcons.close, stringResource(Res.string.chat_close), onClose)
            }
            if (sessions.isEmpty()) {
                Text(
                    stringResource(Res.string.chat_history_empty),
                    color = Ca.colors.textTertiary, style = Ca.type.footnote,
                )
            } else {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sessions.forEach { session ->
                        val selected = session.id == current
                        val fill = if (selected) Ca.colors.accentSoft else Ca.colors.surface3
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md)).background(fill)
                                .clickable {
                                    backend.agent.resumeSession(session.id)
                                    onClose()
                                }
                                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    session.title,
                                    color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(Res.string.chat_history_messages) + " " + session.messageCount,
                                    color = Ca.colors.textTertiary, style = Ca.type.caption,
                                )
                            }
                            HistoryIconButton(CaIcons.close, stringResource(Res.string.chat_delete)) {
                                backend.agent.deleteSession(session.id)
                                sessions = backend.agent.sessions()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(Ca.radius.sm))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(14.dp), tint = Ca.colors.textSecondary)
    }
}
