package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoContainerFallbackTest {

    private class RecordingExec(private val codes: MutableList<Int>) : (String, String) -> Int {
        val scripts = mutableListOf<String>()
        val args = mutableListOf<String>()
        override fun invoke(script: String, arg: String): Int {
            scripts += script
            args += arg
            return codes.removeAt(0)
        }
    }

    @Test
    fun `snake_case success never tries CamelCase`() {
        val exec = RecordingExec(mutableListOf(0))
        assertTrue(autoContainerCall(16, exec))
        assertEquals(1, exec.scripts.size)
        assertTrue(exec.scripts[0].contains("auto_container"))
        assertTrue(exec.scripts[0].contains("i32 1000"))
        assertEquals("16", exec.args[0])
    }

    @Test
    fun `snake_case failure falls back to CamelCase`() {
        val exec = RecordingExec(mutableListOf(10, 0))
        assertTrue(autoContainerCall(18, exec))
        assertEquals(2, exec.scripts.size)
        assertTrue(exec.scripts[1].contains("AutoContainer"))
        assertFalse(exec.scripts[1].contains("auto_container"))
        assertTrue(exec.scripts[1].contains("i32 1000"))
        assertEquals("18", exec.args[1])
    }

    @Test
    fun `both names failing returns false`() {
        val exec = RecordingExec(mutableListOf(10, 10))
        assertFalse(autoContainerCall(0, exec))
        assertEquals(2, exec.scripts.size)
    }

    @Test
    fun `non-whitelisted cmd throws before any exec`() {
        val exec = RecordingExec(mutableListOf())
        assertThrows(IllegalArgumentException::class.java) { autoContainerCall(17, exec) }
        assertEquals(0, exec.scripts.size)
    }

    @Test
    fun `trace distinguishes process result from Binder parcel and sanitizes output`() {
        val trace = autoContainerTrace(
            serviceName = "auto_container",
            cmd = 16,
            code = 0,
            stdout = "Result: Parcel(00000000)\nroute/private",
        )

        assertTrue(trace.contains("processExit=0"))
        assertTrue(trace.contains("parcelReply=true"))
        assertFalse(trace.contains('\n'))
    }

    @Test
    fun `cluster system probe runs only fixed read-only commands and stays bounded`() {
        val commands = mutableListOf<String>()
        val report = buildClusterSystemProbe("none") { command, args ->
            commands += command
            assertTrue(args.isEmpty())
            CmdResult(0, "DisplayDeviceInfo XDJAScreenProjection")
        }

        assertEquals(11, commands.size)
        assertTrue(commands.all { command ->
            command.startsWith("service list") ||
                command.startsWith("service call") ||
                command.startsWith("pm list packages") ||
                command.startsWith("dumpsys ")
        })
        assertTrue(report.contains("[display_manager] exit=0"))
        assertTrue(report.contains("XDJAScreenProjection"))
        assertTrue(report.length <= 30_000)
    }

    @Test
    fun `cluster system probe carries the vendor-stack sections the native path needs`() {
        val report = buildClusterSystemProbe("none") { _, _ -> CmdResult(0, "x") }

        listOf(
            "[vendor_services_wide]",
            "[nav_cluster_packages]",
            "[nav_cluster_running_services]",
            "[foreground_activities]",
        ).forEach { section -> assertTrue(section, report.contains(section)) }
        assertTrue(report.startsWith("schema=3"))
        assertTrue(report.contains("tx5=getProjectionDisplayInfo"))
        assertFalse(report.contains("[auto_container_projection_info]"))
    }

    @Test
    fun `no probe command takes an argument or writes anything`() {
        val commands = mutableListOf<String>()
        buildClusterSystemProbe("none") { command, args ->
            commands += command
            assertTrue(args.isEmpty())
            CmdResult(0, "")
        }

        // The shared C07/C08 probe may only read Binder descriptors. C09 owns transaction 5.
        commands.filter { it.startsWith("service call") }.forEach { command ->
            assertTrue(command, command.contains("1598968902"))
        }
        assertTrue(commands.none { it.contains("am ") || it.contains("settings put") })
    }

    @Test
    fun `C09 projection info probe performs the exact out getter once`() {
        val commands = mutableListOf<String>()
        val report = buildAutoContainerProjectionInfoProbe { command, args ->
            commands += command
            assertTrue(args.isEmpty())
            CmdResult(0, "Result: Parcel(00000000 00000001)")
        }

        assertEquals(listOf("service call auto_container 5"), commands)
        assertTrue(report.contains("method=getProjectionDisplayInfo"))
        assertTrue(report.contains("transaction=5 direction=out mutation=NONE"))
        assertTrue(report.contains("[auto_container_projection_info] exit=0"))
    }
}
