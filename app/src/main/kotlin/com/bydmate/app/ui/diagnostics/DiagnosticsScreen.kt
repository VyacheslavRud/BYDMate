package com.bydmate.app.ui.diagnostics

import android.view.Display
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bydmate.app.R
import com.bydmate.app.BuildConfig
import com.bydmate.app.cluster.ClusterLabObservation
import com.bydmate.app.cluster.ClusterLabOutcomeType
import com.bydmate.app.cluster.ClusterLabScenario
import com.bydmate.app.cluster.ClusterLabScenarioCatalog
import com.bydmate.app.cluster.ClusterLabState
import com.bydmate.app.data.diagnostics.CapabilityAssessment
import com.bydmate.app.data.diagnostics.CapabilityId
import com.bydmate.app.data.diagnostics.CapabilityState
import com.bydmate.app.data.diagnostics.DiagnosticHealth
import com.bydmate.app.data.diagnostics.DiagnosticReason
import com.bydmate.app.data.diagnostics.DiagnosticSection
import com.bydmate.app.data.diagnostics.DiagnosticSectionResult
import com.bydmate.app.data.diagnostics.DisplaySnapshot
import com.bydmate.app.data.diagnostics.HudIncident
import com.bydmate.app.data.diagnostics.HudIncidentCause
import com.bydmate.app.data.diagnostics.VehicleDiagnosticsSnapshot
import com.bydmate.app.data.diagnostics.WazeWindowState
import com.bydmate.app.data.vehicle.VehicleProfile
import com.bydmate.app.hud.HudLabObserved
import com.bydmate.app.hud.HudLabOutcome
import com.bydmate.app.hud.HudLabOutcomeType
import com.bydmate.app.hud.HudLabScenario
import com.bydmate.app.hud.HudLabScenarioCatalog
import com.bydmate.app.hud.HudLabScenarioGroup
import com.bydmate.app.hud.HudLabSendFailure
import com.bydmate.app.hud.HudLabState
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hudLabState by viewModel.hudLabState.collectAsStateWithLifecycle()
    val clusterLabState by viewModel.clusterLabState.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val evaluation = state.evaluation

    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshPeriodically()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep))),
    ) {
        // Keep the car layout at two readable columns even on a low-density 1920 px panel.
        // A narrow emulator gets one unconstrained column instead of Adaptive's 390 dp minimum.
        val gridColumns = if (maxWidth >= 840.dp) GridCells.Fixed(2) else GridCells.Fixed(1)
        LazyVerticalGrid(
            columns = gridColumns,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DiagnosticsHeader(
                    capturedAtMs = snapshot?.capturedAtMs,
                    isRefreshing = state.isRefreshing,
                    onBack = onBack,
                    onRefresh = { viewModel.refresh() },
                )
            }

            if (state.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ErrorCard(state.error!!)
                }
            }

            if (snapshot == null || evaluation == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LoadingCard()
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OverallCard(evaluation.sections)
                }
                evaluation.sections.forEach { result ->
                    item {
                        DiagnosticSectionCard(result, snapshot)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DisplaysCard(snapshot.displays, snapshot.clusterSelectedDisplayId)
                }
                if (BuildConfig.DEBUG) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        HudLabCard(
                            state = hudLabState,
                            externalLabBusy = clusterLabState.busy ||
                                clusterLabState.pendingObservationRecordId != null,
                            onSend = viewModel::sendHudLabScenario,
                            onObserved = viewModel::recordHudLabObservation,
                            onClear = viewModel::clearHudLab,
                            onExport = viewModel::exportHudLab,
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ClusterLabCard(
                            state = clusterLabState,
                            exportState = hudLabState,
                            onRun = viewModel::runClusterLabScenario,
                            onObserved = viewModel::recordClusterLabObservation,
                            onCancel = viewModel::cancelClusterLab,
                            onExport = viewModel::exportHudLab,
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CapabilitiesCard(
                        capabilities = evaluation.capabilities,
                        snapshot = snapshot,
                        onConfirm = viewModel::setCapabilityConfirmed,
                    )
                }
            }
        }
    }
}

