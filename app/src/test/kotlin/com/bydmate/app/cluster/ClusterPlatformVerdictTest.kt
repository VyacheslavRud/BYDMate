package com.bydmate.app.cluster

import com.bydmate.app.helper.serviceCallReplyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the real Sea Lion 07 probe text captured by Cluster Lab C07 on 2026-07-21.
 *
 * The strings below are copied from the on-car export, only shortened. They are the evidence the
 * audit rests on, so a future platform change has to break these tests rather than a report.
 *
 * Every assertion here is about the Android display path. Whether a native BYD compositor, a
 * separate cluster ECU or a vendor protocol could drive the instrument cluster is outside what
 * these probes can observe, and the tests below assert that the export says so.
 */
class ClusterPlatformVerdictTest {

    @Test fun `C09 CLI printer error is never reported as a captured projection parcel`() {
        val facts = parseAutoContainerProjectionInfoProbe(
            "[auto_container_projection_info] exit=0 " +
                "Result: Parcel(Error: 0xffffffffffffffb6 \"Not a data message\")",
        )

        assertEquals("CLI_REPLY_UNREADABLE", facts.status)
        assertFalse(facts.parcelCaptured)
        assertFalse(facts.usable)
    }

    @Test fun `C09 typed parcel distinguishes capture from usable success`() {
        val captured = parseAutoContainerProjectionInfoProbe(
            "[auto_container_projection_info] status=PARCEL_CAPTURED " +
                "serviceResult=0 parcelCaptured=true usable=true",
        )
        val rejected = parseAutoContainerProjectionInfoProbe(
            "[auto_container_projection_info] status=SERVICE_NON_ZERO " +
                "serviceResult=-1 parcelCaptured=true usable=false",
        )

        assertEquals(0, captured.serviceResult)
        assertTrue(captured.parcelCaptured)
        assertTrue(captured.usable)
        assertEquals(-1, rejected.serviceResult)
        assertTrue(rejected.parcelCaptured)
        assertFalse(rejected.usable)
    }

    @Test fun `C09 parses native source and bounded Fission surface inventory`() {
        val facts = parseAutoContainerProjectionInfoProbe(
            """
            [auto_container_projection_info] status=PARCEL_CAPTURED
            source=AutoContainerNative serviceResult=0 parcelCaptured=true usable=true
            [fission_projection_inventory] status=CAPTURED reportedCount=2 capturedCount=2 error=
            [fission_projection_1] name=projection_main width=1920 height=720 surfacePresent=true
            [fission_projection_2] name=projection_aux width=1280 height=480 surfacePresent=false
            """.trimIndent(),
        )

        assertEquals("AutoContainerNative", facts.source)
        assertEquals("CAPTURED", facts.fissionStatus)
        assertEquals(2, facts.fissionReportedCount)
        assertEquals(2, facts.fissionCapturedCount)
        assertEquals("projection_aux", facts.fissionDisplays[1].name)
        assertFalse(facts.fissionDisplays[1].surfacePresent)
    }

    private fun probe(
        displayManager: String,
        surfaceFlinger: String = """Display 4630946674560563842 (HWC display 0): port=130 pnpId=QCM""",
        displayManagerExit: Int = 0,
        surfaceFlingerExit: Int = 0,
        replyStatus: String = "-1",
        includeSurfaceFlingerSection: Boolean = true,
    ): String = buildString {
        appendLine("schema=1")
        appendLine(
            "last_auto_container=service=auto_container transaction=2 device=1000 command=16 " +
                "processExit=0 parcelReply=true replyStatus=$replyStatus",
        )
        appendLine("[services] exit=0")
        appendLine("0 AutoContainerNative: [] 47 auto_container: [android.os.IAutoContainer]")
        appendLine("[display_manager] exit=$displayManagerExit")
        appendLine(displayManager)
        if (includeSurfaceFlingerSection) {
            appendLine("[surface_flinger_displays] exit=$surfaceFlingerExit")
            appendLine(surfaceFlinger)
        }
        appendLine("[activity_displays] exit=0")
        appendLine("(empty)")
    }

