package io.legado.app.ui.book.read.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.BookContentProcess
import io.legado.app.domain.model.TextProcessAction
import io.legado.app.ui.book.read.ContentProcessConfigUiState
import io.legado.app.ui.book.read.ContentProcessItemUi
import io.legado.app.ui.book.read.ReadBookIntent
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumToggleButton
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.AppTextField

/**
 * 正文处理 Sheet：只承载 AI 改写（净化/重写）等修改正文的记录。
 * 用户划线/高亮笔记独立于本表，查看在目录 Sheet 的「笔记」页。
 */
@Composable
fun ContentProcessesSheet(
    show: Boolean,
    state: ContentProcessConfigUiState,
    onIntent: (ReadBookIntent) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.content_processes),
    ) {
        when {
            state.isLoading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCircularProgressIndicator()
                }
            }

            state.errorMessage != null -> {
                AppText(
                    text = state.errorMessage,
                    color = LegadoTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                )
            }

            state.items.isEmpty() -> {
                EmptyMessage(
                    message = stringResource(R.string.content_process_empty),
                    modifier = Modifier
                        .fillMaxWidth(),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        ContentProcessItem(
                            item = item,
                            onClick = {
                                onIntent(ReadBookIntent.OpenContentProcessHistory(item.id))
                            },
                            onToggle = {
                                onIntent(
                                    ReadBookIntent.ToggleContentProcess(item.id, !item.enabled)
                                )
                            },
                            onDelete = {
                                onIntent(ReadBookIntent.RequestDeleteContentProcess(item))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    ContentProcessHistoryDialog(state = state, onIntent = onIntent)

    val deletingItem = state.deleteItem
    AppAlertDialog(
        show = deletingItem != null,
        onDismissRequest = { onIntent(ReadBookIntent.DismissDeleteContentProcess) },
        title = stringResource(R.string.delete),
        text = stringResource(R.string.content_process_delete_confirm_simple),
        confirmText = stringResource(R.string.ok),
        onConfirm = { onIntent(ReadBookIntent.ConfirmDeleteContentProcess) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(ReadBookIntent.DismissDeleteContentProcess) },
    )
}

@Composable
private fun ContentProcessHistoryDialog(
    state: ContentProcessConfigUiState,
    onIntent: (ReadBookIntent) -> Unit,
) {
    val item = state.historyItem ?: return
    AppAlertDialog(
        show = true,
        onDismissRequest = { onIntent(ReadBookIntent.DismissContentProcessHistory) },
        title = stringResource(R.string.content_process_history),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText(
                    text = stringResource(R.string.ai_text_clean_before),
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                AppText(text = item.selectedText)
                AppTextField(
                    value = state.revisionText,
                    onValueChange = {
                        onIntent(ReadBookIntent.SetContentProcessRevisionText(it))
                    },
                    label = stringResource(R.string.ai_text_clean_after),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    MediumTonalButton(
                        onClick = { onIntent(ReadBookIntent.SaveContentProcessRevision) },
                        icon = Icons.Default.History,
                        text = stringResource(R.string.content_process_save_revision),
                        enabled = !state.isSavingRevision &&
                            state.revisionText != item.replacementText,
                    )
                }
                AppText(
                    text = stringResource(R.string.content_process_revision_history),
                    style = LegadoTheme.typography.labelLargeEmphasized,
                )
                state.history.forEach { revision ->
                    NormalCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = LegadoTheme.colorScheme.onSheetContent,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AppText(
                                    text = stringResource(
                                        R.string.content_process_revision_format,
                                        revision.revisionNumber,
                                    ),
                                    modifier = Modifier.weight(1f),
                                    style = LegadoTheme.typography.labelMedium,
                                )
                                if (revision.isCurrent) {
                                    AppText(
                                        text = stringResource(R.string.content_process_current_revision),
                                        color = LegadoTheme.colorScheme.primary,
                                        style = LegadoTheme.typography.labelSmall,
                                    )
                                } else {
                                    MediumTonalButton(
                                        onClick = {
                                            onIntent(ReadBookIntent.RollbackContentProcess(revision.id))
                                        },
                                        icon = Icons.Default.Restore,
                                        contentDescription = stringResource(R.string.content_process_rollback),
                                        enabled = !state.isSavingRevision,
                                    )
                                }
                            }
                            AppText(
                                text = revision.replacementText.ifEmpty {
                                    stringResource(R.string.ai_text_clean_delete)
                                },
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.close),
        onConfirm = { onIntent(ReadBookIntent.DismissContentProcessHistory) },
    )
}

@Composable
private fun ContentProcessItem(
    item: ContentProcessItemUi,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    NormalCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        containerColor = LegadoTheme.colorScheme.onSheetContent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AppText(
                        text = contentProcessTitle(item),
                        style = LegadoTheme.typography.labelLargeEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppText(
                        text = stringResource(R.string.chapter_index_format, item.chapterIndex + 1),
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MediumToggleButton(
                    checked = item.enabled,
                    onCheckedChange = { onToggle() },
                    icon = Icons.Default.VisibilityOff,
                    iconChecked = Icons.Default.Visibility,
                    contentDescription = stringResource(R.string.enable),
                )
                MediumTonalButton(
                    onClick = onDelete,
                    icon = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                )
            }

            Spacer(Modifier.height(10.dp))
            AppText(
                text = stringResource(R.string.ai_text_clean_before),
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
            AppText(
                text = item.selectedText,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(8.dp))
            AppText(
                text = stringResource(R.string.ai_text_clean_after),
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
            AppText(
                text = item.replacementText.ifEmpty {
                    stringResource(R.string.ai_text_clean_delete)
                },
                modifier = Modifier.padding(top = 2.dp),
                color = if (item.replacementText.isEmpty()) {
                    LegadoTheme.colorScheme.error
                } else {
                    LegadoTheme.colorScheme.onSurface
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun contentProcessTitle(item: ContentProcessItemUi): String {
    val kind = when (item.kind) {
        BookContentProcess.KIND_AI_CLEAN -> stringResource(R.string.content_process_ai_clean)
        BookContentProcess.KIND_AI_REWRITE -> stringResource(R.string.content_process_ai_rewrite)
        BookContentProcess.KIND_MANUAL_EDIT -> stringResource(R.string.content_process_manual_edit)
        else -> item.kind
    }
    val action = when (item.actionType) {
        TextProcessAction.TYPE_DELETE -> stringResource(R.string.content_process_delete_action)
        TextProcessAction.TYPE_INSERT_BEFORE,
        TextProcessAction.TYPE_INSERT_AFTER -> stringResource(R.string.content_process_insert_action)
        else -> stringResource(R.string.content_process_replace_action)
    }
    return "$kind · $action"
}