@Composable
private fun HudLabCard(
    state: HudLabState,
    externalLabBusy: Boolean,
    onSend: (String, Boolean) -> Unit,
    onObserved: (HudLabObserved) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    var parkConfirmed by remember { mutableStateOf(false) }
    val pending = state.pending
    val canSend = parkConfirmed && !state.busy && pending == null && !externalLabBusy
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AccentBlue.copy(alpha = 0.09f)),
        border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.42f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                stringResource(R.string.diagnostics_hud_lab_title),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.diagnostics_hud_lab_description),
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.diagnostics_hud_lab_native_hint),
                color = AccentBlue,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 5.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.busy && pending == null && !externalLabBusy) {
                        parkConfirmed = !parkConfirmed
                    }
                    .padding(top = 9.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = parkConfirmed,
                    onCheckedChange = { parkConfirmed = it },
                    enabled = !state.busy && pending == null && !externalLabBusy,
                )
                Text(
                    stringResource(R.string.diagnostics_hud_lab_park_confirmation),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            HudLabScenarioGroup.entries.forEach { group ->
                val scenarios = HudLabScenarioCatalog.all.filter { it.group == group }
                Text(
                    hudLabGroupText(group),
                    color = AccentBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                scenarios.chunked(2).forEach { row ->
                    HudLabScenarioRow(
                        scenarios = row,
                        enabled = canSend,
                        onSend = { onSend(it.id, parkConfirmed) },
                    )
                }
            }

            Text(
                stringResource(R.string.diagnostics_hud_lab_auto_clear),
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 7.dp),
            )

            if (pending != null) {
                HorizontalDivider(
                    color = CardBorder.copy(alpha = 0.55f),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                Text(
                    "${pending.record.scenarioId ?: "LEGACY"} · " +
                        (pending.record.scenarioTitle ?: pending.record.command?.name ?: "HUD Lab"),
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                pending.record.scenarioSummary?.let {
                    Text(
                        it,
                        color = TextMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (pending.autoCleared) {
                    Text(
                        stringResource(R.string.diagnostics_hud_lab_already_cleared),
                        color = AccentOrange,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    stringResource(R.string.diagnostics_hud_lab_what_seen),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 5.dp),
                )
                when (pending.record.scenarioId) {
                    "W24" -> {
                        HudLabObservedRow(
                            HudLabObserved.LEFT_THEN_RIGHT,
                            HudLabObserved.REVERSED_SEQUENCE,
                            !state.busy,
                            onObserved,
                        )
                        HudLabObservedRow(
                            HudLabObserved.FIRST_PHASE_ONLY,
                            HudLabObserved.SECOND_PHASE_ONLY,
                            !state.busy,
                            onObserved,
                        )
                        HudLabObservedRow(
                            HudLabObserved.NOTHING,
                            HudLabObserved.FLASHED,
                            !state.busy,
                            onObserved,
                        )
                    }
                    "W25" -> {
                        HudLabObservedRow(
                            HudLabObserved.RIGHT_CLEARED_AND_REDRAWN,
                            HudLabObserved.CLEAR_NOT_VISIBLE,
                            !state.busy,
                            onObserved,
                        )
                        HudLabObservedRow(
                            HudLabObserved.FIRST_PHASE_ONLY,
                            HudLabObserved.SECOND_PHASE_ONLY,
                            !state.busy,
                            onObserved,
                        )
                        HudLabObservedRow(
                            HudLabObserved.NOTHING,
                            HudLabObserved.FLASHED,
                            !state.busy,
                            onObserved,
                        )
                    }
                    "W26", "W27", "W28", "W29", "W30", "W31" -> {
                        val expected = HudLabScenarioCatalog.byId(
                            requireNotNull(pending.record.scenarioId),
                        )?.expected ?: HudLabObserved.INFO_VISIBLE
                        HudLabObservedRow(
                            expected,
                            HudLabObserved.INFO_VISIBLE,
                            !state.busy,
                            onObserved,
                        )
                        HudLabObservedRow(
                            HudLabObserved.NOTHING,
                            HudLabObserved.FLASHED,
                            !state.busy,
                            onObserved,
                        )
                    }
                    else -> {
                        HudLabObservedRow(
                            HudLabObserved.LEFT,
                            HudLabObserved.RIGHT,
                            !state.busy,
                            onObserved,
                        )
                        HudLabObservedRow(
                            HudLabObserved.STRAIGHT,
                            HudLabObserved.UTURN,
                            !state.busy,
                            onObserved,
                        )
                        HudLabObservedRow(
                            HudLabObserved.NOTHING,
                            HudLabObserved.FLASHED,
                            !state.busy,
                            onObserved,
                        )
                        HudLabObservedRow(
                            HudLabObserved.INFO_VISIBLE,
                            HudLabObserved.SPEED_SIGN_VISIBLE,
                            !state.busy,
                            onObserved,
                        )
                    }
                }
                HudLabObservedRow(
                    first = HudLabObserved.OTHER,
                    second = HudLabObserved.NOT_REPORTED,
                    enabled = !state.busy,
                    onObserved = onObserved,
                )
            }

            if (state.busy && state.currentScenarioId != null) {
                Text(
                    "${state.currentScenarioId}: step ${state.currentStep}/${state.totalSteps}, " +
                        "push ${state.currentPush}/${state.totalPushes}",
                    color = AccentOrange,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }

            state.lastOutcome?.let { outcome ->
                Text(
                    hudLabOutcomeText(outcome),
                    color = if (outcome.type in setOf(
                            HudLabOutcomeType.SEND_REJECTED,
                            HudLabOutcomeType.EXPORT_FAILED,
                        )
                    ) SocRed else AccentGreen,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    border = BorderStroke(1.dp, CardBorder),
                ) {
                    Text(stringResource(R.string.diagnostics_hud_lab_clear))
                }
                Button(
                    onClick = onExport,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        contentColor = NavyDark,
                    ),
                ) {
                    Text(stringResource(R.string.diagnostics_hud_lab_export))
                }
            }
            Text(
                stringResource(R.string.diagnostics_hud_lab_saved_count, state.recordsCount),
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun ClusterLabCard(
    state: ClusterLabState,
    exportState: HudLabState,
    onRun: (String, Boolean) -> Unit,
    onObserved: (ClusterLabObservation) -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
) {
    var parkConfirmed by remember { mutableStateOf(false) }
    val pending = state.pendingObservationRecordId != null
    val canRun = parkConfirmed && !state.busy && !pending &&
        !exportState.busy && exportState.pending == null
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AccentOrange.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.42f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                stringResource(R.string.diagnostics_cluster_lab_title),
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.diagnostics_cluster_lab_description),
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.diagnostics_cluster_lab_safety_hint),
                color = AccentOrange,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 5.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.busy && !pending) {
                        parkConfirmed = !parkConfirmed
                    }
                    .padding(top = 9.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = parkConfirmed,
                    onCheckedChange = { parkConfirmed = it },
                    enabled = !state.busy && !pending,
                )
                Text(
                    stringResource(R.string.diagnostics_hud_lab_park_confirmation),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            ClusterLabScenarioCatalog.all.chunked(2).forEach { row ->
                ClusterLabScenarioRow(
                    scenarios = row,
                    enabled = canRun,
                    onRun = { onRun(it.id, parkConfirmed) },
                )
            }

            if (state.busy) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                    color = AccentOrange,
                    trackColor = CardBorder.copy(alpha = 0.45f),
                )
                Text(
                    "${state.currentScenarioId ?: "?"}: ${state.currentStep ?: "starting"}",
                    color = AccentOrange,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (pending) {
                HorizontalDivider(
                    color = CardBorder.copy(alpha = 0.55f),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                Text(
                    stringResource(
                        R.string.diagnostics_cluster_lab_what_seen,
                        state.pendingObservationScenarioId ?: "?",
                    ),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
                ClusterLabObservedRow(
                    ClusterLabObservation.VISIBLE,
                    ClusterLabObservation.NOTHING,
                    onObserved,
                )
                ClusterLabObservedRow(
                    ClusterLabObservation.BLACK_SCREEN,
                    ClusterLabObservation.FLICKERED,
                    onObserved,
                )
                ClusterLabObservedRow(
                    ClusterLabObservation.WRONG_GEOMETRY,
                    ClusterLabObservation.MAIN_DISPLAY_ONLY,
                    onObserved,
                )
                ClusterLabObservedRow(
                    ClusterLabObservation.OTHER,
                    ClusterLabObservation.NOT_REPORTED,
                    onObserved,
                )
            }

            state.lastOutcome?.let { outcome ->
                val success = outcome.type == ClusterLabOutcomeType.COMPLETED ||
                    outcome.type == ClusterLabOutcomeType.OBSERVATION_SAVED
                Text(
                    buildString {
                        append(outcome.scenarioId ?: "Cluster Lab")
                        append(": ").append(outcome.type)
                        outcome.failure?.let { append(" · ").append(it) }
                        outcome.cleanupConfirmed?.let { append(" · cleanup=").append(it) }
                    },
                    color = if (success) AccentGreen else SocRed,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    border = BorderStroke(1.dp, CardBorder),
                ) {
                    Text(stringResource(R.string.diagnostics_cluster_lab_cancel))
                }
                Button(
                    onClick = onExport,
                    enabled = !state.busy && !exportState.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        contentColor = NavyDark,
                    ),
                ) {
                    Text(stringResource(R.string.diagnostics_cluster_lab_export_all))
                }
            }
            exportState.lastOutcome?.takeIf {
                it.type == HudLabOutcomeType.EXPORTED || it.type == HudLabOutcomeType.EXPORT_FAILED
            }?.let {
                Text(
                    hudLabOutcomeText(it),
                    color = if (it.type == HudLabOutcomeType.EXPORTED) AccentGreen else SocRed,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Text(
                stringResource(R.string.diagnostics_cluster_lab_saved_count, state.recordsCount),
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun ClusterLabScenarioRow(
    scenarios: List<ClusterLabScenario>,
    enabled: Boolean,
    onRun: (ClusterLabScenario) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        scenarios.forEach { scenario ->
            Button(
                onClick = { onRun(scenario) },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentOrange.copy(alpha = 0.78f),
                    contentColor = NavyDark,
                ),
            ) {
                Text("${scenario.id} · ${scenario.title}", fontSize = 11.sp, maxLines = 3)
            }
        }
        if (scenarios.size == 1) Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ClusterLabObservedRow(
    first: ClusterLabObservation,
    second: ClusterLabObservation,
    onObserved: (ClusterLabObservation) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(first, second).forEach { observed ->
            OutlinedButton(
                onClick = { onObserved(observed) },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.55f)),
            ) {
                Text(clusterLabObservedText(observed), fontSize = 11.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun clusterLabObservedText(observed: ClusterLabObservation): String = stringResource(
    when (observed) {
        ClusterLabObservation.VISIBLE -> R.string.diagnostics_cluster_lab_seen_visible
        ClusterLabObservation.NOTHING -> R.string.diagnostics_hud_lab_saw_nothing
        ClusterLabObservation.BLACK_SCREEN -> R.string.diagnostics_cluster_lab_seen_black
        ClusterLabObservation.FLICKERED -> R.string.diagnostics_cluster_lab_seen_flickered
        ClusterLabObservation.WRONG_GEOMETRY -> R.string.diagnostics_cluster_lab_seen_geometry
        ClusterLabObservation.MAIN_DISPLAY_ONLY -> R.string.diagnostics_cluster_lab_seen_main_only
        ClusterLabObservation.OTHER -> R.string.diagnostics_hud_lab_saw_other
        ClusterLabObservation.NOT_REPORTED -> R.string.diagnostics_hud_lab_not_reported
    },
)

@Composable
private fun HudLabScenarioRow(
    scenarios: List<HudLabScenario>,
    enabled: Boolean,
    onSend: (HudLabScenario) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        scenarios.forEach { scenario ->
            Button(
                onClick = { onSend(scenario) },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue.copy(alpha = 0.82f),
                    contentColor = TextPrimary,
                ),
                ) {
                Text("${scenario.id} · ${scenario.title}", maxLines = 3, fontSize = 11.sp)
            }
        }
        if (scenarios.size == 1) Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HudLabObservedRow(
    first: HudLabObserved,
    second: HudLabObserved,
    enabled: Boolean,
    onObserved: (HudLabObserved) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(first, second).forEach { observed ->
            OutlinedButton(
                onClick = { onObserved(observed) },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.55f)),
            ) {
                Text(hudLabObservedText(observed), maxLines = 2)
            }
        }
    }
}

@Composable
private fun hudLabGroupText(group: HudLabScenarioGroup): String = when (group) {
    HudLabScenarioGroup.CONTROL -> "Control / CLEAR"
    HudLabScenarioGroup.RAW_F28 -> "Native metadata f28"
    HudLabScenarioGroup.PNG_F8 -> "Donor PNG f8"
    HudLabScenarioGroup.MATCHED -> "Matched f8 + f28"
    HudLabScenarioGroup.CROSSED -> "Conflict: f8 vs f28"
    HudLabScenarioGroup.FULL -> "Full frame / transitions"
    HudLabScenarioGroup.FIELD_ISOLATION -> "Field isolation: f9/f10/f26/f33/f7/f11"
    HudLabScenarioGroup.TIMING -> "Delivery timing / cadence"
}

@Composable
private fun hudLabObservedText(observed: HudLabObserved): String = stringResource(
    when (observed) {
        HudLabObserved.LEFT -> R.string.diagnostics_hud_lab_saw_left
        HudLabObserved.RIGHT -> R.string.diagnostics_hud_lab_saw_right
        HudLabObserved.STRAIGHT -> R.string.diagnostics_hud_lab_saw_straight
        HudLabObserved.UTURN -> R.string.diagnostics_hud_lab_saw_uturn
        HudLabObserved.NOTHING -> R.string.diagnostics_hud_lab_saw_nothing
        HudLabObserved.FLASHED -> R.string.diagnostics_hud_lab_saw_flashed
        HudLabObserved.INFO_VISIBLE -> R.string.diagnostics_hud_lab_saw_info
        HudLabObserved.DISTANCE_VISIBLE -> R.string.diagnostics_hud_lab_saw_distance
        HudLabObserved.ROAD_VISIBLE -> R.string.diagnostics_hud_lab_saw_road
        HudLabObserved.ETA_VISIBLE -> R.string.diagnostics_hud_lab_saw_eta
        HudLabObserved.PROGRESS_VISIBLE -> R.string.diagnostics_hud_lab_saw_progress
        HudLabObserved.SPEED_NUMBER_VISIBLE -> R.string.diagnostics_hud_lab_saw_speed_number
        HudLabObserved.SPEED_SIGN_VISIBLE -> R.string.diagnostics_hud_lab_saw_speed_sign
        HudLabObserved.LEFT_THEN_RIGHT -> R.string.diagnostics_hud_lab_saw_left_then_right
        HudLabObserved.RIGHT_CLEARED_AND_REDRAWN -> R.string.diagnostics_hud_lab_saw_redrawn
        HudLabObserved.CLEAR_NOT_VISIBLE -> R.string.diagnostics_hud_lab_saw_clear_not_visible
        HudLabObserved.REVERSED_SEQUENCE -> R.string.diagnostics_hud_lab_saw_reversed_sequence
        HudLabObserved.FIRST_PHASE_ONLY -> R.string.diagnostics_hud_lab_saw_first_only
        HudLabObserved.SECOND_PHASE_ONLY -> R.string.diagnostics_hud_lab_saw_second_only
        HudLabObserved.OTHER -> R.string.diagnostics_hud_lab_saw_other
        HudLabObserved.NOT_REPORTED -> R.string.diagnostics_hud_lab_not_reported
    },
)

@Composable
private fun hudLabOutcomeText(outcome: HudLabOutcome): String = when (outcome.type) {
    HudLabOutcomeType.FRAME_SENT -> stringResource(
        R.string.diagnostics_hud_lab_frame_sent,
        outcome.rc ?: -1,
    )
    HudLabOutcomeType.SEND_REJECTED -> if (outcome.failure != null) {
        hudLabFailureText(outcome.failure)
    } else {
        stringResource(R.string.diagnostics_hud_lab_send_rejected, outcome.rc ?: -1)
    }
    HudLabOutcomeType.CLEARED -> stringResource(
        R.string.diagnostics_hud_lab_clear_result,
        outcome.rc ?: -1,
    )
    HudLabOutcomeType.OBSERVATION_SAVED ->
        stringResource(R.string.diagnostics_hud_lab_observation_saved)
    HudLabOutcomeType.EXPORTED -> stringResource(
        R.string.diagnostics_hud_lab_exported,
        outcome.path ?: "?",
    )
    HudLabOutcomeType.EXPORT_FAILED ->
        stringResource(R.string.diagnostics_hud_lab_export_failed)
}

@Composable
private fun hudLabFailureText(failure: HudLabSendFailure): String = stringResource(
    when (failure) {
        HudLabSendFailure.DEV_BUILD_REQUIRED -> R.string.diagnostics_hud_lab_failure_dev
        HudLabSendFailure.PARK_CONFIRMATION_REQUIRED -> R.string.diagnostics_hud_lab_failure_confirm
        HudLabSendFailure.VEHICLE_DATA_UNAVAILABLE -> R.string.diagnostics_hud_lab_failure_vehicle_data
        HudLabSendFailure.VEHICLE_MOVING -> R.string.diagnostics_hud_lab_failure_moving
        HudLabSendFailure.PARK_GEAR_REQUIRED -> R.string.diagnostics_hud_lab_failure_park
        HudLabSendFailure.ROUTE_ACTIVE -> R.string.diagnostics_hud_lab_failure_route
        HudLabSendFailure.HUD_DISABLED -> R.string.diagnostics_hud_lab_failure_disabled
        HudLabSendFailure.HUD_NOT_READY -> R.string.diagnostics_hud_lab_failure_not_ready
        HudLabSendFailure.HUD_SEND_FAILED -> R.string.diagnostics_hud_lab_failure_send
        HudLabSendFailure.INVALID_PAYLOAD -> R.string.diagnostics_hud_lab_failure_payload
        HudLabSendFailure.HUD_ICON_UNAVAILABLE -> R.string.diagnostics_hud_lab_failure_icon
        HudLabSendFailure.HUD_CLEAR_FAILED -> R.string.diagnostics_hud_lab_failure_clear
        HudLabSendFailure.JOURNAL_WRITE_FAILED -> R.string.diagnostics_hud_lab_failure_journal
    },
)

@Composable
private fun DiagnosticsHeader(
    capturedAtMs: Long?,
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
    ) {
        if (maxWidth < 760.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DiagnosticsBackButton(onBack)
                    DiagnosticsHeaderTitle(Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DiagnosticsCheckedAt(capturedAtMs, Modifier.weight(1f))
                    DiagnosticsRefreshButton(isRefreshing, onRefresh)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DiagnosticsBackButton(onBack)
                DiagnosticsHeaderTitle(Modifier.weight(1f))
                DiagnosticsCheckedAt(capturedAtMs)
                DiagnosticsRefreshButton(isRefreshing, onRefresh)
            }
        }
    }
}

@Composable
private fun DiagnosticsBackButton(onBack: () -> Unit) {
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, CardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(R.string.diagnostics_back_cd),
        )
        Text(stringResource(R.string.diagnostics_back), modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun DiagnosticsHeaderTitle(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            stringResource(R.string.diagnostics_title),
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
        )
        Text(
            stringResource(
                R.string.diagnostics_profile,
                VehicleProfile.CURRENT.model,
                VehicleProfile.CURRENT.trim,
            ),
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun DiagnosticsCheckedAt(capturedAtMs: Long?, modifier: Modifier = Modifier) {
    Text(
        if (capturedAtMs == null) stringResource(R.string.diagnostics_not_checked)
        else stringResource(R.string.diagnostics_last_checked, timeText(capturedAtMs)),
        color = TextMuted,
        fontSize = 12.sp,
        modifier = modifier,
    )
}

@Composable
private fun DiagnosticsRefreshButton(isRefreshing: Boolean, onRefresh: () -> Unit) {
    Button(
        onClick = onRefresh,
        enabled = !isRefreshing,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = NavyDark),
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = NavyDark,
            )
        } else {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(
            stringResource(if (isRefreshing) R.string.diagnostics_refreshing else R.string.diagnostics_refresh),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

private fun deriveOverallHealth(results: List<DiagnosticSectionResult>): DiagnosticHealth = when {
    results.any { it.health == DiagnosticHealth.ERROR } -> DiagnosticHealth.ERROR
    results.any { it.health == DiagnosticHealth.ATTENTION } -> DiagnosticHealth.ATTENTION
    results.any { it.health == DiagnosticHealth.CHECKING } -> DiagnosticHealth.CHECKING
    results.isEmpty() || results.any { it.health == DiagnosticHealth.UNKNOWN } -> DiagnosticHealth.UNKNOWN
    else -> DiagnosticHealth.HEALTHY
}

@Composable
private fun OverallCard(results: List<DiagnosticSectionResult>) {
    val errors = results.count { it.health == DiagnosticHealth.ERROR }
    val warnings = results.count { it.health == DiagnosticHealth.ATTENTION }
    val overallHealth = deriveOverallHealth(results)
    val accent = healthColor(overallHealth)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(overallHealth)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    stringResource(
                        when (overallHealth) {
                            DiagnosticHealth.HEALTHY -> R.string.diagnostics_overall_good
                            DiagnosticHealth.CHECKING -> R.string.diagnostics_overall_checking
                            DiagnosticHealth.UNKNOWN -> R.string.diagnostics_overall_unknown
                            else -> R.string.diagnostics_overall_attention
                        },
                    ),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (errors > 0 || warnings > 0) {
                    Text(
                        stringResource(R.string.diagnostics_overall_counts, errors, warnings),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            StatusBadge(overallHealth)
        }
    }
}

@Composable
private fun DiagnosticSectionCard(
    result: DiagnosticSectionResult,
    snapshot: VehicleDiagnosticsSnapshot,
) {
    var expanded by remember(result.section) { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, healthColor(result.health).copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(result.health)
                Text(
                    sectionTitle(result.section),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                StatusBadge(result.health)
            }
            Text(
                reasonSummary(result.reason, snapshot),
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 9.dp),
            )
            Text(
                stringResource(
                    if (expanded) R.string.diagnostics_hide_details
                    else R.string.diagnostics_show_details,
                ),
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (expanded) {
                HorizontalDivider(
                    color = CardBorder.copy(alpha = 0.55f),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                technicalRows(result.section, snapshot).forEach { (label, value) ->
                    TechnicalRow(label, value)
                }
            }
        }
    }
}

@Composable
private fun DisplaysCard(displays: List<DisplaySnapshot>, selectedDisplayId: Int?) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                stringResource(R.string.diagnostics_displays_title),
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.diagnostics_displays_count, displays.size),
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            if (displays.isEmpty()) {
                Text(stringResource(R.string.diagnostics_displays_empty), color = TextSecondary)
            } else {
                displays.forEachIndexed { index, display ->
                    if (index > 0) HorizontalDivider(color = CardBorder.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(
                            if (display.isClusterCandidate) DiagnosticHealth.HEALTHY
                            else DiagnosticHealth.UNKNOWN,
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(display.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(
                                    R.string.diagnostics_display_details,
                                    display.id,
                                    display.widthPx,
                                    display.heightPx,
                                    display.densityDpi,
                                    displayState(display.state),
                                ),
                                color = TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                        if (display.isClusterCandidate) {
                            SmallBadge(
                                if (display.id == selectedDisplayId) {
                                    stringResource(R.string.diagnostics_display_selected)
                                } else {
                                    stringResource(R.string.diagnostics_display_candidate)
                                },
                                AccentGreen,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilitiesCard(
    capabilities: List<CapabilityAssessment>,
    snapshot: VehicleDiagnosticsSnapshot,
    onConfirm: (CapabilityId, Boolean) -> Unit,
) {
    val userConfirmable = setOf(
        CapabilityId.VEHICLE_COMMANDS,
        CapabilityId.AUTOMATIC_CHARGING,
        CapabilityId.CLUSTER_PROJECTION,
        CapabilityId.FACTORY_HUD,
    )
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                stringResource(R.string.diagnostics_capabilities_title),
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.diagnostics_capabilities_hint),
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
            )
            capabilities.forEachIndexed { index, capability ->
                if (index > 0) HorizontalDivider(color = CardBorder.copy(alpha = 0.45f))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(capabilityHealth(capability.state))
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(
                                capabilityTitle(capability.id),
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            capabilityEvidence(capability)?.let {
                                Text(it, color = TextMuted, fontSize = 11.sp)
                            }
                        }
                        SmallBadge(capabilityStateText(capability.state), capabilityColor(capability.state))
                    }
                    if (capability.id in userConfirmable &&
                        capability.state != CapabilityState.UNAVAILABLE
                    ) {
                        val manuallyConfirmed = snapshot.userConfirmedAtMs[capability.id] != null
                        val actionColor = if (manuallyConfirmed) TextMuted else AccentGreen
                        OutlinedButton(
                            onClick = { onConfirm(capability.id, !manuallyConfirmed) },
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 4.dp)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, actionColor.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = actionColor),
                        ) {
                            Text(
                                stringResource(
                                    if (manuallyConfirmed) R.string.diagnostics_capability_reset
                                    else R.string.diagnostics_capability_confirm,
                                ),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = AccentGreen)
            Text(
                stringResource(R.string.diagnostics_refreshing),
                color = TextSecondary,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun ErrorCard(error: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SocRed.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, SocRed.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.diagnostics_collection_error, error),
            color = SocRed,
            fontSize = 13.sp,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun StatusDot(health: DiagnosticHealth) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.size(10.dp).background(healthColor(health), CircleShape),
    )
}

@Composable
private fun StatusBadge(health: DiagnosticHealth) = SmallBadge(
    text = healthText(health),
    color = healthColor(health),
)

@Composable
private fun SmallBadge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun TechnicalRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(0.42f))
        Text(value, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(0.58f))
    }
}

@Composable
private fun sectionTitle(section: DiagnosticSection): String = stringResource(
    when (section) {
        DiagnosticSection.SERVICE -> R.string.diagnostics_service_title
        DiagnosticSection.ENERGYDATA -> R.string.diagnostics_energydata_title
        DiagnosticSection.BYD_SERVICES -> R.string.diagnostics_byd_services_title
        DiagnosticSection.NAVIGATION -> R.string.diagnostics_navigation_title
        DiagnosticSection.CLUSTER -> R.string.diagnostics_cluster_title
        DiagnosticSection.HUD -> R.string.diagnostics_hud_title
    },
)

@Composable
private fun reasonSummary(reason: DiagnosticReason, s: VehicleDiagnosticsSnapshot): String = when (reason) {
    DiagnosticReason.SERVICE_RUNNING_LIVE -> stringResource(R.string.diagnostics_service_running_live)
    DiagnosticReason.SERVICE_RUNNING_WAITING_DATA -> stringResource(R.string.diagnostics_service_waiting_data)
    DiagnosticReason.SERVICE_DEMO_MODE -> stringResource(R.string.diagnostics_service_demo)
    DiagnosticReason.SERVICE_STOPPED -> stringResource(R.string.diagnostics_service_stopped)
    DiagnosticReason.ENERGYDATA_AVAILABLE -> stringResource(R.string.diagnostics_energydata_available)
    DiagnosticReason.ENERGYDATA_DEAD -> stringResource(R.string.diagnostics_energydata_dead)
    DiagnosticReason.ENERGYDATA_UNAVAILABLE -> stringResource(R.string.diagnostics_energydata_unavailable)
    DiagnosticReason.BYD_CONNECTED -> stringResource(R.string.diagnostics_byd_connected)
    DiagnosticReason.BYD_HELPER_ONLY -> stringResource(R.string.diagnostics_byd_helper_only)
    DiagnosticReason.BYD_DATA_WITHOUT_HELPER -> stringResource(R.string.diagnostics_byd_data_without_helper)
    DiagnosticReason.BYD_DISCONNECTED -> stringResource(R.string.diagnostics_byd_disconnected)
    DiagnosticReason.WAZE_GUIDANCE_VISIBLE -> stringResource(R.string.diagnostics_waze_guidance_visible)
    DiagnosticReason.WAZE_GUIDANCE_FALLBACK -> stringResource(R.string.diagnostics_waze_guidance_fallback)
    DiagnosticReason.WAZE_READY_ROUTE_INACTIVE -> stringResource(R.string.diagnostics_waze_ready)
    DiagnosticReason.WAZE_ROUTE_UNREADABLE -> stringResource(R.string.diagnostics_waze_route_unreadable)
    DiagnosticReason.WAZE_WINDOW_UNREACHABLE -> stringResource(R.string.diagnostics_waze_window_unreachable)
    DiagnosticReason.WAZE_ACCESSIBILITY_DISCONNECTED -> stringResource(R.string.diagnostics_accessibility_disconnected)
    DiagnosticReason.WAZE_ACCESSIBILITY_DISABLED -> stringResource(R.string.diagnostics_accessibility_disabled)
    DiagnosticReason.WAZE_NOT_INSTALLED -> stringResource(R.string.diagnostics_waze_not_installed)
    DiagnosticReason.CLUSTER_ACTIVE -> s.clusterDisplaySearchElapsedMs?.let {
        stringResource(R.string.diagnostics_cluster_active_after, it)
    } ?: stringResource(R.string.diagnostics_cluster_active)
    DiagnosticReason.CLUSTER_WAITING_FOR_DISPLAY -> stringResource(R.string.diagnostics_cluster_waiting)
    DiagnosticReason.CLUSTER_DISPLAY_READY -> stringResource(R.string.diagnostics_cluster_display_ready)
    DiagnosticReason.CLUSTER_FAILED_DAEMON -> stringResource(R.string.diagnostics_cluster_daemon_failed)
    DiagnosticReason.CLUSTER_FAILED_DISPLAY -> stringResource(
        R.string.diagnostics_cluster_display_failed,
        s.clusterDisplaySearchElapsedMs ?: 0L,
    )
    DiagnosticReason.CLUSTER_FAILED_OTHER -> stringResource(
        R.string.diagnostics_cluster_other_failed,
        s.clusterFailure ?: "?",
    )
    DiagnosticReason.CLUSTER_NO_DISPLAY -> stringResource(R.string.diagnostics_cluster_no_display)
    DiagnosticReason.CLUSTER_DISABLED -> stringResource(R.string.diagnostics_cluster_disabled)
    DiagnosticReason.HUD_FRAME_ACCEPTED -> stringResource(R.string.diagnostics_hud_frame_accepted)
    DiagnosticReason.HUD_CHANNEL_READY -> stringResource(R.string.diagnostics_hud_channel_ready)
    DiagnosticReason.HUD_CONNECTING -> stringResource(R.string.diagnostics_hud_connecting)
    DiagnosticReason.HUD_BIND_FAILED -> stringResource(R.string.diagnostics_hud_bind_failed)
    DiagnosticReason.HUD_SEND_FAILED -> stringResource(R.string.diagnostics_hud_send_failed)
    DiagnosticReason.HUD_UNSUPPORTED -> stringResource(R.string.diagnostics_hud_unsupported)
    DiagnosticReason.HUD_ENABLED_BUT_OFF -> stringResource(R.string.diagnostics_hud_enabled_off)
    DiagnosticReason.HUD_DISABLED -> stringResource(R.string.diagnostics_hud_disabled)
}

@Composable
private fun technicalRows(
    section: DiagnosticSection,
    s: VehicleDiagnosticsSnapshot,
): List<Pair<String, String>> = when (section) {
    DiagnosticSection.SERVICE -> listOf(
        stringResource(R.string.diagnostics_tech_service) to yesNo(s.serviceRunning),
        stringResource(R.string.diagnostics_tech_demo_mode) to yesNo(s.demoModeEnabled),
        stringResource(R.string.diagnostics_tech_started) to timeOrNever(s.serviceStartedAtMs),
        stringResource(R.string.diagnostics_tech_data_connection) to yesNo(s.vehicleDataConnected),
        stringResource(R.string.diagnostics_tech_last_data) to timeOrNever(s.lastVehicleDataAtMs),
    )
    DiagnosticSection.ENERGYDATA -> listOf(
        stringResource(R.string.diagnostics_tech_readable_db) to yesNo(s.energyDataAvailable),
        stringResource(R.string.diagnostics_tech_liveness) to s.energyDataDebug,
        stringResource(R.string.diagnostics_tech_fingerprint) to
            "${s.energyDataFingerprintMtimeMs ?: "—"} / ${s.energyDataFingerprintSizeBytes ?: "—"} B",
        stringResource(R.string.diagnostics_tech_imported_trips) to s.energyDataTripCount.toString(),
    )
    DiagnosticSection.BYD_SERVICES -> listOf(
        stringResource(R.string.diagnostics_tech_helper) to yesNo(s.helperAlive),
        stringResource(R.string.diagnostics_tech_native_snapshot) to yesNo(s.vehicleSnapshotPresent),
        stringResource(R.string.diagnostics_tech_data_connection) to yesNo(s.vehicleDataConnected),
        stringResource(R.string.diagnostics_tech_last_data) to timeOrNever(s.lastVehicleDataAtMs),
    )
    DiagnosticSection.NAVIGATION -> listOf(
        "Waze" to (s.wazeVersion ?: stringResource(R.string.diagnostics_not_installed)),
        stringResource(R.string.diagnostics_tech_accessibility_enabled) to yesNo(s.accessibilityEnabled),
        stringResource(R.string.diagnostics_tech_accessibility_connected) to yesNo(s.accessibilityConnected),
        stringResource(R.string.diagnostics_tech_waze_window) to wazeWindowText(s.wazeWindowState),
        stringResource(R.string.diagnostics_tech_feed) to yesNo(s.wazeFeedEnabled),
        stringResource(R.string.diagnostics_tech_route_active) to yesNo(s.routeActive),
        stringResource(R.string.diagnostics_tech_route_source) to (s.routeSource ?: "—"),
        stringResource(R.string.diagnostics_tech_route_maneuver) to
            if (s.routeManeuverGaode > 0) s.routeManeuverGaode.toString() else "—",
        stringResource(R.string.diagnostics_tech_route_renderable) to yesNo(s.routeRenderable),
        stringResource(R.string.diagnostics_tech_route_last_update) to
            timeOrNever(s.routeLastUpdateAtMs),
        stringResource(R.string.diagnostics_tech_route_last_observed) to
            timeOrNever(s.routeLastObservedAtMs),
        stringResource(R.string.diagnostics_tech_route_end) to buildString {
            append(s.routeEndReason ?: "—")
            s.routeEndedAtMs?.let { append(" @ ").append(timeText(it)) }
        },
        stringResource(R.string.diagnostics_tech_last_probe) to
            (s.wazeLastProbeResult ?: "—"),
        stringResource(R.string.diagnostics_tech_last_event) to timeOrNever(s.wazeLastEventAtMs),
        stringResource(R.string.diagnostics_tech_last_readable) to timeOrNever(s.wazeLastReadableAtMs),
        stringResource(R.string.diagnostics_tech_last_no_guidance) to
            timeOrNever(s.wazeLastNoGuidanceAtMs),
        stringResource(R.string.diagnostics_tech_last_window_loss) to
            timeOrNever(s.wazeLastWindowUnreachableAtMs),
        stringResource(R.string.diagnostics_tech_last_unreadable) to
            timeOrNever(s.wazeLastUnreadableAtMs),
    )
    DiagnosticSection.CLUSTER -> listOf(
        stringResource(R.string.diagnostics_tech_enabled) to yesNo(s.clusterEnabled),
        stringResource(R.string.diagnostics_tech_mode) to s.clusterMode,
        stringResource(R.string.diagnostics_tech_phase) to s.clusterPhase.name,
        stringResource(R.string.diagnostics_tech_attempt) to timeOrNever(s.clusterLastAttemptAtMs),
        stringResource(R.string.diagnostics_tech_display_wait) to
            s.clusterDisplaySearchElapsedMs?.let { "$it ms" }.orEmpty().ifBlank { "—" },
        stringResource(R.string.diagnostics_tech_selected_display) to
            (s.clusterSelectedDisplayId?.toString() ?: "—"),
        stringResource(R.string.diagnostics_tech_monitored_display) to
            (s.clusterMonitoredDisplayId?.toString() ?: "—"),
        stringResource(R.string.diagnostics_tech_last_display_event) to buildString {
            append(s.clusterLastDisplayEvent ?: "—")
            s.clusterLastDisplayEventAtMs?.let { append(" @ ").append(timeText(it)) }
        },
        stringResource(R.string.diagnostics_tech_last_success) to timeOrNever(s.clusterLastSuccessAtMs),
        stringResource(R.string.diagnostics_tech_last_failure) to (s.clusterFailure ?: "—"),
    )
    DiagnosticSection.HUD -> {
        val incident = s.hudLastIncident
        listOf(
            stringResource(R.string.diagnostics_tech_enabled) to yesNo(s.hudEnabled),
            "SOME/IP gateway" to yesNo(s.hudGatewayPresent),
            stringResource(R.string.diagnostics_tech_state) to s.hudState.name,
            stringResource(R.string.diagnostics_tech_last_attempt) to timeOrNever(s.hudLastAttemptAtMs),
            stringResource(R.string.diagnostics_tech_last_success) to timeOrNever(s.hudLastSuccessAtMs),
            stringResource(R.string.diagnostics_tech_delivery_kind) to
                (s.hudLastDeliveryKind ?: "—"),
            stringResource(R.string.diagnostics_tech_guidance_success) to
                timeOrNever(s.hudLastGuidanceSuccessAtMs),
            stringResource(R.string.diagnostics_tech_clear_attempt) to
                timeOrNever(s.hudLastClearAttemptAtMs),
            stringResource(R.string.diagnostics_tech_clear_success) to
                timeOrNever(s.hudLastClearSuccessAtMs),
            stringResource(R.string.diagnostics_tech_result_code) to (s.hudLastResultCode?.toString() ?: "—"),
            stringResource(R.string.diagnostics_tech_last_failure) to (s.hudFailure ?: "—"),
            stringResource(R.string.diagnostics_tech_reconnect_attempt) to s.hudReconnectAttempt.toString(),
            stringResource(R.string.diagnostics_tech_next_reconnect) to timeOrNever(s.hudNextReconnectAtMs),
            stringResource(R.string.diagnostics_tech_last_recovery) to timeOrNever(s.hudLastRecoveredAtMs),
            stringResource(R.string.diagnostics_tech_hud_incident_count) to
                s.hudIncidentCount.toString(),
            stringResource(R.string.diagnostics_tech_hud_last_incident) to
                hudIncidentCauseText(incident?.cause),
            stringResource(R.string.diagnostics_tech_hud_incident_detected) to
                incident?.detectedAtMs?.let(::timeText).orEmpty().ifBlank { "—" },
            stringResource(R.string.diagnostics_tech_hud_incident_outage) to
                incident?.outageBeforeDetectionMs?.let {
                    stringResource(R.string.diagnostics_hud_incident_outage_seconds, it / 1_000f)
                }.orEmpty().ifBlank { "—" },
            stringResource(R.string.diagnostics_tech_hud_incident_recovered) to when {
                incident == null -> "—"
                incident.recoveredAtMs == null ->
                    stringResource(R.string.diagnostics_hud_incident_not_recovered)
                else -> timeText(incident.recoveredAtMs)
            },
            stringResource(R.string.diagnostics_tech_hud_incident_details) to
                (incident?.let(::hudIncidentEvidence) ?: "—"),
        )
    }
}

@Composable
private fun hudIncidentCauseText(cause: HudIncidentCause?): String = stringResource(
    when (cause) {
        HudIncidentCause.SERVICE_RESTART -> R.string.diagnostics_hud_incident_service_restart
        HudIncidentCause.ACCESSIBILITY -> R.string.diagnostics_hud_incident_accessibility
        HudIncidentCause.WAZE_WINDOW -> R.string.diagnostics_hud_incident_waze_window
        HudIncidentCause.WAZE_DATA -> R.string.diagnostics_hud_incident_waze_data
        HudIncidentCause.SOME_IP -> R.string.diagnostics_hud_incident_some_ip
        HudIncidentCause.HUD_PIPELINE -> R.string.diagnostics_hud_incident_pipeline
        null -> R.string.diagnostics_hud_incident_none
    },
)

private fun hudIncidentEvidence(incident: HudIncident): String =
    "status=${incident.hudStatus}; rc=${incident.resultCode ?: "—"}; " +
        "failure=${incident.failure ?: "—"}; route=${incident.routeSource ?: "—"}; " +
        "routeAge=${incident.routeAgeMs ?: "—"} ms; " +
        "observedAge=${incident.routeObservedAgeMs ?: "—"} ms; " +
        "maneuver=${incident.routeManeuverGaode}; renderable=${incident.routeRenderable}; " +
        "routeEnd=${incident.routeEndReason ?: "—"}; " +
        "a11y=${incident.accessibilityConnected}; feed=${incident.feedEnabled}; " +
        "window=${incident.wazeWindowReachable ?: "—"}; " +
        "eventAge=${incident.wazeEventAgeMs ?: "—"} ms; " +
        "guidanceAge=${incident.wazeGuidanceAgeMs ?: "—"} ms; " +
        "noGuidanceAge=${incident.wazeNoGuidanceAgeMs ?: "—"} ms; " +
        "windowLossAge=${incident.wazeWindowUnreachableAgeMs ?: "—"} ms; " +
        "unreadableAge=${incident.wazeUnreadableAgeMs ?: "—"} ms; " +
        "probe=${incident.wazeProbeResult ?: "—"}"

@Composable
private fun healthText(health: DiagnosticHealth): String = stringResource(
    when (health) {
        DiagnosticHealth.HEALTHY -> R.string.diagnostics_status_ok
        DiagnosticHealth.ATTENTION -> R.string.diagnostics_status_attention
        DiagnosticHealth.ERROR -> R.string.diagnostics_status_error
        DiagnosticHealth.CHECKING -> R.string.diagnostics_status_checking
        DiagnosticHealth.DISABLED -> R.string.diagnostics_status_disabled
        DiagnosticHealth.UNKNOWN -> R.string.diagnostics_status_unknown
    },
)

private fun healthColor(health: DiagnosticHealth): Color = when (health) {
    DiagnosticHealth.HEALTHY -> AccentGreen
    DiagnosticHealth.ATTENTION -> AccentOrange
    DiagnosticHealth.ERROR -> SocRed
    DiagnosticHealth.CHECKING -> AccentBlue
    DiagnosticHealth.DISABLED, DiagnosticHealth.UNKNOWN -> TextMuted
}

private fun capabilityHealth(state: CapabilityState): DiagnosticHealth = when (state) {
    CapabilityState.CONFIRMED -> DiagnosticHealth.HEALTHY
    CapabilityState.EXPERIMENTAL -> DiagnosticHealth.ATTENTION
    CapabilityState.NOT_TESTED -> DiagnosticHealth.UNKNOWN
    CapabilityState.UNAVAILABLE -> DiagnosticHealth.ERROR
}

private fun capabilityColor(state: CapabilityState): Color = healthColor(capabilityHealth(state))

@Composable
private fun capabilityStateText(state: CapabilityState): String = stringResource(
    when (state) {
        CapabilityState.CONFIRMED -> R.string.diagnostics_capability_confirmed
        CapabilityState.EXPERIMENTAL -> R.string.diagnostics_capability_experimental
        CapabilityState.NOT_TESTED -> R.string.diagnostics_capability_not_tested
        CapabilityState.UNAVAILABLE -> R.string.diagnostics_capability_unavailable
    },
)

@Composable
private fun capabilityTitle(id: CapabilityId): String = stringResource(
    when (id) {
        CapabilityId.LIVE_TELEMETRY -> R.string.diagnostics_capability_live_telemetry
        CapabilityId.ENERGYDATA_TRIPS -> R.string.diagnostics_capability_energydata
        CapabilityId.BATTERY_METRICS -> R.string.diagnostics_capability_battery
        CapabilityId.VEHICLE_COMMANDS -> R.string.diagnostics_capability_commands
        CapabilityId.AUTOMATIC_CHARGING -> R.string.diagnostics_capability_charging
        CapabilityId.WAZE_GUIDANCE -> R.string.diagnostics_capability_waze
        CapabilityId.CLUSTER_PROJECTION -> R.string.diagnostics_capability_cluster
        CapabilityId.FACTORY_HUD -> R.string.diagnostics_capability_hud
    },
)

@Composable
private fun capabilityEvidence(capability: CapabilityAssessment): String? {
    val time = capability.evidenceAtMs?.let(::timeText)
    return when (capability.id) {
        CapabilityId.ENERGYDATA_TRIPS -> capability.evidenceLabel?.let {
            stringResource(R.string.diagnostics_evidence_trip_count, it)
        }
        CapabilityId.BATTERY_METRICS -> capability.evidenceLabel?.let {
            stringResource(R.string.diagnostics_evidence_sensor_count, it)
        }
        CapabilityId.VEHICLE_COMMANDS -> capability.evidenceLabel?.let {
            if (time == null) it else stringResource(R.string.diagnostics_evidence_command, it, time)
        }
        else -> time?.let { stringResource(R.string.diagnostics_evidence_at, it) }
    }
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(
    if (value) R.string.diagnostics_yes else R.string.diagnostics_no,
)

@Composable
private fun timeOrNever(value: Long?): String = value?.let(::timeText)
    ?: stringResource(R.string.diagnostics_never)

@Composable
private fun wazeWindowText(state: WazeWindowState): String = stringResource(
    when (state) {
        WazeWindowState.NOT_CHECKED -> R.string.diagnostics_waze_window_not_checked
        WazeWindowState.NO_WINDOW -> R.string.diagnostics_waze_window_none
        WazeWindowState.WINDOW_VISIBLE -> R.string.diagnostics_waze_window_visible
        WazeWindowState.ROUTE_UNREADABLE -> R.string.diagnostics_waze_window_route_unreadable
        WazeWindowState.GUIDANCE_VISIBLE -> R.string.diagnostics_waze_window_guidance
    },
)

@Composable
private fun displayState(state: Int): String = stringResource(
    when (state) {
        Display.STATE_OFF -> R.string.diagnostics_display_state_off
        Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> R.string.diagnostics_display_state_doze
        Display.STATE_ON -> R.string.diagnostics_display_state_on
        else -> R.string.diagnostics_status_unknown
    },
)

private fun timeText(value: Long): String =
    DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.MEDIUM,
        Locale.getDefault(),
    ).format(Date(value))