    private val seaLionDisplays = """
        DisplayDeviceInfo{"Built-in Screen": uniqueId="local:4630946674560563842", 1920 x 1080,
        density 240, type INTERNAL, state ON, FLAG_DEFAULT_DISPLAY}
        DisplayDeviceInfo{"fission_bg_XDJAScreenProjection":
        uniqueId="virtual:com.xdja.containerservice,1000,fission_bg_XDJAScreenProjection,0",
        1920 x 720, density 320, touch NONE, type VIRTUAL, state ON,
        owner com.xdja.containerservice (uid 1000), FLAG_PRESENTATION, FLAG_OWN_CONTENT_ONLY}
    """.trimIndent()

    /** The DiLink 3 / Leopard 3 shape the production selector was originally written for. */
    private val leopardDisplays = """
        DisplayDeviceInfo{"Built-in Screen": uniqueId="local:1", 1920 x 1080, type INTERNAL}
        DisplayDeviceInfo{"XDJAScreenProjection_1":
        uniqueId="virtual:com.byd.containerservice,1000,XDJAScreenProjection_1,0", 1280 x 480}
    """.trimIndent()

    private val seaLionProbe = probe(seaLionDisplays)

    // --- what the Sea Lion evidence does and does not establish -------------------------------

    @Test fun `Sea Lion shows one physical display and no projection surface`() {
        val facts = parseClusterSystemProbe(seaLionProbe)

        assertEquals(1, facts.physicalDisplayCount)
        assertTrue(facts.displayManagerParsed)
        assertTrue(facts.surfaceFlingerParsed)
        assertTrue(facts.evidenceComplete)
        assertTrue(facts.centralFloatingCompositorPresent)
        assertTrue(facts.autoContainerServicePresent)
        assertEquals(emptyList<String>(), facts.clusterCandidateNames)
        assertEquals(listOf("com.xdja.containerservice"), facts.virtualDisplayOwners)
    }

    @Test fun `AIDL schema header alone does not prove auto container is running`() {
        val report = """
            schema=3
            aidl=android.os.IAutoContainer tx5=getProjectionDisplayInfo
            [services] exit=0
            (empty)
            [display_manager] exit=0
            DisplayDeviceInfo{"Built-in Screen": uniqueId="local:1"}
            [surface_flinger_displays] exit=0
            Display 1 (HWC display 0): port=130
        """.trimIndent()

        assertFalse(parseClusterSystemProbe(report).autoContainerServicePresent)
    }

    @Test fun `failed service inventory cannot prove auto container is running`() {
        val report = """
            schema=3
            aidl=android.os.IAutoContainer tx5=getProjectionDisplayInfo
            [services] exit=10
            47 auto_container: [android.os.IAutoContainer]
            [display_manager] exit=0
            DisplayDeviceInfo{"Built-in Screen": uniqueId="local:1"}
            [surface_flinger_displays] exit=0
            Display 1 (HWC display 0): port=130
        """.trimIndent()

        assertFalse(parseClusterSystemProbe(report).autoContainerServicePresent)
    }

    @Test fun `Sea Lion evidence rules out the Android task path only`() {
        val verdict = clusterAndroidProjectionVerdict(parseClusterSystemProbe(seaLionProbe))

        assertEquals(ClusterAndroidProjectionVerdict.CENTRAL_FLOATING_COMPOSITOR_ONLY, verdict)
        assertEquals(false, verdict.directAndroidTaskProjectionAvailable)
    }

    @Test fun `the export never calls the instrument cluster itself unreachable`() {
        val line = clusterPlatformVerdictLine("final_after_container_watch", seaLionProbe)

        assertTrue(line.contains("directAndroidTaskProjectionAvailable=false"))
        assertTrue(line.contains("nativeClusterPath=UNVERIFIED_BY_THIS_LAB"))
        // The old wording claimed far more than the probe can see.
        assertFalse(line.contains("clusterDisplayUnreachable"))
        assertFalse(line.contains("unreachable"))
    }

    @Test fun `the central floating compositor can never satisfy direct cluster projection`() {
        assertFalse(isClusterProjectionDisplayName("fission_bg_XDJAScreenProjection"))
        assertFalse(isClusterProjectionDisplay(2, "fission_bg_XDJAScreenProjection"))

        val facts = parseClusterSystemProbe(seaLionProbe)
        assertTrue(facts.displayNames.any { it.contains("fission_bg") })
        assertTrue(facts.clusterCandidateNames.isEmpty())
    }

    // --- incomplete evidence must never become a negative conclusion --------------------------

