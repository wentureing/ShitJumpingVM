package com.nextvm.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextvm.core.virtualization.engine.VirtualEngine
import com.nextvm.core.virtualization.process.SlotStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Process Slots 页的 ViewModel：只把引擎快照翻译成 UiState，并把用户动作转成引擎调用。
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

        /** 把该实例当前会话切到前台（不新开 task）。 */
        data class BringToFront(val instanceId: String?) : Intent()

        /** 只关这一个 task（不杀进程、不影响同 guest 的其它 task）。 */
        data class CleanupTask(val taskId: Int) : Intent()

        /** 关掉该 slot 下的全部 task（逐个；仍不杀进程）。 */
        data class CleanupSlot(val slot: Int) : Intent()
    }

    sealed class Effect {
        data class ShowMessage(val message: String) : Effect()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<Effect>()
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    init {
        refresh()
    }

    fun onIntent(intent: Intent) {
        when (intent) {
            is Intent.Refresh -> refresh()
            is Intent.BringToFront -> {
                val id = intent.instanceId ?: return
                act("Brought slot to front") { engine.bringToFront(id) }
            }
            is Intent.CleanupTask -> act("Closed task ${intent.taskId}") {
                engine.cleanupTask(intent.taskId)
            }
            is Intent.CleanupSlot -> act("Closed tasks in slot ${intent.slot}") {
                engine.cleanupSlot(intent.slot) > 0
            }
        }
    }

    /** 执行一个引擎动作（IO 线程），然后重新拉一次快照；消息必须反映真实成败。 */
    private fun act(message: String, block: suspend () -> Boolean) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrDefault(false)
            }
            _effects.emit(Effect.ShowMessage(if (ok) message else "No effect: $message"))
            refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val slots = withContext(Dispatchers.IO) {
                runCatching { engine.slotStatus(force = true) }.getOrElse { emptyList() }
            }
            _uiState.value = UiState(isLoading = false, slots = slots)
        }
    }
}
