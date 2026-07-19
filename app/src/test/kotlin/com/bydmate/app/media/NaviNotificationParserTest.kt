package com.bydmate.app.media

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 32])
class NaviNotificationParserTest {

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
        ))
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

    @Test fun `parse survives notification without extras content`() {
        val parsed = NaviNotificationParser.parse(Notification())
        assertNull(parsed.distance)
        assertFalse(parsed.hasGuidance)
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
}