    @Test fun `a missing SurfaceFlinger section is incomplete evidence`() {
        val facts = parseClusterSystemProbe(
            probe(seaLionDisplays, includeSurfaceFlingerSection = false),
        )

        assertNull(facts.physicalDisplayCount)
        assertTrue(facts.displayManagerParsed)
        assertFalse(facts.surfaceFlingerParsed)
        assertEquals(
            ClusterAndroidProjectionVerdict.EVIDENCE_INCOMPLETE,
            clusterAndroidProjectionVerdict(facts),
        )
    }

    @Test fun `a non-zero SurfaceFlinger exit is incomplete evidence`() {
        val facts = parseClusterSystemProbe(probe(seaLionDisplays, surfaceFlingerExit = 10))

        assertNull(facts.physicalDisplayCount)
        assertEquals(
            ClusterAndroidProjectionVerdict.EVIDENCE_INCOMPLETE,
            clusterAndroidProjectionVerdict(facts),
        )
    }

    @Test fun `an empty SurfaceFlinger section is incomplete evidence`() {
        listOf("", "   ", "(empty)").forEach { body ->
            val facts = parseClusterSystemProbe(probe(seaLionDisplays, surfaceFlinger = body))

            assertNull(body, facts.physicalDisplayCount)
            assertEquals(
                body,
                ClusterAndroidProjectionVerdict.EVIDENCE_INCOMPLETE,
                clusterAndroidProjectionVerdict(facts),
            )
        }
    }

    @Test fun `unrecognized SurfaceFlinger formatting is incomplete rather than zero displays`() {
        val facts = parseClusterSystemProbe(
            probe(seaLionDisplays, surfaceFlinger = "Displays: 1 (internal) port 130"),
        )

        assertNull(facts.physicalDisplayCount)
        assertNotEquals(0, facts.physicalDisplayCount)
        assertEquals(
            ClusterAndroidProjectionVerdict.EVIDENCE_INCOMPLETE,
            clusterAndroidProjectionVerdict(facts),
        )
    }

    @Test fun `DisplayManager names without SurfaceFlinger never yield a negative verdict`() {
        val verdict = clusterAndroidProjectionVerdict(
            parseClusterSystemProbe(probe(seaLionDisplays, surfaceFlingerExit = -999)),
        )

        assertEquals(ClusterAndroidProjectionVerdict.EVIDENCE_INCOMPLETE, verdict)
        assertNull(verdict.directAndroidTaskProjectionAvailable)
        assertNotEquals(ClusterAndroidProjectionVerdict.NO_PROJECTION_DISPLAY_VISIBLE, verdict)
        assertNotEquals(
            ClusterAndroidProjectionVerdict.CENTRAL_FLOATING_COMPOSITOR_ONLY,
            verdict,
        )
    }

    @Test fun `an unusable DisplayManager section is incomplete evidence`() {
        val facts = parseClusterSystemProbe(probe("(empty)", displayManagerExit = 0))

        assertFalse(facts.displayManagerParsed)
        assertEquals(
            ClusterAndroidProjectionVerdict.EVIDENCE_INCOMPLETE,
            clusterAndroidProjectionVerdict(facts),
        )
    }

    @Test fun `a missing or blank probe is incomplete evidence`() {
        listOf(null, "", "   ").forEach { report ->
            val verdict = clusterAndroidProjectionVerdict(parseClusterSystemProbe(report))
            assertEquals(ClusterAndroidProjectionVerdict.EVIDENCE_INCOMPLETE, verdict)
            assertNull(verdict.directAndroidTaskProjectionAvailable)
        }
    }

    // --- display counting -----------------------------------------------------------------------

    @Test fun `one valid HWC display is counted`() {
        assertEquals(1, parseClusterSystemProbe(seaLionProbe).physicalDisplayCount)
    }

    @Test fun `two valid HWC displays are counted and never called unavailable`() {
        val facts = parseClusterSystemProbe(
            probe(
                displayManager = """
                    DisplayDeviceInfo{"Built-in Screen": uniqueId="local:1"}
                    DisplayDeviceInfo{"Cluster": uniqueId="local:2"}
                """.trimIndent(),
                surfaceFlinger = "Display 1 (HWC display 0): port=130 " +
                    "Display 2 (HWC display 1): port=131",
            ),
        )

        assertEquals(2, facts.physicalDisplayCount)
        val verdict = clusterAndroidProjectionVerdict(facts)
        assertEquals(ClusterAndroidProjectionVerdict.SECONDARY_PHYSICAL_DISPLAY_PRESENT, verdict)
        assertNull(verdict.directAndroidTaskProjectionAvailable)
    }

