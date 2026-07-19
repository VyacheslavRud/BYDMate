package com.bydmate.app.helper

import com.bydmate.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevBuildIsolationTest {

    @Test
    fun `debug build uses isolated app and helper identities`() {
        assertTrue(BuildConfig.DEBUG)
        assertEquals("com.bydmate.app.dev", BuildConfig.APPLICATION_ID)
        assertEquals(BuildConfig.APPLICATION_ID, HelperBinderProtocol.APP_PACKAGE)
        assertEquals("bydmate_helper_dev", HelperBinderProtocol.SERVICE_NAME)
        assertEquals("bydmate_dev", HelperBinderProtocol.PROCESS_NAME)
        assertEquals(
            "com.bydmate.app.dev/com.bydmate.app.cluster.SteeringWheelKeyService",
            HelperBinderProtocol.ACCESSIBILITY_SERVICE_COMPONENT,
        )
        assertTrue(HelperBinderProtocol.LOCK_PATH.contains("_dev"))
        assertTrue(HelperBinderProtocol.LOG_PATH.contains("_dev"))
    }
}
