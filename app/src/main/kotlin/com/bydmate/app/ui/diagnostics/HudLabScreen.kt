package com.bydmate.app.ui.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.hud.HudLabLogStore
import com.bydmate.app.hud.HudLabObserved
import com.bydmate.app.hud.HudLabOutcome
import com.bydmate.app.hud.HudLabOutcomeType
import com.bydmate.app.hud.HudLabScenario
import com.bydmate.app.hud.HudLabScenarioCatalog
import com.bydmate.app.hud.HudLabSendFailure
import com.bydmate.app.hud.HudLabState
import com.bydmate.app.ui.theme.AccentBlue
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.AccentPurple
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.NavyDeep
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

@Composable
fun HudLabScreen(
    onBack: () -> Unit,
    viewModel: HudLabViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clusterState by viewModel.clusterLabState.collectAsStateWithLifecycle()
    val externalLabBusy = clusterState.busy || clusterState.pendingObservationRecordId != null
    var selectedCatalog by rememberSaveable { mutableIntStateOf(0) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val scenarios = when (selectedCatalog) {
        0 -> HudLabScenarioCatalog.confirmed
        1 -> HudLabScenarioCatalog.compatibility
        2 -> HudLabScenarioCatalog.explorer
        else -> HudLabScenarioCatalog.extended
    }
    val safeSelectedIndex = selectedIndex.coerceIn(0, scenarios.lastIndex.coerceAtLeast(0))
    var parkConfirmed by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val canSwitchCatalog = !state.busy && state.pending == null && !externalLabBusy

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.diagnostics_hud_lab_delete_title)) },
            text = { Text(stringResource(R.string.diagnostics_hud_lab_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteRecords()
                    },
                ) {
                    Text(stringResource(R.string.diagnostics_hud_lab_delete_confirm), color = SocRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.diagnostics_hud_lab_delete_cancel))
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDark, NavyDeep))),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 430.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                HudLabHeader(onBack)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                HudLabCatalogCard(
                    selectedCatalog = selectedCatalog,
                    enabled = canSwitchCatalog,
                    onSelectConfirmed = {
                        selectedCatalog = 0
                        selectedIndex = 0
                        parkConfirmed = false
                    },
                    onSelectCompatibility = {
                        selectedCatalog = 1
                        selectedIndex = 0
                        parkConfirmed = false
                    },
                    onSelectExtended = {
                        selectedCatalog = 3
                        selectedIndex = 0
                        parkConfirmed = false
                    },
                    onSelectExplorer = {
                        selectedCatalog = 2
                        selectedIndex = HudLabScenarioCatalog.explorer.indexOfFirst {
                            it.id !in state.completedExplorerScenarioIds
                        }.takeIf { it >= 0 } ?: 0
                        parkConfirmed = false
                    },
                )
            }
            if (scenarios.isNotEmpty()) {
                item {
                    HudLabRunnerCard(
                        state = state,
                        scenario = scenarios[safeSelectedIndex],
                        selectedIndex = safeSelectedIndex,
                        totalScenarios = scenarios.size,
                        explorerMode = selectedCatalog == 2,
                        explorerCompletedCount = state.completedExplorerScenarioIds.size,
                        parkConfirmed = parkConfirmed,
                        externalLabBusy = externalLabBusy,
                        onParkConfirmedChange = { parkConfirmed = it },
                        onPrevious = { selectedIndex = (selectedIndex - 1).coerceAtLeast(0) },
                        onNext = {
                            selectedIndex = (selectedIndex + 1).coerceAtMost(scenarios.lastIndex)
                        },
                        onRun = {
                            viewModel.sendScenario(scenarios[safeSelectedIndex].id, parkConfirmed)
                        },
                        onObserved = { observed ->
                            viewModel.recordObservation(observed)
                            if (safeSelectedIndex < scenarios.lastIndex) {
                                selectedIndex = safeSelectedIndex + 1
                            }
                        },
                        onNamedIndicator = { label ->
                            viewModel.recordNamedIndicator(label)
                            if (safeSelectedIndex < scenarios.lastIndex) {
                                selectedIndex = safeSelectedIndex + 1
                            }
                        },
                        onRepeatRequested = {
                            viewModel.recordObservation(HudLabObserved.NOT_REPORTED)
                        },
                        onClear = viewModel::clearHud,
                    )
                }
            }
            item {
                HudLabJournalCard(
                    state = state,
                    onExport = viewModel::export,
                    onDelete = { showDeleteConfirmation = true },
                )
            }
        }
    }
}

