package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cover for the vendor-stack inventory added after the 2026-07-21 C07 run.
 *
 * That run established the fact this parser exists for: the driver saw the physical instrument
 * cluster react while `DisplayManager`, `SurfaceFlinger` and the activity dump were byte-identical
 * before, immediately after and twelve seconds after the container command. Whatever rendered is
 * therefore native, and these sections are the only safe way to narrow it down.
 */
class ClusterNativePathTest {

    private fun probe(
        vendorServices: String = "0 AutoContainerNative: [] 47 auto_container: " +
            "[android.os.IAutoContainer] 95 display: [android.hardware.display.IDisplayManager]",
        packages: String = "package:com.byd.cluster.navi package:com.ts.car.someip.service",
        runningServices: String = "ServiceRecord{a1b2c3 u0 com.byd.cluster.navi/.ClusterNaviService}",
        foreground: String = "mResumedActivity: ActivityRecord{d4e5f6 u0 com.waze/.MainActivity t42}",
        includeVendorSections: Boolean = true,
        vendorExit: Int = 0,
    ): String = buildString {
        appendLine("schema=2")
        appendLine("last_auto_container=none")
        appendLine("[display_manager] exit=0")
        appendLine("""DisplayDeviceInfo{"Built-in Screen": uniqueId="local:1"}""")
        appendLine("[surface_flinger_displays] exit=0")
        appendLine("Display 1 (HWC display 0): port=130")
        if (includeVendorSections) {
            appendLine("[vendor_services_wide] exit=$vendorExit")
            appendLine(vendorServices)
            appendLine("[nav_cluster_packages] exit=0")
            appendLine(packages)
            appendLine("[nav_cluster_running_services] exit=0")
            appendLine(runningServices)
            appendLine("[foreground_activities] exit=0")
            appendLine(foreground)
        }
    }

    @Test fun `vendor services packages and running services are extracted`() {
        val facts = parseNativeClusterFacts(probe())

        assertTrue(facts.sectionsParsed)
        assertTrue(facts.vendorServices.contains("auto_container"))
        assertTrue(facts.vendorServices.contains("AutoContainerNative"))
        assertEquals(
            listOf("com.byd.cluster.navi", "com.ts.car.someip.service"),
            facts.navClusterPackages,
        )
        assertEquals(
            listOf("com.byd.cluster.navi/.ClusterNaviService"),
            facts.runningNavServices,
        )
        assertTrue(facts.autoContainerNativePresent)
    }

    @Test fun `the SOME-IP gateway of the working windshield HUD is only counted never probed`() {
        val facts = parseNativeClusterFacts(probe())

        assertTrue(facts.someIpGatewayPresent)
        assertEquals("com.ts.car.someip.service", SOMEIP_GATEWAY_PACKAGE)
    }

    @Test fun `an old schema-1 report is reported as unparsed rather than as empty vendor stack`() {
        val facts = parseNativeClusterFacts(probe(includeVendorSections = false))

        assertFalse(facts.sectionsParsed)
        assertTrue(facts.vendorServices.isEmpty())
        assertTrue(
            clusterNativePathLine("before_container_on", probe(includeVendorSections = false))
                .contains("sectionsParsed=false"),
        )
    }

    @Test fun `a failed vendor section yields no facts from that section`() {
        val facts = parseNativeClusterFacts(probe(vendorExit = 10))

        assertTrue(facts.vendorServices.isEmpty())
        // The other sections are independent and must still parse.
        assertTrue(facts.navClusterPackages.isNotEmpty())
    }

    // --- per-section exit codes decide what may be concluded ---------------------------------

    @Test fun `a present header with a non-zero exit is not a parsed section`() {
        val facts = parseNativeClusterFacts(probe(vendorExit = 10))

        assertTrue(facts.vendorSectionsPresent)
        assertFalse(facts.vendorServicesParsed)
        // One failed section is enough to make the vendor block incomplete...
        assertFalse(facts.sectionsParsed)
        // ...but it is not an input of the delta, so the delta stays available.
        assertTrue(facts.deltaInputsParsed)
    }

    @Test fun `a section header alone never marks the vendor block parsed`() {
        val headerOnly = """
            schema=2
            [vendor_services_wide] exit=137
            (empty)
        """.trimIndent()
        val facts = parseNativeClusterFacts(headerOnly)

        assertTrue(facts.vendorSectionsPresent)
        assertFalse(facts.sectionsParsed)
        assertFalse(facts.deltaInputsParsed)
    }

