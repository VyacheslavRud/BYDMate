package com.bydmate.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight in-memory holder for the most recently completed driving session's
 * SOC bookmarks. Written by TrackingService on session start/end; read by
 * HistoryImporter when enriching freshly-imported TripEntity rows.
 *
 * In-memory only — intentionally not persisted. Process restart mid-session
 * drops the bookmarks; HistoryImporter falls back to null socStart/socEnd for
 * that trip (same as before the native-stack migration). This is acceptable:
 * the SOC enrichment is best-effort and only benefits the current session.
 */
@Singleton
class LastSessionRepository @Inject constructor() {

    data class Snapshot(
        val startSoc: Int?,
        val endSoc: Int?,
        val startTs: Long?,
        val endTs: Long?,
    )

    @Volatile private var startSoc: Int? = null
    @Volatile private var endSoc: Int? = null
    @Volatile private var startTs: Long? = null
    @Volatile private var endTs: Long? = null

    fun onSessionStart(soc: Int?, ts: Long) {
        startSoc = soc
        endSoc = null
        startTs = ts
        endTs = null
    }

    fun onSessionEnd(soc: Int?, ts: Long) {
        endSoc = soc
        endTs = ts
    }

    fun snapshot(): Snapshot = Snapshot(startSoc, endSoc, startTs, endTs)
}
