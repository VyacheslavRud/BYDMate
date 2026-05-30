package com.bydmate.app.ui.cluster

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.cluster.KeyCapture
import com.bydmate.app.cluster.SteeringWheelKeyState
import com.bydmate.app.data.vehicle.DisplayInfo
import com.bydmate.app.data.vehicle.HelperClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Feature IDs probed in Phase 0 (research): UI7 instrument, setting fallback, old-gen pair. */
private val FEATURE_IDS = listOf(1276313665, 1276174357, 1086337074, 1276157976)

data class ClusterDiagnosticState(
    val loading: Boolean = false,
    val displays: List<DisplayInfo> = emptyList(),
    val displaysError: String? = null,
    val featureReadings: Map<Int, Int?> = emptyMap(),
    val featuresProbed: Boolean = false,
)

@HiltViewModel
class ClusterDiagnosticViewModel @Inject constructor(
    private val helperClient: HelperClient,
) : ViewModel() {

    private val _state = MutableStateFlow(ClusterDiagnosticState())
    val state: StateFlow<ClusterDiagnosticState> = _state.asStateFlow()

    /** Captured steering-wheel key event, surfaced straight from the service bridge. */
    val capturedKeyEvent: StateFlow<KeyCapture?> = SteeringWheelKeyState.capturedKeyEvent

    val isServiceConnected: Boolean get() = SteeringWheelKeyState.isConnected

    var consumeEvents: Boolean
        get() = SteeringWheelKeyState.consumeEvents
        set(value) { SteeringWheelKeyState.consumeEvents = value }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val displays = helperClient.listDisplays()
            val readings = FEATURE_IDS.associateWith { helperClient.getInstrumentFeature(it) }
            _state.value = ClusterDiagnosticState(
                loading = false,
                displays = displays ?: emptyList(),
                displaysError = if (displays == null) "Демон недоступен или вернул ошибку" else null,
                featureReadings = readings,
                featuresProbed = true,
            )
        }
    }
}
