package com.bydmate.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import com.bydmate.app.cluster.ClusterLabManager
import com.bydmate.app.hud.HudLabManager
import com.bydmate.app.hud.HudLabObserved
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Dedicated state holder for the dev-only parked HUD calibration destination. */
@HiltViewModel
class HudLabViewModel @Inject constructor(
    private val hudLabManager: HudLabManager,
    clusterLabManager: ClusterLabManager,
) : ViewModel() {
    val state = hudLabManager.state
    val clusterLabState = clusterLabManager.state

    fun sendScenario(scenarioId: String, parkConfirmedByUser: Boolean) {
        val cluster = clusterLabState.value
        if (cluster.busy || cluster.pendingObservationRecordId != null) return
        hudLabManager.sendScenario(scenarioId, parkConfirmedByUser)
    }

    fun recordObservation(observed: HudLabObserved) {
        hudLabManager.recordObservation(observed)
    }

    fun clearHud() {
        hudLabManager.clear()
    }

    fun export() {
        hudLabManager.export()
    }

    fun deleteRecords() {
        hudLabManager.deleteRecords()
    }
}