@Composable
private fun HudLabCatalogCard(
    selectedCatalog: Int,
    enabled: Boolean,
    onSelectConfirmed: () -> Unit,
    onSelectCompatibility: () -> Unit,
    onSelectExtended: () -> Unit,
    onSelectExplorer: () -> Unit,
) {
    val accent = when (selectedCatalog) {
        0 -> AccentBlue
        1 -> AccentOrange
        2 -> AccentGreen
        else -> AccentPurple
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                stringResource(R.string.diagnostics_hud_lab_catalog_title),
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HudLabCatalogButton(
                    selected = selectedCatalog == 0,
                    label = stringResource(R.string.diagnostics_hud_lab_catalog_confirmed),
                    accent = AccentBlue,
                    enabled = enabled,
                    onClick = onSelectConfirmed,
                    modifier = Modifier.weight(1f),
                )
                HudLabCatalogButton(
                    selected = selectedCatalog == 3,
                    label = stringResource(R.string.diagnostics_hud_lab_catalog_extended),
                    accent = AccentPurple,
                    enabled = enabled,
                    onClick = onSelectExtended,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HudLabCatalogButton(
                    selected = selectedCatalog == 1,
                    label = stringResource(R.string.diagnostics_hud_lab_catalog_compatibility),
                    accent = AccentOrange,
                    enabled = enabled,
                    onClick = onSelectCompatibility,
                    modifier = Modifier.weight(1f),
                )
                HudLabCatalogButton(
                    selected = selectedCatalog == 2,
                    label = stringResource(R.string.diagnostics_hud_lab_catalog_explorer),
                    accent = AccentGreen,
                    enabled = enabled,
                    onClick = onSelectExplorer,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(
                    when (selectedCatalog) {
                        0 -> R.string.diagnostics_hud_lab_catalog_confirmed_hint
                        1 -> R.string.diagnostics_hud_lab_catalog_compatibility_hint
                        2 -> R.string.diagnostics_hud_lab_catalog_explorer_hint
                        else -> R.string.diagnostics_hud_lab_catalog_extended_hint
                    },
                ),
                color = if (selectedCatalog == 0) TextSecondary else accent,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun HudLabCatalogButton(
    selected: Boolean,
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent),
        ) {
            Text(label, maxLines = 2)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
        ) {
            Text(label, maxLines = 2)
        }
    }
}

@Composable
private fun HudLabHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.diagnostics_back_cd),
                tint = TextPrimary,
            )
        }
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                stringResource(R.string.diagnostics_hud_lab_title),
                color = TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.diagnostics_hud_lab_description),
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Text(
                stringResource(R.string.diagnostics_hud_lab_native_hint),
                color = AccentBlue,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun HudLabRunnerCard(
    state: HudLabState,
    scenario: HudLabScenario,
    selectedIndex: Int,
    totalScenarios: Int,
    explorerMode: Boolean,
    explorerCompletedCount: Int,
    parkConfirmed: Boolean,
    externalLabBusy: Boolean,
    onParkConfirmedChange: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRun: () -> Unit,
    onObserved: (HudLabObserved) -> Unit,
    onNamedIndicator: (String) -> Unit,
    onRepeatRequested: () -> Unit,
    onClear: () -> Unit,
) {
    val pending = state.pending
    val pendingIsExplorer = HudLabScenarioCatalog.isExplorerScenario(pending?.record?.scenarioId)
    var indicatorLabel by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(pending?.record?.id) { indicatorLabel = "" }
    val controlsEnabled = !state.busy && pending == null && !externalLabBusy
    val canRun = controlsEnabled && parkConfirmed
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AccentBlue.copy(alpha = 0.09f)),
        border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.42f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(
                    R.string.diagnostics_hud_lab_test_counter,
                    selectedIndex + 1,
                    totalScenarios,
                ),
                color = AccentBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (explorerMode) {
                Text(
                    stringResource(
                        R.string.diagnostics_hud_lab_explorer_progress,
                        explorerCompletedCount.coerceAtMost(totalScenarios),
                        totalScenarios,
                    ),
                    color = AccentGreen,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                "${scenario.id} · ${scenario.title}",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                scenario.summary,
                color = TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPrevious,
                    enabled = controlsEnabled && selectedIndex > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) {
                    Text(stringResource(R.string.diagnostics_hud_lab_previous))
                }
                OutlinedButton(
                    onClick = onNext,
                    enabled = controlsEnabled && selectedIndex < totalScenarios - 1,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) {
                    Text(stringResource(R.string.diagnostics_hud_lab_next))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = controlsEnabled) {
                        onParkConfirmedChange(!parkConfirmed)
                    }
                    .padding(top = 8.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = parkConfirmed,
                    onCheckedChange = onParkConfirmedChange,
                    enabled = controlsEnabled,
                )
                Text(
                    stringResource(R.string.diagnostics_hud_lab_park_confirmation),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            Button(
                onClick = onRun,
                enabled = canRun,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentBlue,
                    contentColor = TextPrimary,
                ),
            ) {
                Text(stringResource(R.string.diagnostics_hud_lab_run_selected))
            }
            Text(
                stringResource(R.string.diagnostics_hud_lab_auto_clear),
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (externalLabBusy) {
                Text(
                    stringResource(R.string.diagnostics_hud_lab_cluster_busy),
                    color = AccentOrange,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (state.busy && state.totalPushes > 0) {
                val progress = state.currentPush.toFloat() / state.totalPushes.toFloat()
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    color = AccentBlue,
                )
                Text(
                    "${state.currentScenarioId}: ${state.currentPush}/${state.totalPushes}",
                    color = AccentOrange,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (pending != null) {
                HorizontalDivider(
                    color = CardBorder.copy(alpha = 0.55f),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Text(
                    "${pending.record.scenarioId} · ${pending.record.scenarioTitle}",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (pending.autoCleared) {
                    Text(
                        stringResource(R.string.diagnostics_hud_lab_already_cleared),
                        color = AccentOrange,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (pendingIsExplorer) {
                    Text(
                        stringResource(R.string.diagnostics_hud_lab_explorer_what_seen),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    OutlinedTextField(
                        value = indicatorLabel,
                        onValueChange = {
                            indicatorLabel = it.take(HudLabLogStore.MAX_USER_LABEL_CHARS)
                        },
                        enabled = !state.busy,
                        label = {
                            Text(stringResource(R.string.diagnostics_hud_lab_explorer_label))
                        },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.diagnostics_hud_lab_explorer_label_counter,
                                    indicatorLabel.length,
                                    HudLabLogStore.MAX_USER_LABEL_CHARS,
                                ),
                            )
                        },
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onNamedIndicator(indicatorLabel) },
                        enabled = !state.busy && indicatorLabel.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    ) {
                        Text(stringResource(R.string.diagnostics_hud_lab_explorer_save_next))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onObserved(HudLabObserved.STRAIGHT) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) {
                            Text(
                                stringResource(R.string.diagnostics_hud_lab_explorer_straight),
                                maxLines = 2,
                            )
                        }
                        OutlinedButton(
                            onClick = { onObserved(HudLabObserved.NOTHING) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) {
                            Text(
                                stringResource(R.string.diagnostics_hud_lab_explorer_nothing),
                                maxLines = 2,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onObserved(HudLabObserved.FLASHED) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) {
                            Text(hudLabObservedText(HudLabObserved.FLASHED), maxLines = 2)
                        }
                        OutlinedButton(
                            onClick = { onObserved(HudLabObserved.VISIBLE_UNDESCRIBED) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        ) {
                            Text(
                                stringResource(R.string.diagnostics_hud_lab_explorer_skip),
                                maxLines = 2,
                            )
                        }
                    }
                    TextButton(
                        onClick = onRepeatRequested,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.diagnostics_hud_lab_explorer_repeat))
                    }
                } else {
                    Text(
                        stringResource(R.string.diagnostics_hud_lab_what_seen),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    observationOptions(
                        pending.record.scenarioId,
                        pending.record.expected,
                    ).chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { observed ->
                                OutlinedButton(
                                    onClick = { onObserved(observed) },
                                    enabled = !state.busy,
                                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                                    border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.55f)),
                                ) {
                                    Text(hudLabObservedText(observed), maxLines = 2, fontSize = 11.sp)
                                }
                            }
                            if (row.size == 1) Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            state.lastOutcome?.takeUnless { outcome ->
                outcome.type in setOf(
                    HudLabOutcomeType.EXPORTED,
                    HudLabOutcomeType.EXPORT_FAILED,
                    HudLabOutcomeType.RECORDS_DELETED,
                    HudLabOutcomeType.RECORDS_DELETE_FAILED,
                )
            }?.let { outcome ->
                Text(
                    hudLabOutcomeText(outcome),
                    color = if (outcome.type in setOf(
                            HudLabOutcomeType.SEND_REJECTED,
                            HudLabOutcomeType.EXPORT_FAILED,
                            HudLabOutcomeType.RECORDS_DELETE_FAILED,
                        )
                    ) SocRed else AccentGreen,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            OutlinedButton(
                onClick = onClear,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 46.dp),
                border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.7f)),
            ) {
                Text(stringResource(R.string.diagnostics_hud_lab_clear))
            }
        }
    }
}

@Composable
private fun HudLabJournalCard(
    state: HudLabState,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(alpha = 0.07f)),
        border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(R.string.diagnostics_hud_lab_journal_title),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.diagnostics_hud_lab_saved_count, state.recordsCount),
                color = AccentGreen,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                stringResource(R.string.diagnostics_hud_lab_journal_hint),
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = onExport,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    contentColor = NavyDark,
                ),
            ) {
                Text(stringResource(R.string.diagnostics_hud_lab_export))
            }
            OutlinedButton(
                onClick = onDelete,
                enabled = !state.busy && state.pending == null && state.recordsCount > 0,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
                border = BorderStroke(1.dp, SocRed.copy(alpha = 0.75f)),
            ) {
                Text(stringResource(R.string.diagnostics_hud_lab_delete_records), color = SocRed)
            }
            state.lastOutcome?.takeIf { outcome ->
                outcome.type in setOf(
                    HudLabOutcomeType.EXPORTED,
                    HudLabOutcomeType.EXPORT_FAILED,
                    HudLabOutcomeType.RECORDS_DELETED,
                    HudLabOutcomeType.RECORDS_DELETE_FAILED,
                )
            }?.let { outcome ->
                Text(
                    hudLabOutcomeText(outcome),
                    color = if (outcome.type in setOf(
                            HudLabOutcomeType.EXPORT_FAILED,
                            HudLabOutcomeType.RECORDS_DELETE_FAILED,
                        )
                    ) SocRed else AccentGreen,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 9.dp),
                )
            }
        }
    }
}