    @Test fun `an empty grep result is a real observation rather than a failure`() {
        // `dumpsys | grep | head` exits 0 with no output when nothing matches.
        val facts = parseNativeClusterFacts(probe(runningServices = "(empty)"))

        assertTrue(facts.runningNavServicesParsed)
        assertTrue(facts.runningNavServices.isEmpty())
        assertTrue(facts.deltaInputsParsed)
    }

    @Test fun `a failed running-services section blocks the delta`() {
        val broken = probe().replace(
            "[nav_cluster_running_services] exit=0",
            "[nav_cluster_running_services] exit=1",
        )
        val line = clusterNativeDeltaLine(broken, probe())

        assertTrue(line.contains("status=EVIDENCE_INCOMPLETE"))
        assertTrue(line.contains("beforeParsed=false"))
        assertTrue(line.contains("runningNavServices=false"))
        assertFalse(line.contains("NO_VENDOR_STATE_CHANGE_OBSERVED"))
    }

    @Test fun `a failed foreground section blocks the delta`() {
        val broken = probe().replace(
            "[foreground_activities] exit=0",
            "[foreground_activities] exit=137",
        )
        val line = clusterNativeDeltaLine(probe(), broken)

        assertTrue(line.contains("status=EVIDENCE_INCOMPLETE"))
        assertTrue(line.contains("finalParsed=false"))
        assertFalse(line.contains("NO_VENDOR_STATE_CHANGE_OBSERVED"))
    }

    @Test fun `a missing foreground section blocks the delta`() {
        val withoutForeground = probe().substringBefore("[foreground_activities]")
        val line = clusterNativeDeltaLine(withoutForeground, probe())

        assertTrue(line.contains("status=EVIDENCE_INCOMPLETE"))
        assertFalse(line.contains("NO_VENDOR_STATE_CHANGE_OBSERVED"))
    }

    @Test fun `an unrelated failed section does not hide a real change`() {
        val before = probe(runningServices = "(empty)", vendorExit = 10)
        val after = probe(vendorExit = 10)

        val line = clusterNativeDeltaLine(before, after)

        assertTrue(line.contains("status=VENDOR_STATE_CHANGED"))
        assertTrue(line.contains("startedServices=com.byd.cluster.navi/.ClusterNaviService"))
    }

    // --- dump order must not look like a state change ------------------------------------------

    @Test fun `the same foreground components in a different order are not a change`() {
        val orderA = probe(
            foreground = "mResumedActivity: ActivityRecord{a u0 com.waze/.MainActivity t1} " +
                "mFocusedApp: ActivityRecord{b u0 com.byd.cluster.navi/.MapActivity t2}",
        )
        val orderB = probe(
            foreground = "mFocusedApp: ActivityRecord{b u0 com.byd.cluster.navi/.MapActivity t2} " +
                "mResumedActivity: ActivityRecord{a u0 com.waze/.MainActivity t1}",
        )

        assertEquals(
            parseNativeClusterFacts(orderA).foregroundActivities,
            parseNativeClusterFacts(orderB).foregroundActivities,
        )
        val line = clusterNativeDeltaLine(orderA, orderB)
        assertTrue(line.contains("status=NO_VENDOR_STATE_CHANGE_OBSERVED"))
        assertTrue(line.contains("foregroundChanged=false"))
    }

    @Test fun `reordered running services are not a started or stopped service`() {
        val orderA = probe(
            runningServices = "ServiceRecord{a1 u0 com.byd.cluster.navi/.ClusterNaviService} " +
                "ServiceRecord{b2 u0 com.ts.car.someip.service/.Gateway}",
        )
        val orderB = probe(
            runningServices = "ServiceRecord{b2 u0 com.ts.car.someip.service/.Gateway} " +
                "ServiceRecord{a1 u0 com.byd.cluster.navi/.ClusterNaviService}",
        )

        val line = clusterNativeDeltaLine(orderA, orderB)

        assertTrue(line.contains("status=NO_VENDOR_STATE_CHANGE_OBSERVED"))
        assertTrue(line.contains("startedServices=none"))
        assertTrue(line.contains("stoppedServices=none"))
    }

