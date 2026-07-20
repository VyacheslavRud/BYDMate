package com.bydmate.app.cluster

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 32])
class ClusterLabLogStoreTest {
    private lateinit var context: Context
    private var exported: File? = null

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ClusterLabLogStore.clearForTest(context)
    }

    @After fun tearDown() {
        exported?.delete()
        ClusterLabLogStore.clearForTest(context)
    }

    @Test fun `timeline cleanup and observation survive persistent reload`() {
        val scenario = checkNotNull(ClusterLabScenarioCatalog.byId("C03"))
        val record = ClusterLabLogStore.begin(
            context = context,
            scenario = scenario,
            autoContainerEnabled = false,
            compositorOwnershipPending = false,
            gear = 1,
            speedKmh = 0,
            nowMs = 1_000L,
        )
        ClusterLabLogStore.append(
            context,
            record.id,
            ClusterLabEvent(
                atMs = 1_200L,
                elapsedMs = 200L,
                kind = ClusterLabEventKind.OVERLAY_ADDED,
                detail = "display=7 expected=614x648+1306+36",
                displays = listOf(
                    ClusterLabDisplaySnapshot(
                        id = 7,
                        name = "XDJAScreenProjection_1",
                        widthPx = 1920,
                        heightPx = 720,
                        densityDpi = 320,
                        state = 2,
                        clusterCandidate = true,
                    ),
                ),
                gear = 1,
                speedKmh = 0,
            ),
        )
        ClusterLabLogStore.finish(
            context,
            record.id,
            failure = null,
            cleanupConfirmed = true,
            nowMs = 9_000L,
        )
        ClusterLabLogStore.recordObservation(
            context,
            record.id,
            ClusterLabObservation.VISIBLE,
            nowMs = 10_000L,
        )

        val restored = ClusterLabLogStore.records(context).single()
        assertEquals("C03", restored.scenarioId)
        assertEquals(ClusterLabMutation.APP_OVERLAY, restored.mutation)
        assertEquals(true, restored.cleanupConfirmed)
        assertNull(restored.failure)
        assertEquals(ClusterLabObservation.VISIBLE, restored.observed)
        assertEquals(4, restored.events.size)
        assertEquals("XDJAScreenProjection_1", restored.events[1].displays.single().name)

        val section = ClusterLabLogStore.renderDiagnosticSection(context)
        assertTrue(section.contains("--- Instrument Cluster Lab ---"))
        assertTrue(section.contains("scenario=C03"))
        assertTrue(section.contains("kind=OVERLAY_ADDED"))
        assertTrue(section.contains("cleanupConfirmed=true"))
    }

    @Test fun `blocked projection attempt records guard state without claiming cleanup failure`() {
        val scenario = checkNotNull(ClusterLabScenarioCatalog.byId("C04"))
        val record = ClusterLabLogStore.begin(
            context = context,
            scenario = scenario,
            autoContainerEnabled = true,
            compositorOwnershipPending = false,
            gear = 1,
            speedKmh = 0,
            nowMs = 1_000L,
        )
        ClusterLabLogStore.finish(
            context,
            record.id,
            failure = ClusterLabFailure.AUTO_CONTAINER_ENABLED,
            cleanupConfirmed = true,
            nowMs = 1_010L,
        )

        val restored = ClusterLabLogStore.records(context).single()
        assertEquals(ClusterLabFailure.AUTO_CONTAINER_ENABLED, restored.failure)
        assertTrue(restored.autoContainerEnabled)
        assertEquals(true, restored.cleanupConfirmed)
        assertFalse(restored.compositorOwnershipPending)
    }

    @Test fun `standalone export contains the complete cluster timeline`() {
        val scenario = checkNotNull(ClusterLabScenarioCatalog.byId("C01"))
        val record = ClusterLabLogStore.begin(
            context,
            scenario,
            autoContainerEnabled = false,
            compositorOwnershipPending = false,
            gear = 1,
            speedKmh = 0,
            nowMs = 1_000L,
        )
        ClusterLabLogStore.finish(context, record.id, null, true, nowMs = 2_000L)

        exported = ClusterLabLogStore.export(context, nowMs = 3_000L)

        assertTrue(exported!!.exists())
        val report = exported!!.readText()
        assertTrue(report.contains("=== BYDMate Instrument Cluster Lab export ==="))
        assertTrue(report.contains("scenario=C01"))
        assertTrue(report.contains("kind=START"))
        assertTrue(report.contains("kind=COMPLETE"))
        assertTrue(report.contains("vehicle:"))
    }
}

