package com.poi.homelab.feature.home.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.SecondaryButton
import com.poi.homelab.core.ui.components.SuccessButton
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.feature.home.TodoItem
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoEditorSheet(
    items: List<TodoItem>,
    onDismiss: () -> Unit,
    onSave: (List<TodoItem>) -> Unit,
) {
    var drafts by remember { mutableStateOf(items) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "编辑 Todo",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                InlineAction("取消", onDismiss)
            }
            Text(
                text = "勾选状态按天保存，列表改动会一直保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            drafts.forEachIndexed { index, item ->
                key(item.id) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = item.label,
                            onValueChange = { value ->
                                drafts = drafts.toMutableList().also {
                                    it[index] = it[index].copy(label = value)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("待办内容") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                        )
                        InlineAction(
                            text = "删除",
                            onClick = {
                                drafts = drafts.filterIndexed { i, _ -> i != index }
                            },
                            color = Danger,
                        )
                    }
                }
            }

            if (drafts.isEmpty()) {
                Text(
                    text = "还没有事项，先添加一条吧。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            SecondaryButton(
                text = "添加一项",
                onClick = {
                    drafts = drafts + TodoItem("todo-${UUID.randomUUID()}", "")
                },
                modifier = Modifier.padding(top = 12.dp),
            )
            SuccessButton(
                text = "保存",
                onClick = {
                    onSave(drafts)
                    onDismiss()
                },
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