    @Test fun `section exit codes are exported for every parsed block`() {
        val facts = parseClusterSystemProbe(probe(seaLionDisplays, surfaceFlingerExit = 10))

        assertEquals(0, facts.sectionExitCodes["display_manager"])
        assertEquals(10, facts.sectionExitCodes["surface_flinger_displays"])
    }

    // --- DiLink 3 must keep working -------------------------------------------------------------

    @Test fun `a genuine DiLink 3 projection surface is still recognized`() {
        val facts = parseClusterSystemProbe(
            probe(leopardDisplays, surfaceFlinger = "Display 1 (HWC display 0): port=130"),
        )

        assertEquals(listOf("XDJAScreenProjection_1"), facts.clusterCandidateNames)
        val verdict = clusterAndroidProjectionVerdict(facts)
        assertEquals(ClusterAndroidProjectionVerdict.PROJECTION_DISPLAY_PRESENT, verdict)
        assertEquals(true, verdict.directAndroidTaskProjectionAvailable)
    }

    @Test fun `a projection surface is recognized even when SurfaceFlinger is unavailable`() {
        val verdict = clusterAndroidProjectionVerdict(
            parseClusterSystemProbe(probe(leopardDisplays, includeSurfaceFlingerSection = false)),
        )

        assertEquals(ClusterAndroidProjectionVerdict.PROJECTION_DISPLAY_PRESENT, verdict)
    }

    @Test fun `preferred display selection still prefers the underscore-one surface`() {
        assertEquals(
            5,
            preferredClusterDisplayId(
                listOf(4 to "XDJAScreenProjection", 5 to "XDJAScreenProjection_1"),
            ),
        )
        assertNull(preferredClusterDisplayId(listOf(2 to "fission_bg_XDJAScreenProjection")))
    }

    // --- three-snapshot comparison ---------------------------------------------------------------

    private val withExtraDisplay = probe(
        seaLionDisplays + "\n" + """DisplayDeviceInfo{"XDJAScreenProjection_1": uniqueId="local:9"}""",
    )

    @Test fun `identical snapshots report no observed Android change`() {
        val facts = parseClusterSystemProbe(seaLionProbe)

        assertEquals(
            ClusterContainerEffect.NO_ANDROID_DISPLAY_CHANGE_OBSERVED,
            clusterContainerEffect(facts, facts, facts),
        )
    }

    @Test fun `a delayed display is distinguished from an immediate one`() {
        val before = parseClusterSystemProbe(seaLionProbe)
        val appeared = parseClusterSystemProbe(withExtraDisplay)

        assertEquals(
            ClusterContainerEffect.DISPLAY_APPEARED_AFTER_DELAY,
            clusterContainerEffect(before, before, appeared),
        )
        assertEquals(
            ClusterContainerEffect.DISPLAY_APPEARED_IMMEDIATELY,
            clusterContainerEffect(before, appeared, appeared),
        )
        assertEquals(
            ClusterContainerEffect.DISPLAY_APPEARED_AND_DISAPPEARED,
            clusterContainerEffect(before, appeared, before),
        )
    }

    @Test fun `a missing final snapshot can never prove that nothing changed`() {
        val before = parseClusterSystemProbe(seaLionProbe)
        val missing = parseClusterSystemProbe(null)

        assertEquals(
            ClusterContainerEffect.EVIDENCE_INCOMPLETE,
            clusterContainerEffect(before, before, missing),
        )
        assertEquals(
            ClusterContainerEffect.EVIDENCE_INCOMPLETE,
            clusterContainerEffect(missing, before, before),
        )
    }

    // --- transport evidence stays raw ------------------------------------------------------------

