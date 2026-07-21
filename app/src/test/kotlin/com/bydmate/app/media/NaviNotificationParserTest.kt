package com.bydmate.app.media

import android.app.Notification
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavManeuverCodes
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
class NaviNotificationParserTest {

    @Before fun resetHub() = NavGuidanceHub.reset()

    private fun statusBarNotification(
        notification: Notification,
        postTimeMs: Long,
    ): StatusBarNotification = StatusBarNotification(
        "com.waze",
        "com.waze",
        42,
        "route",
        1_000,
        0,
        0,
        notification,
        UserHandle.getUserHandleForUid(1_000),
        postTimeMs,
    )

    @Test fun `maps standard Waze lines to maneuver distance and street`() {
        val parsed = NaviNotificationParser.fromText(
            title = "350 m",
            text = "Turn right onto Main Street",
            subText = "18 min",
            bigText = "12 km · 18 min",
        )
        assertEquals("направо", parsed.maneuver)
        assertEquals("350 m", parsed.distance)
        assertEquals("Main Street", parsed.street)
        assertEquals(2, parsed.guidance?.maneuverGaode)
        assertEquals(350, parsed.guidance?.distanceMeters)
        assertEquals(12_000, parsed.guidance?.totalDistMeters)
        assertEquals(18 * 60, parsed.guidance?.etaSeconds)
        assertEquals("12 km", parsed.remainingDistance)
        assertEquals("18 min", parsed.remainingTime)
        assertTrue(parsed.bigTexts.contains("18 min"))
    }

    @Test fun `Russian Waze instruction is parsed`() {
        val parsed = NaviNotificationParser.fromText(
            title = "Через 1,2 км поверните налево на улицу Ленина",
            text = null,
            subText = null,
            bigText = null,
        )
        assertEquals("налево", parsed.maneuver)
        assertEquals("1,2 км", parsed.distance)
        assertEquals(1200, parsed.guidance?.distanceMeters)
        assertEquals("Ленина", parsed.street)
    }

    @Test fun `compound instruction does not leak following maneuver into street`() {
        val english = NaviNotificationParser.fromText(
            title = "350 m",
            text = "Turn right onto Main Street, then turn left",
            subText = null,
            bigText = null,
        )
        assertEquals(2, english.guidance?.maneuverGaode)
        assertEquals("Main Street", english.street)

        val russian = NaviNotificationParser.fromText(
            title = "350 м",
            text = "Поверните направо на улицу Ленина, затем налево",
            subText = null,
            bigText = null,
        )
        assertEquals(2, russian.guidance?.maneuverGaode)
        assertEquals("Ленина", russian.street)
    }

    @Test fun `generic running notification is not guidance`() {
        val parsed = NaviNotificationParser.fromText(
            title = "Waze",
            text = "Running. Tap to open.",
            subText = null,
            bigText = null,
        )
        assertFalse(parsed.hasGuidance)
        assertTrue(parsed.bigTexts.isEmpty())
    }

    @Test fun `road-only Waze alert cannot become route guidance`() {
        val parsed = NaviNotificationParser.fromText(
            title = "Police reported ahead",
            text = "D1",
            subText = null,
            bigText = null,
        )
        assertFalse(parsed.hasGuidance)
    }

    @Test fun `distance-only Waze alert cannot become route guidance`() {
        val parsed = NaviNotificationParser.fromText(
            title = "Police reported 500 m ahead",
            text = "D1",
            subText = null,
            bigText = null,
        )
        assertFalse(parsed.hasGuidance)
        assertNull(parsed.distance)
    }

    @Test fun `unknown channel rejects distance-only alert but trusted channel accepts it`() {
        assertFalse(MediaSessionListenerService.shouldAcceptNavigationNotification(
            category = null,
            channelId = "COMMUNITY_ALERTS",
        ))
        assertTrue(MediaSessionListenerService.shouldAcceptNavigationNotification(
            category = null,
            channelId = MediaSessionListenerService.WAZE_NAVIGATION_CHANNEL,
        ))
    }

    @Test fun `unknown channel is rejected even when its text resembles a maneuver`() {
        val engagement = NaviNotificationParser.fromText(
            title = "Continue your drive with Waze",
            text = "Your destination is waiting",
            subText = null,
            bigText = null,
        )
        assertTrue(engagement.hasGuidance)
        assertFalse(MediaSessionListenerService.shouldAcceptNavigationNotification(
            category = null,
            channelId = "ENGAGEMENT",
            parsedHasGuidance = engagement.hasGuidance,
            hasStrongRouteEvidence = MediaSessionListenerService.hasStrongRouteEvidence(engagement),
        ))
    }

