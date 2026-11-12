package com.kurzyakov.nfctagemulation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kurzyakov.nfctagemulation.R
import com.kurzyakov.nfctagemulation.data.Exchange
import com.kurzyakov.nfctagemulation.data.Session
import com.kurzyakov.nfctagemulation.tag.StatusWord
import com.kurzyakov.nfctagemulation.tag.toHex
import com.kurzyakov.nfctagemulation.ui.Labels
import com.kurzyakov.nfctagemulation.ui.theme.AppTheme
import com.kurzyakov.nfctagemulation.ui.theme.HexTextStyle

@Composable
fun SessionCard(
    session: Session,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        SessionHeader(session = session, expanded = expanded, onToggle = onToggle)
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                session.exchanges.forEach { exchange ->
                    Spacer(Modifier.height(10.dp))
                    ExchangeRow(exchange)
                }
                session.endReason?.let { reason ->
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = Labels.endReason(LocalContext.current, reason),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(session: Session, expanded: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val active = session.isActive
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (active) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (session.simulated) Icons.Rounded.PlayCircleOutline else Icons.Rounded.Sensors,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Labels.sessionTitle(context, session), style = MaterialTheme.typography.titleSmall)
                if (session.messageWritten) {
                    Spacer(Modifier.width(8.dp))
                    Tag(
                        text = stringResource(R.string.session_written),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            val parts = buildList {
                add(Labels.time(session.startedAt))
                add(Labels.commandCount(context, session.exchanges.size))
                val endedAt = session.endedAt
                if (endedAt == null) {
                    add(stringResource(R.string.session_in_progress))
                } else {
                    add(stringResource(R.string.session_duration, (endedAt - session.startedAt) / 1000.0))
                }
            }
            Text(
                text = parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.rotate(rotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExchangeRow(exchange: Exchange) {
    val context = LocalContext.current
    val ok = exchange.statusWord == StatusWord.OK
    val extended = AppTheme.extendedColors
    val accent = if (ok) extended.success else MaterialTheme.colorScheme.error

    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(accent.copy(alpha = 0.7f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = Labels.operation(context, exchange.operation),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Column {
                    HexLine(arrow = "→", bytes = exchange.command.toHex())
                    HexLine(arrow = "←", bytes = exchange.response.toHex())
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(
                    text = "%04X · %s".format(exchange.statusWord, Labels.statusWord(context, exchange.statusWord)),
                    container = if (ok) extended.successContainer else MaterialTheme.colorScheme.errorContainer,
                    content = if (ok) extended.onSuccessContainer else MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = Labels.preciseTime(exchange.time),
                    style = HexTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HexLine(arrow: String, bytes: String) {
    Row {
        Text(text = "$arrow ", style = HexTextStyle, color = MaterialTheme.colorScheme.primary)
        Text(text = bytes, style = HexTextStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
