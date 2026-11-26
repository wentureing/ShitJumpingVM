package com.nextvm.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextvm.core.virtualization.process.SlotState
import com.nextvm.core.virtualization.process.SlotStatus
import com.nextvm.core.virtualization.process.TaskRef

/**
 * Process Slots 列表：只渲染 [ProcessSlotsViewModel.UiState] 并转发动作请求。
 *
 * 清理粒度是 **task**（不按包名批量），见变更文档 §3.3。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessSlotsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProcessSlotsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var expandedSlot by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ProcessSlotsViewModel.Effect.ShowMessage ->
                    snackbarHostState.showSnackbar(effect.message, duration = SnackbarDuration.Short)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Process Slots", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.onIntent(ProcessSlotsViewModel.Intent.Refresh) }) {
                        Text("Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (uiState.isLoading) "Reading slots..." else
                    "${uiState.slots.size} slot(s) in use · tap a row to see its tasks",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!uiState.isLoading && uiState.slots.isEmpty()) {
                Text(
                    text = "No running slot",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(
                    items = uiState.slots,
                    key = { "${it.slot}-${it.instanceId ?: "none"}-${it.state.name}" }
                ) { status ->
                    SlotRow(
                        status = status,
                        expanded = expandedSlot == status.slot,
                        onToggle = {
                            expandedSlot = if (expandedSlot == status.slot) null else status.slot
                        },
                        onBringToFront = {
                            viewModel.onIntent(
                                ProcessSlotsViewModel.Intent.BringToFront(status.instanceId)
                            )
                        },
                        onCleanupSlot = {
                            viewModel.onIntent(ProcessSlotsViewModel.Intent.CleanupSlot(status.slot))
                        },
                        onCleanupTask = { taskId ->
                            viewModel.onIntent(ProcessSlotsViewModel.Intent.CleanupTask(taskId))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotRow(
    status: SlotStatus,
    expanded: Boolean,
    onToggle: () -> Unit,
    onBringToFront: () -> Unit,
    onCleanupSlot: () -> Unit,
    onCleanupTask: (Int) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "p${status.slot}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = status.packageName ?: status.instanceId ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = status.state.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = stateColor(status.state)
                )
            }
            Text(
                text = buildString {
                    append("pid ").append(if (status.pid > 0) status.pid else "—")
                    append(" · task ").append(if (status.taskId > 0) status.taskId else "—")
                    if (status.taskCount > 0) append(" (").append(status.taskCount).append(" tasks)")
                    if (status.topComponent != null) append(" · ").append(status.topComponent)
                    if (status.note.isNotEmpty()) append("  (").append(status.note).append(")")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                OutlinedButton(
                    onClick = onBringToFront,
                    enabled = status.state == SlotState.RUNNING && status.taskId > 0
                ) { Text("Bring to front") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onCleanupSlot,
                    enabled = status.tasks.isNotEmpty()
                ) { Text("Close all tasks") }
            }
            if (expanded && status.tasks.isNotEmpty()) {
                Text(
                    text = "Tasks (close one by one; process stays alive)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
                status.tasks.forEach { task ->
                    TaskRow(task = task, onClose = { onCleanupTask(task.taskId) })
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskRef, onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) {
        Text(
            text = "task ${task.taskId} · ${task.numActivities} act" +
                (task.topComponent?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onClose) { Text("Close") }
    }
}

private fun stateColor(state: SlotState): Color = when (state) {
    SlotState.RUNNING -> Color(0xFF2E7D32)
    SlotState.STALE -> Color(0xFFEF6C00)
    SlotState.ORPHAN -> Color(0xFFC62828)
    SlotState.CONFLICT -> Color(0xFF6A1B9A)
    SlotState.FREE -> Color(0xFF757575)
}