private fun observationOptions(
    scenarioId: String?,
    expected: HudLabObserved,
): List<HudLabObserved> = when {
    scenarioId?.startsWith("SL") == true -> listOf(
        expected,
        HudLabObserved.RIGHT,
        HudLabObserved.LEFT,
        HudLabObserved.STRAIGHT,
        HudLabObserved.DISTANCE_VISIBLE,
        HudLabObserved.ROAD_VISIBLE,
        HudLabObserved.INFO_VISIBLE,
        HudLabObserved.NOTHING,
        HudLabObserved.FLASHED,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    ).distinct()
    scenarioId?.startsWith("U") == true -> listOf(
        HudLabObserved.UTURN,
        HudLabObserved.LEFT,
        HudLabObserved.RIGHT,
        HudLabObserved.STRAIGHT,
        HudLabObserved.NOTHING,
        HudLabObserved.FLASHED,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    )
    scenarioId?.startsWith("R") == true -> listOf(
        HudLabObserved.ROUNDABOUT,
        HudLabObserved.LEFT,
        HudLabObserved.RIGHT,
        HudLabObserved.STRAIGHT,
        HudLabObserved.UTURN,
        HudLabObserved.NOTHING,
        HudLabObserved.FLASHED,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    )
    scenarioId == "HX01" -> listOf(
        HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        HudLabObserved.SPEED_OUTLINE_WITH_MANEUVER_VISIBLE,
        HudLabObserved.SPEED_NUMBER_VISIBLE,
        HudLabObserved.RIGHT,
        HudLabObserved.INFO_VISIBLE,
        HudLabObserved.NOTHING,
        HudLabObserved.FLASHED,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    )
    scenarioId == "HX02" -> listOf(
        HudLabObserved.SPEED_MANEUVER_ETA_VISIBLE,
        HudLabObserved.ETA_VISIBLE,
        HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        HudLabObserved.SPEED_OUTLINE_WITH_MANEUVER_VISIBLE,
        HudLabObserved.INFO_VISIBLE,
        HudLabObserved.NOTHING,
        HudLabObserved.FLASHED,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    )
    scenarioId == "HX03" -> listOf(
        HudLabObserved.SPEED_MANEUVER_PROGRESS_VISIBLE,
        HudLabObserved.PROGRESS_VISIBLE,
        HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        HudLabObserved.SPEED_OUTLINE_WITH_MANEUVER_VISIBLE,
        HudLabObserved.INFO_VISIBLE,
        HudLabObserved.NOTHING,
        HudLabObserved.FLASHED,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    )
    scenarioId == "HX04" -> listOf(
        HudLabObserved.FULL_SCALAR_VISIBLE,
        HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        HudLabObserved.SPEED_OUTLINE_WITH_MANEUVER_VISIBLE,
        HudLabObserved.INFO_VISIBLE,
        HudLabObserved.NOTHING,
        HudLabObserved.FLASHED,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    )
    scenarioId == "HX05" || scenarioId in setOf("N17", "N18") ||
        scenarioId?.startsWith("X") == true -> listOf(
        HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        HudLabObserved.SPEED_OUTLINE_WITH_MANEUVER_VISIBLE,
        HudLabObserved.SPEED_NUMBER_VISIBLE,
        HudLabObserved.SPEED_SIGN_OUTLINE_VISIBLE,
        HudLabObserved.LEFT,
        HudLabObserved.RIGHT,
        HudLabObserved.STRAIGHT,
        HudLabObserved.UTURN,
        HudLabObserved.NOTHING,
        HudLabObserved.FLASHED,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    )
    scenarioId?.startsWith("N") == true || scenarioId?.startsWith("S") == true -> listOf(
        HudLabObserved.SPEED_NUMBER_VISIBLE,
        HudLabObserved.SPEED_SIGN_OUTLINE_VISIBLE,
        HudLabObserved.INFO_VISIBLE,
        HudLabObserved.STRAIGHT,
        HudLabObserved.NOTHING,
        HudLabObserved.FLASHED,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    )
    else -> listOf(
        expected,
        HudLabObserved.INFO_VISIBLE,
        HudLabObserved.NOTHING,
        HudLabObserved.FLASHED,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    ).distinct()
}

