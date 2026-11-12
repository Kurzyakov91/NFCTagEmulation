package com.kurzyakov.nfctagemulation.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kurzyakov.nfctagemulation.R
import com.kurzyakov.nfctagemulation.data.ContentOrigin
import com.kurzyakov.nfctagemulation.data.TagContent
import com.kurzyakov.nfctagemulation.tag.NdefMessageCodec
import com.kurzyakov.nfctagemulation.tag.NdefRecord
import com.kurzyakov.nfctagemulation.tag.RecordContent
import com.kurzyakov.nfctagemulation.tag.content
import com.kurzyakov.nfctagemulation.ui.Labels
import com.kurzyakov.nfctagemulation.ui.theme.NfcTagTheme

@Composable
fun ContentCard(
    content: TagContent,
    onEdit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(stringResource(R.string.content_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            val records = content.records
            when {
                content.isEmpty -> RecordRow(
                    icon = Icons.Rounded.CropFree,
                    caption = stringResource(R.string.content_empty_title),
                    value = stringResource(R.string.content_empty_subtitle),
                    muted = true,
                )
                records == null -> RecordRow(
                    icon = Icons.Rounded.WarningAmber,
                    caption = stringResource(R.string.content_invalid),
                    value = stringResource(R.string.record_binary, content.message.size),
                    muted = true,
                )
                else -> records.forEachIndexed { index, record ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    NdefRecordRow(record)
                }
            }

            Spacer(Modifier.height(20.dp))
            UsageBar(used = content.message.size, capacity = content.capacity)

            content.updatedAt?.let { updatedAt ->
                Spacer(Modifier.height(6.dp))
                val time = Labels.dateTime(updatedAt)
                val origin = when (content.origin) {
                    ContentOrigin.Reader -> stringResource(R.string.content_origin_reader)
                    ContentOrigin.App -> stringResource(R.string.content_origin_app)
                    ContentOrigin.None -> null
                }
                Text(
                    text = origin?.let { stringResource(R.string.content_updated_with_origin, time, it) }
                        ?: stringResource(R.string.content_updated, time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(if (content.isEmpty) R.string.content_set else R.string.content_edit))
                }
                if (!content.isEmpty) {
                    OutlinedButton(onClick = onClear) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.content_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun NdefRecordRow(record: NdefRecord) {
    when (val content = record.content()) {
        is RecordContent.Text -> RecordRow(
            icon = Icons.Rounded.TextFields,
            caption = stringResource(R.string.record_text, content.language),
            value = content.text,
        )
        is RecordContent.Uri -> {
            val uriHandler = LocalUriHandler.current
            RecordRow(
                icon = Icons.Rounded.Link,
                caption = stringResource(R.string.record_uri),
                value = content.uri,
                trailing = {
                    IconButton(onClick = { runCatching { uriHandler.openUri(content.uri) } }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.content_open_link))
                    }
                },
            )
        }
        is RecordContent.Mime -> RecordRow(
            icon = Icons.Rounded.Description,
            caption = stringResource(R.string.record_mime, content.mimeType),
            value = content.preview ?: stringResource(R.string.record_binary, content.size),
        )
        is RecordContent.External -> RecordRow(
            icon = Icons.Rounded.Extension,
            caption = stringResource(R.string.record_external, content.type),
            value = content.preview ?: stringResource(R.string.record_binary, content.size),
        )
        RecordContent.Empty -> RecordRow(
            icon = Icons.Rounded.CropFree,
            caption = stringResource(R.string.record_empty),
            value = null,
        )
        is RecordContent.Other -> RecordRow(
            icon = if (content.size > 0) Icons.Rounded.DataObject else Icons.AutoMirrored.Rounded.HelpOutline,
            caption = stringResource(R.string.record_other, content.tnf, content.type),
            value = stringResource(R.string.record_binary, content.size),
        )
    }
}

@Composable
private fun RecordRow(
    icon: ImageVector,
    caption: String,
    value: String?,
    muted: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = if (muted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null) {
                Spacer(Modifier.height(2.dp))
                SelectionContainer {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun UsageBar(used: Int, capacity: Int) {
    val fraction = if (capacity > 0) used.toFloat() / capacity else 0f
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        drawStopIndicator = {},
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.content_usage, used, capacity),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview
@Composable
private fun ContentCardPreview() {
    val message = NdefMessageCodec.encode(
        listOf(NdefRecord.text("Привет! Это эмулированная метка.", "ru"), NdefRecord.uri("https://example.com")),
    )
    NfcTagTheme {
        ContentCard(
            content = TagContent(
                message = message,
                records = NdefMessageCodec.decode(message),
                capacity = 4094,
                origin = ContentOrigin.Reader,
                updatedAt = 1_700_000_000_000,
            ),
            onEdit = {},
            onClear = {},
        )
    }
}