    @Test fun `a non-zero reply is exported without deciding the hardware outcome`() {
        val before = parseClusterSystemProbe(seaLionProbe)
        val line = clusterContainerEffectLine(before, before, before, replyStatus = -1)

        assertTrue(line.contains("containerReplyStatus=-1"))
        assertTrue(line.contains("containerReplyMeaning=NON_ZERO_SERVICE_REPLY_HARDWARE_UNVERIFIED"))
        assertTrue(line.contains("effect=NO_ANDROID_DISPLAY_CHANGE_OBSERVED"))
        assertTrue(line.contains("nativeClusterPath=UNVERIFIED_BY_THIS_LAB"))
        // No wording anywhere may claim the command was rejected or changed nothing at all.
        assertFalse(line.contains("REJECTED"))
        assertFalse(line.contains("NO_HARDWARE_CHANGE"))
    }

    /**
     * An earlier C07 run recorded a user observation of VISIBLE while the reply was -1 and the
     * Android inventory was unchanged. The two axes must stay independent in the export.
     */
    @Test fun `visual observation stays a separate axis from Android display evidence`() {
        val unchanged = parseClusterSystemProbe(seaLionProbe)

        val pending = clusterContainerEffectLine(unchanged, unchanged, unchanged, replyStatus = -1)
        val recorded = clusterContainerEffectLine(
            unchanged, unchanged, unchanged,
            replyStatus = -1,
            visualObservationPending = false,
        )

        assertTrue(pending.contains("visualObservation=PENDING_USER_REPORT"))
        assertTrue(recorded.contains("visualObservation=RECORDED"))
        // The Android effect is identical in both: a visual report never rewrites it, and it
        // never overrules a visual report.
        assertTrue(pending.contains("effect=NO_ANDROID_DISPLAY_CHANGE_OBSERVED"))
        assertTrue(recorded.contains("effect=NO_ANDROID_DISPLAY_CHANGE_OBSERVED"))
    }

    @Test fun `reply meanings never assert a hardware result`() {
        assertEquals("NO_STATUS_DECODED", containerReplyMeaning(null))
        assertEquals("ZERO_REPLY_SERVICE_LEVEL_OK", containerReplyMeaning(0))
        assertEquals("NON_ZERO_SERVICE_REPLY_HARDWARE_UNVERIFIED", containerReplyMeaning(-1))
        assertEquals("NON_ZERO_SERVICE_REPLY_HARDWARE_UNVERIFIED", containerReplyMeaning(7))
    }

    // --- raw Binder reply decoding ---------------------------------------------------------------

    @Test fun `a non-zero container reply is decoded from its parcel`() {
        assertEquals(-1, serviceCallReplyStatus("Result: Parcel(00000000 ffffffff '........')"))
    }

    @Test fun `a zero container reply decodes to zero`() {
        assertEquals(0, serviceCallReplyStatus("Result: Parcel(00000000 00000000 '........')"))
    }

    @Test fun `a descriptor dump is not mistaken for a status reply`() {
        val descriptor = "Result: Parcel( 0x00000000: 00000019 006e0061 00720064 0069006f " +
            "'....a.n.d.r.o.i.' 0x00000010: 002e0064 0073006f 0049002e 00750041 'd...o.s...I.A.u.')"

        assertNull(serviceCallReplyStatus(descriptor))
    }

    @Test fun `empty or exception replies decode to nothing`() {
        assertNull(serviceCallReplyStatus(""))
        assertNull(serviceCallReplyStatus("Result: Parcel(00000000 '....')"))
        // Non-zero first word is a Binder exception header, not a value to interpret.
        assertNull(serviceCallReplyStatus("Result: Parcel(ffffffff 00000000 '........')"))
    }

    @Test fun `the platform line reports an undecoded reply as unknown`() {
        val line = clusterPlatformVerdictLine("manual_watch_final", null)

        assertTrue(line.contains("androidVerdict=EVIDENCE_INCOMPLETE"))
        assertTrue(line.contains("directAndroidTaskProjectionAvailable=unknown"))
        assertTrue(line.contains("physicalDisplays=unknown"))
        assertTrue(line.contains("containerReplyStatus=unknown"))
        assertTrue(line.contains("containerReplyMeaning=NO_STATUS_DECODED"))
    }

    @Test fun `capture timestamps reach the export`() {
        val line = clusterPlatformVerdictLine(
            stage = "before_container_on",
            report = seaLionProbe,
            capturedAtMs = 1_784_642_427_484,
            captureDurationMs = 310,
        )

        assertTrue(line.contains("capturedAtMs=1784642427484"))
        assertTrue(line.contains("captureDurationMs=310"))
    }
}