@Composable
private fun hudLabObservedText(observed: HudLabObserved): String = stringResource(
    when (observed) {
        HudLabObserved.LEFT -> R.string.diagnostics_hud_lab_saw_left
        HudLabObserved.RIGHT -> R.string.diagnostics_hud_lab_saw_right
        HudLabObserved.STRAIGHT -> R.string.diagnostics_hud_lab_saw_straight
        HudLabObserved.UTURN -> R.string.diagnostics_hud_lab_saw_uturn
        HudLabObserved.ROUNDABOUT -> R.string.diagnostics_hud_lab_saw_roundabout
        HudLabObserved.NOTHING -> R.string.diagnostics_hud_lab_saw_nothing
        HudLabObserved.FLASHED -> R.string.diagnostics_hud_lab_saw_flashed
        HudLabObserved.INFO_VISIBLE -> R.string.diagnostics_hud_lab_saw_info
        HudLabObserved.DISTANCE_VISIBLE -> R.string.diagnostics_hud_lab_saw_distance
        HudLabObserved.ROAD_VISIBLE -> R.string.diagnostics_hud_lab_saw_road
        HudLabObserved.ETA_VISIBLE -> R.string.diagnostics_hud_lab_saw_eta
        HudLabObserved.PROGRESS_VISIBLE -> R.string.diagnostics_hud_lab_saw_progress
        HudLabObserved.SPEED_NUMBER_VISIBLE -> R.string.diagnostics_hud_lab_saw_speed_number
        HudLabObserved.SPEED_SIGN_OUTLINE_VISIBLE ->
            R.string.diagnostics_hud_lab_saw_speed_outline
        HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE ->
            R.string.diagnostics_hud_lab_saw_speed_number_with_maneuver
        HudLabObserved.SPEED_OUTLINE_WITH_MANEUVER_VISIBLE ->
            R.string.diagnostics_hud_lab_saw_speed_outline_with_maneuver
        HudLabObserved.SPEED_MANEUVER_ROAD_VISIBLE ->
            R.string.diagnostics_hud_lab_saw_speed_maneuver_road
        HudLabObserved.SPEED_MANEUVER_ETA_VISIBLE ->
            R.string.diagnostics_hud_lab_saw_speed_maneuver_eta
        HudLabObserved.SPEED_MANEUVER_PROGRESS_VISIBLE ->
            R.string.diagnostics_hud_lab_saw_speed_maneuver_progress
        HudLabObserved.FULL_SCALAR_VISIBLE -> R.string.diagnostics_hud_lab_saw_full_scalar
        HudLabObserved.SPEED_SIGN_VISIBLE -> R.string.diagnostics_hud_lab_saw_speed_sign
        HudLabObserved.LEFT_THEN_RIGHT -> R.string.diagnostics_hud_lab_saw_left_then_right
        HudLabObserved.RIGHT_CLEARED_AND_REDRAWN -> R.string.diagnostics_hud_lab_saw_redrawn
        HudLabObserved.CLEAR_NOT_VISIBLE -> R.string.diagnostics_hud_lab_saw_clear_not_visible
        HudLabObserved.REVERSED_SEQUENCE -> R.string.diagnostics_hud_lab_saw_reversed_sequence
        HudLabObserved.FIRST_PHASE_ONLY -> R.string.diagnostics_hud_lab_saw_first_only
        HudLabObserved.SECOND_PHASE_ONLY -> R.string.diagnostics_hud_lab_saw_second_only
        HudLabObserved.VISIBLE_UNDESCRIBED ->
            R.string.diagnostics_hud_lab_saw_visible_undescribed
        HudLabObserved.NAMED_INDICATOR -> R.string.diagnostics_hud_lab_saw_named_indicator
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
    HudLabOutcomeType.RECORDS_DELETED ->
        stringResource(R.string.diagnostics_hud_lab_records_deleted)
    HudLabOutcomeType.RECORDS_DELETE_FAILED ->
        stringResource(R.string.diagnostics_hud_lab_records_delete_failed)
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