    @Test fun `every extracted list is exported in a stable order`() {
        val facts = parseNativeClusterFacts(
            probe(packages = "package:com.zzz.navi package:com.aaa.meter"),
        )

        assertEquals(listOf("com.aaa.meter", "com.zzz.navi"), facts.navClusterPackages)
        assertEquals(facts.vendorServices.sorted(), facts.vendorServices)
        assertEquals(facts.foregroundActivities.sorted(), facts.foregroundActivities)
    }

    @Test fun `a missing report parses to nothing without throwing`() {
        listOf(null, "", "   ").forEach { report ->
            val facts = parseNativeClusterFacts(report)
            assertFalse(facts.sectionsParsed)
            assertTrue(facts.runningNavServices.isEmpty())
        }
    }

    // --- delta across the container command -------------------------------------------------

    @Test fun `a started vendor service is reported as a vendor state change`() {
        val line = clusterNativeDeltaLine(
            before = probe(runningServices = "(none)"),
            afterWatch = probe(),
        )

        assertTrue(line.contains("status=VENDOR_STATE_CHANGED"))
        assertTrue(line.contains("startedServices=com.byd.cluster.navi/.ClusterNaviService"))
        assertTrue(line.contains("stoppedServices=none"))
    }

    @Test fun `a foreground change alone is a vendor state change`() {
        val line = clusterNativeDeltaLine(
            before = probe(foreground = "mResumedActivity: ActivityRecord{a u0 com.waze/.Main t1}"),
            afterWatch = probe(
                foreground = "mResumedActivity: ActivityRecord{b u0 com.byd.cluster.navi/.Map t2}",
            ),
        )

        assertTrue(line.contains("status=VENDOR_STATE_CHANGED"))
        assertTrue(line.contains("foregroundChanged=true"))
    }

    /**
     * The outcome the 2026-07-21 evidence predicts: the cluster visibly reacts, yet nothing the
     * Android framework can dump moves. That must still not be phrased as "no native path".
     */
    @Test fun `an empty delta never rules the native path out`() {
        val identical = probe()
        val line = clusterNativeDeltaLine(identical, identical)

        assertTrue(line.contains("status=NO_VENDOR_STATE_CHANGE_OBSERVED"))
        assertTrue(line.contains("nativeClusterPath=NOT_OBSERVABLE_FROM_THIS_CELL"))
        assertFalse(line.contains("IMPOSSIBLE"))
        assertFalse(line.contains("unreachable"))
    }

    @Test fun `a delta needs both snapshots parsed before it concludes anything`() {
        val line = clusterNativeDeltaLine(probe(includeVendorSections = false), probe())

        assertTrue(line.contains("status=EVIDENCE_INCOMPLETE"))
        assertTrue(line.contains("beforeParsed=false"))
        assertTrue(line.contains("finalParsed=true"))
        assertFalse(line.contains("NO_VENDOR_STATE_CHANGE_OBSERVED"))
    }

    @Test fun `the native path line never claims an Android display verdict`() {
        val line = clusterNativePathLine("final_after_container_watch", probe())

        assertFalse(line.contains("directAndroidTaskProjectionAvailable"))
        assertFalse(line.contains("androidVerdict"))
        assertTrue(line.startsWith("cluster_native_path "))
    }

    // --- settled architecture must reach the export -------------------------------------------

    /** The native Qt renderer and the optional Android projection bridge are separate layers. */
    @Test fun `every export line carries the settled cluster architecture`() {
        val identical = probe()

        listOf(
            clusterNativePathLine("aidl_contract", identical),
            clusterNativeDeltaLine(identical, identical),
        ).forEach { line ->
            assertTrue(line, line.contains("clusterNativeArchitecture=$CLUSTER_NATIVE_ARCHITECTURE"))
        }
        assertEquals(
            "FISSION_HOST_QT_WITH_OPTIONAL_XDJA_ANDROID_PROJECTION_BRIDGE",
            CLUSTER_NATIVE_ARCHITECTURE,
        )
    }

    /**
     * The runtime status stays scoped to what a probe inside this Android cell can observe. It must
     * never claim the cluster is impossible, and never claim silence means nothing is there.
     */
    @Test fun `the runtime status describes observability rather than possibility`() {
        assertEquals("NOT_OBSERVABLE_FROM_THIS_CELL", NATIVE_CLUSTER_PATH_STATUS)

        val line = clusterNativePathLine("aidl_contract", probe())
        assertFalse(line.contains("IMPOSSIBLE"))
        assertFalse(line.contains("unreachable"))
    }
}