    @Test fun `vendor channel accepts parsed maneuver only with independent route metric`() {
        val realTurn = NaviNotificationParser.fromText(
            title = "500 m",
            text = "Turn right",
            subText = null,
            bigText = null,
        )
        assertTrue(MediaSessionListenerService.shouldAcceptNavigationNotification(
            category = null,
            channelId = "VENDOR_WAZE_ROUTE",
            parsedHasGuidance = realTurn.hasGuidance,
            hasStrongRouteEvidence = MediaSessionListenerService.hasStrongRouteEvidence(realTurn),
        ))

        val weak = NaviNotificationParser.fromText(
            title = null,
            text = "Continue",
            subText = null,
            bigText = null,
        )
        assertFalse(MediaSessionListenerService.shouldAcceptNavigationNotification(
            category = null,
            channelId = "ENGAGEMENT",
            parsedHasGuidance = weak.hasGuidance,
            hasStrongRouteEvidence = MediaSessionListenerService.hasStrongRouteEvidence(weak),
        ))
    }

    @Test fun `distance-only progress update preserves accepted vendor route key`() {
        val service = MediaSessionListenerService()
        val strong = Notification().apply {
            extras.putString(Notification.EXTRA_TITLE, "500 m")
            extras.putString(Notification.EXTRA_TEXT, "Turn right")
        }
        service.onNotificationPosted(statusBarNotification(strong, postTimeMs = 1_000L))

        val beforeProgress = NavGuidanceHub.snapshot()
        assertTrue(beforeProgress.active)
        assertEquals(2, beforeProgress.maneuverGaode)
        assertEquals(500, beforeProgress.distanceMeters)

        val distanceOnly = Notification().apply {
            extras.putString(Notification.EXTRA_TITLE, "450 m")
        }
        service.onNotificationPosted(statusBarNotification(distanceOnly, postTimeMs = 2_000L))

        val afterProgress = NavGuidanceHub.snapshot()
        assertTrue(afterProgress.active)
        assertEquals(2, afterProgress.maneuverGaode)
        assertEquals(500, afterProgress.distanceMeters)
    }

    @Test fun `parse reads Android notification extras`() {
        val notification = Notification().apply {
            extras.putString(Notification.EXTRA_TITLE, "500 m")
            extras.putString(Notification.EXTRA_TEXT, "Keep left toward Brno")
            extras.putString(Notification.EXTRA_SUB_TEXT, "24 min")
        }
        val parsed = NaviNotificationParser.parse(notification)
        assertEquals("левее", parsed.maneuver)
        assertEquals(500, parsed.guidance?.distanceMeters)
        assertEquals("Brno", parsed.street)
    }

    @Test fun `vendor string extra supplies maneuver when standard fields omit it`() {
        val notification = Notification().apply {
            extras.putString(Notification.EXTRA_TITLE, "250 m")
            extras.putString(Notification.EXTRA_TEXT, "Main Street")
            extras.putString("com.waze.navigation.maneuver", "TURN_RIGHT")
        }

        val parsed = NaviNotificationParser.parse(notification)

        assertEquals(NavManeuverCodes.GAODE_RIGHT, parsed.guidance?.maneuverGaode)
        assertEquals(250, parsed.guidance?.distanceMeters)
        assertEquals("Main Street", parsed.street)
    }

    @Test fun `standard route instruction wins over a stale vendor hint`() {
        val parsed = NaviNotificationParser.fromText(
            title = "250 m",
            text = "Turn left onto Main Street",
            subText = null,
            bigText = null,
            maneuverHints = listOf("TURN_RIGHT"),
        )

        assertEquals(NavManeuverCodes.GAODE_LEFT, parsed.guidance?.maneuverGaode)
    }

    @Test fun `RemoteViews maneuver code completes distance and street only notification`() {
        val parsed = NaviNotificationParser.fromText(
            title = "50 m",
            text = "Main Street",
            subText = null,
            bigText = null,
            maneuverCodeHint = NavManeuverCodes.GAODE_RIGHT,
        )

        assertEquals(NavManeuverCodes.GAODE_RIGHT, parsed.guidance?.maneuverGaode)
        assertEquals(50, parsed.guidance?.distanceMeters)
        assertEquals("Main Street", parsed.guidance?.road)
    }

