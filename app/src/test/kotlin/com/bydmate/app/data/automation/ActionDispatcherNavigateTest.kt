package com.bydmate.app.data.automation

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherNavigateTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = ActionDispatcher(vehicleApi, helper, app,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true)).apply {
        navigationAppInstalled = { true }
    }

    private fun actionDef(payload: String) =
        ActionDef(command = "", displayName = "navi", kind = "navigate", payload = payload)

    @Test fun shortcut_home_opens_waze_favorite() = runTest {
        val res = dispatcher.dispatch(actionDef("""{"shortcut":"home"}"""), null)
        assertTrue(res.success)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.waze", intent.`package`)
        assertEquals("home", intent.data?.getQueryParameter("favorite"))
        assertEquals("yes", intent.data?.getQueryParameter("navigate"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun shortcut_work_opens_waze_favorite() = runTest {
        val res = dispatcher.dispatch(actionDef("""{"shortcut":"work"}"""), null)
        assertTrue(res.success)
        assertEquals("work", shadowOf(app).nextStartedActivity.data?.getQueryParameter("favorite"))
    }

    @Test fun shortcut_unknown_fails_without_starting_activity() = runTest {
        val res = dispatcher.dispatch(actionDef("""{"shortcut":"dacha"}"""), null)
        assertFalse(res.success)
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    @Test fun show_point_centers_waze_without_navigation() = runTest {
        val res = dispatcher.dispatch(
            actionDef("""{"show":true,"lat":55.75,"lon":37.62,"label":"Кафе"}"""), null)
        assertTrue(res.success)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.waze", intent.`package`)
        assertEquals("55.75,37.62", intent.data?.getQueryParameter("ll"))
        assertEquals("8", intent.data?.getQueryParameter("z"))
        assertEquals(null, intent.data?.getQueryParameter("navigate"))
    }

    @Test fun show_point_without_label_uses_same_waze_contract() = runTest {
        dispatcher.dispatch(actionDef("""{"show":true,"lat":55.75,"lon":37.62}"""), null)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("55.75,37.62", intent.data?.getQueryParameter("ll"))
    }

    @Test fun latlon_still_builds_route_uri() = runTest {
        dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("https", intent.data?.scheme)
        assertEquals("waze.com", intent.data?.host)
        assertEquals("57.0,36.0", intent.data?.getQueryParameter("ll"))
        assertEquals("yes", intent.data?.getQueryParameter("navigate"))
    }

    @Test fun helper_delivery_is_authoritative_and_skips_app_start() = runTest {
        coEvery { helper.launchWazeDeepLink(any()) } returns true
        val result = dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        assertTrue(result.success)
        assertTrue(result.launchDelivered)
        coVerify(exactly = 1) {
            helper.launchWazeDeepLink(match { uri ->
                uri.startsWith("https://waze.com/ul?") &&
                    uri.contains("ll=57.0%2C36.0") && uri.contains("navigate=yes")
            })
        }
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    @Test fun query_still_opens_map_search() = runTest {
        dispatcher.dispatch(actionDef("""{"query":"кафе"}"""), null)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("кафе", intent.data?.getQueryParameter("q"))
        assertEquals("yes", intent.data?.getQueryParameter("navigate"))
    }

    @Test fun search_only_query_does_not_start_navigation() = runTest {
        dispatcher.dispatch(actionDef("""{"query":"кафе","searchOnly":true}"""), null)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("кафе", intent.data?.getQueryParameter("q"))
        assertEquals(null, intent.data?.getQueryParameter("navigate"))
    }

    @Test fun missing_waze_returns_clear_error() = runTest {
        dispatcher.navigationAppInstalled = { false }
        val result = dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        assertFalse(result.success)
        assertEquals("Waze не установлен", result.reason)
    }
}
