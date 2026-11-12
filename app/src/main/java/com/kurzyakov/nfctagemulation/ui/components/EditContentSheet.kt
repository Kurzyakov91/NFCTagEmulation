package com.kurzyakov.nfctagemulation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kurzyakov.nfctagemulation.R
import com.kurzyakov.nfctagemulation.tag.NdefRecord
import com.kurzyakov.nfctagemulation.ui.DraftKind
import kotlinx.coroutines.launch

/**
 * Редактор содержимого метки: одна текстовая запись или ссылка.
 *
 * @param buildRecord собирает запись из черновика (или `null`, если черновик пуст)
 * @param encodedSize размер NDEF-сообщения с этой записью
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContentSheet(
    initialKind: DraftKind,
    initialValue: String,
    capacity: Int,
    buildRecord: (DraftKind, String) -> NdefRecord?,
    encodedSize: (NdefRecord) -> Int,
    onSave: (NdefRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var text by rememberSaveable { mutableStateOf(if (initialKind == DraftKind.Text) initialValue else "") }
    var link by rememberSaveable { mutableStateOf(if (initialKind == DraftKind.Link) initialValue else "") }

    val value = if (kind == DraftKind.Text) text else link
    val record = buildRecord(kind, value)
    val size = record?.let(encodedSize) ?: 0
    val tooLarge = size > capacity

    fun close(after: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { after() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            // Кнопка сохранения в заголовке: на маленьком экране с открытой клавиатурой
            // нижняя часть листа может оказаться под клавиатурой.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.editor_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { record?.let { close { onSave(it) } } },
                    enabled = record != null && !tooLarge,
                ) {
                    Text(stringResource(R.string.editor_save))
                }
            }
            Spacer(Modifier.height(20.dp))

            val kinds = DraftKind.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = kind == option,
                        onClick = { kind = option },
                        shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = kind == option) {
                                Icon(
                                    if (option == DraftKind.Text) Icons.Rounded.TextFields else Icons.Rounded.Link,
                                    contentDescription = null,
                                )
                            }
                        },
                    ) {
                        Text(stringResource(if (option == DraftKind.Text) R.string.editor_kind_text else R.string.editor_kind_link))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            val supporting = when {
                tooLarge -> stringResource(R.string.editor_too_large, size, capacity)
                kind == DraftKind.Link && value.isEmpty() -> stringResource(R.string.editor_link_hint)
                else -> stringResource(R.string.editor_size, size, capacity)
            }
            if (kind == DraftKind.Text) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.editor_text_label)) },
                    minLines = 2,
                    maxLines = 8,
                    isError = tooLarge,
                    supportingText = { Text(supporting) },
                )
            } else {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.editor_link_label)) },
                    singleLine = true,
                    isError = tooLarge,
                    supportingText = { Text(supporting) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
            }

        }
    }
}