    @Test fun `standard instruction wins over conflicting RemoteViews resource`() {
        val parsed = NaviNotificationParser.fromText(
            title = "50 m",
            text = "Turn left onto Main Street",
            subText = null,
            bigText = null,
            maneuverCodeHint = NavManeuverCodes.GAODE_RIGHT,
        )

        assertEquals(NavManeuverCodes.GAODE_LEFT, parsed.guidance?.maneuverGaode)
    }

    @Test fun `diagnostic dump redacts vendor maneuver extra value`() {
        val notification = Notification().apply {
            extras.putString(Notification.EXTRA_TITLE, "250 m")
            extras.putString(Notification.EXTRA_TEXT, "Main Street")
            extras.putString("com.waze.navigation.maneuver", "TURN_RIGHT_SECRET")
        }

        val dump = NaviNotificationParser.dump(notification)

        assertTrue("semanticHints=" in dump)
        assertFalse("TURN_RIGHT_SECRET" in dump)
    }

    @Test fun `parse survives notification without extras content`() {
        val parsed = NaviNotificationParser.parse(Notification())
        assertNull(parsed.distance)
        assertFalse(parsed.hasGuidance)
    }

    @Test fun `notification wires split route summary into HUD guidance`() {
        val parsed = NaviNotificationParser.fromText(
            title = "250 m",
            text = "Turn right onto Main Street",
            subText = "1 h 5 min",
            bigText = "12 km · ETA 3:40 PM",
        )

        assertEquals("12 km", parsed.remainingDistance)
        assertEquals("1 h 5 min", parsed.remainingTime)
        assertEquals("15:40", parsed.arrivalTime)
        assertEquals(12_000, parsed.guidance?.totalDistMeters)
        assertEquals(3900, parsed.guidance?.etaSeconds)
        assertEquals("15:40", parsed.guidance?.arrivalTime)
    }

    @Test fun `Russian notification duration and distance reach HUD guidance`() {
        val parsed = NaviNotificationParser.fromText(
            title = "350 м",
            text = "Поверните направо",
            subText = "1 ч 5 мин",
            bigText = "12 км · 1 ч 5 мин",
        )

        assertEquals("1 ч 5 мин", parsed.remainingTime)
        assertEquals("12 км", parsed.remainingDistance)
        assertEquals(3900, parsed.guidance?.etaSeconds)
        assertEquals(12_000, parsed.guidance?.totalDistMeters)
    }

    @Test fun `next maneuver distance is not reused as remaining route distance`() {
        val parsed = NaviNotificationParser.fromText(
            title = "In 500 m",
            text = "Turn left",
            subText = "18 min",
            bigText = null,
        )

        assertEquals(500, parsed.guidance?.distanceMeters)
        assertEquals(18 * 60, parsed.guidance?.etaSeconds)
        assertEquals(0, parsed.guidance?.totalDistMeters)
        assertNull(parsed.remainingDistance)
    }

    @Test fun `diagnostic dump redacts notification text and street`() {
        val notification = Notification().apply {
            extras.putString(Notification.EXTRA_TITLE, "350 m")
            extras.putString(Notification.EXTRA_TEXT, "Turn right onto Secret Street")
            extras.putString(Notification.EXTRA_BIG_TEXT, "12 km · 18 min")
        }

        val dump = NaviNotificationParser.dump(notification)
        assertTrue("source=notification" in dump)
        assertTrue("recognized=RIGHT" in dump)
        assertTrue("title=present(len=5)" in dump)
        assertFalse("Secret Street" in dump)
        assertFalse("Turn right" in dump)
        assertFalse("12 km" in dump)
    }

    @Test fun `remaining route summary is not next maneuver distance`() {
        val parsed = NaviNotificationParser.fromText(
            title = "12 km · 18 min",
            text = "D1",
            subText = null,
            bigText = null,
        )

        assertNull(parsed.distance)
        assertEquals("12 km", parsed.remainingDistance)
        assertEquals("18 min", parsed.remainingTime)
        assertFalse(parsed.hasGuidance)
    }

    @Test fun `newest notification mirror is selected by observation order`() {
        val older = NavigationNotificationMirror(
            packageName = NaviRouteHolder.NAVI_PACKAGE,
            title = "older",
            text = null,
            subText = null,
            parsed = null,
            postTimeMs = 200,
            observedSequence = 1,
        )
        val newer = older.copy(
            title = "newer",
            // Vendor postTime is not guaranteed to advance on an ongoing-notification update.
            postTimeMs = 100,
            observedSequence = 2,
        )

        assertEquals(newer, newestNavigationNotification(listOf(older, newer)))
    }

    @Test fun `notification mirror selection handles no remaining notification`() {
        assertNull(newestNavigationNotification(emptyList()))
    }
}
