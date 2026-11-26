package com.nextvm.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextvm.core.virtualization.engine.VirtualEngine
import com.nextvm.core.virtualization.process.SlotStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Process Slots 页的 ViewModel：只把引擎的只读快照翻译成 UiState。
 *
 * 说明：docs/输出/变更/20261127-process-slots-会话复用与UI解绑.md §3.3
 */
@HiltViewModel
class ProcessSlotsViewModel @Inject constructor(
    private val engine: VirtualEngine,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val slots: List<SlotStatus> = emptyList(),
    )

    sealed class Intent {
        data object Refresh : Intent()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun onIntent(intent: Intent) {
        when (intent) {
            is Intent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val slots = withContext(Dispatchers.IO) {
                runCatching { engine.slotStatus() }.getOrElse { emptyList() }
            }
            _uiState.value = UiState(isLoading = false, slots = slots)
        }
    }
}
