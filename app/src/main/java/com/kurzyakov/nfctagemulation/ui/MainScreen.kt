package com.kurzyakov.nfctagemulation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kurzyakov.nfctagemulation.R
import com.kurzyakov.nfctagemulation.data.TagContent
import com.kurzyakov.nfctagemulation.data.TagEvent
import com.kurzyakov.nfctagemulation.tag.RecordContent
import com.kurzyakov.nfctagemulation.tag.content
import com.kurzyakov.nfctagemulation.ui.components.ContentCard
import com.kurzyakov.nfctagemulation.ui.components.EditContentSheet
import com.kurzyakov.nfctagemulation.ui.components.SessionCard
import com.kurzyakov.nfctagemulation.ui.components.StatusCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onOpenNfcSettings: () -> Unit) {
    val context = LocalContext.current
    val status by viewModel.nfcStatus.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val readerPresent by viewModel.readerPresent.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showEditor by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // Свёрнут ли сеанс: по умолчанию раскрыт только самый свежий.
    val expandedOverrides = remember { mutableStateMapOf<Long, Boolean>() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                TagEvent.MessageReceived -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    context.getString(R.string.snack_message_received)
                }
                is TagEvent.SelfTestFinished -> when {
                    !event.success -> context.getString(R.string.snack_self_test_failed)
                    event.bytesRead == 0 -> context.getString(R.string.snack_self_test_empty)
                    else -> context.getString(R.string.snack_self_test_ok, event.bytesRead)
                }
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.title_main)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_self_test)) },
                                leadingIcon = { Icon(Icons.Rounded.PlayCircleOutline, contentDescription = null) },
                                enabled = !readerPresent,
                                onClick = {
                                    menuOpen = false
                                    viewModel.runSelfTest()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_copy_log)) },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                enabled = sessions.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    clipboard.setText(AnnotatedString(Labels.exportLog(context, sessions)))
                                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.snack_log_copied)) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_clear_log)) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                                enabled = sessions.any { !it.isActive },
                                onClick = {
                                    menuOpen = false
                                    expandedOverrides.clear()
                                    viewModel.clearLog()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                StatusCard(status = status, readerPresent = readerPresent, onOpenNfcSettings = onOpenNfcSettings)
            }
            item(key = "content") {
                ContentCard(
                    content = content,
                    onEdit = { showEditor = true },
                    onClear = viewModel::clearContent,
                )
            }
            item(key = "logHeader") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.log_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (sessions.isEmpty()) {
                item(key = "logEmpty") { EmptyLog() }
            } else {
                itemsIndexed(sessions, key = { _, session -> session.id }) { index, session ->
                    val expanded = expandedOverrides[session.id] ?: (index == 0)
                    SessionCard(
                        session = session,
                        expanded = expanded,
                        onToggle = { expandedOverrides[session.id] = !expanded },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (showEditor) {
        val (initialKind, initialValue) = remember { draftFrom(content) }
        EditContentSheet(
            initialKind = initialKind,
            initialValue = initialValue,
            capacity = viewModel.capacity,
            buildRecord = viewModel::recordFor,
            encodedSize = viewModel::encodedSize,
            onSave = { record ->
                viewModel.saveContent(record)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun EmptyLog() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.History,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.log_empty_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.log_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Черновик редактора по текущему содержимому: одна текстовая запись или ссылка. */
private fun draftFrom(content: TagContent): Pair<DraftKind, String> {
    val single = content.records?.singleOrNull()?.content()
    return when (single) {
        is RecordContent.Text -> DraftKind.Text to single.text
        is RecordContent.Uri -> DraftKind.Link to single.uri
        else -> DraftKind.Text to ""
    }
}
