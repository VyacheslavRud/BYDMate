package com.bydmate.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.diagnostics.CapabilityId
import com.bydmate.app.data.diagnostics.DiagnosticEvaluation
import com.bydmate.app.data.diagnostics.VehicleDiagnosticsCollector
import com.bydmate.app.data.diagnostics.VehicleDiagnosticsEvaluator
import com.bydmate.app.data.diagnostics.VehicleDiagnosticsSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DiagnosticsUiState(
    val snapshot: VehicleDiagnosticsSnapshot? = null,
    val evaluation: DiagnosticEvaluation? = null,
    val isRefreshing: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val collector: VehicleDiagnosticsCollector,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()
    private val refreshMutex = Mutex()

    init {
        refresh(showSpinner = true)
    }

    fun refresh(showSpinner: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            collectAndPublish(showSpinner)
        }
    }

    /** Refreshes only while the diagnostics destination is STARTED. */
    internal suspend fun refreshPeriodically() {
        // The first ViewModel refresh already covers initial composition. On a later resume the
        // retained snapshot may be minutes old, so refresh it immediately instead of waiting for
        // the first five-second tick.
        if (_uiState.value.snapshot != null) {
            withContext(Dispatchers.IO) {
                collectAndPublish(showSpinner = false)
            }
        }
        while (currentCoroutineContext().isActive) {
            delay(AUTO_REFRESH_MS)
            withContext(Dispatchers.IO) {
                collectAndPublish(showSpinner = false)
            }
        }
    }

    private suspend fun collectAndPublish(showSpinner: Boolean) {
        refreshMutex.withLock {
            if (showSpinner) _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                val snapshot = collector.collect()
                _uiState.value = DiagnosticsUiState(
                    snapshot = snapshot,
                    evaluation = VehicleDiagnosticsEvaluator.evaluate(snapshot),
                    isRefreshing = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
            }
        }
    }

    fun setCapabilityConfirmed(id: CapabilityId, confirmed: Boolean) {
        collector.setUserConfirmed(id, confirmed)
        refresh(showSpinner = false)
    }

    companion object {
        private const val AUTO_REFRESH_MS = 5_000L
    }
}
