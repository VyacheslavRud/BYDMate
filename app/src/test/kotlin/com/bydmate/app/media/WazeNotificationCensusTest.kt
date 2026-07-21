package com.bydmate.app.media

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
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
@Config(sdk = [32])
class WazeNotificationCensusTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        WazeNotificationCensus.reset()
    }

    private fun notification(
        configure: Notification.Builder.() -> Unit = {},
    ): Notification = Notification.Builder(context, "channel")
        .setSmallIcon(android.R.drawable.ic_menu_directions)
        .apply(configure)
        .build()

    @Test fun `counters separate accepted from rejected notifications`() {
        WazeNotificationCensus.record(notification(), accepted = true, resolveName = { null })
        WazeNotificationCensus.record(notification(), accepted = false, resolveName = { null })
        WazeNotificationCensus.record(notification(), accepted = false, resolveName = { null })

        val snapshot = WazeNotificationCensus.snapshot()
        assertEquals(3, snapshot.posted)
        assertEquals(1, snapshot.accepted)
        assertEquals(2, snapshot.rejected)
        assertEquals(false, snapshot.lastAccepted)
    }

    @Test fun `standard template exposes no RemoteViews and is distinguishable from silence`() {
        WazeNotificationCensus.record(
            notification { setContentTitle("t").setContentText("x") },
            accepted = false,
            resolveName = { null },
        )

        val snapshot = WazeNotificationCensus.snapshot()
        assertEquals(1, snapshot.posted)
        assertFalse(snapshot.lastRejectedShape!!.contentView)
        assertFalse(snapshot.lastRejectedShape!!.bigContentView)
    }

    @Test fun `custom RemoteViews are reported as present`() {
        val views = RemoteViews(context.packageName, android.R.layout.simple_list_item_1)
        WazeNotificationCensus.record(
            notification { setCustomContentView(views) },
            accepted = false,
            resolveName = { null },
        )

        assertTrue(WazeNotificationCensus.snapshot().lastRejectedShape!!.contentView)
    }

    @Test fun `small icon type and resolved resource name are captured`() {
        WazeNotificationCensus.record(
            notification(),
            accepted = true,
            resolveName = { "ic_turn_right_24" },
        )

        val shape = WazeNotificationCensus.snapshot().lastAcceptedShape!!
        assertEquals(Icon.TYPE_RESOURCE, shape.smallIconType)
        assertEquals("ic_turn_right_24", shape.smallIconResource)
    }

    @Test fun `bitmap large icon exposes its type but never a resource name`() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        WazeNotificationCensus.record(
            notification { setLargeIcon(Icon.createWithBitmap(bitmap)) },
            accepted = true,
            resolveName = { "unexpected" },
        )

        val shape = WazeNotificationCensus.snapshot().lastAcceptedShape!!
        assertEquals(Icon.TYPE_BITMAP, shape.largeIconType)
        assertNull(shape.largeIconResource)
    }

    @Test fun `extras census keeps key names and value types but no values`() {
        WazeNotificationCensus.record(
            notification { setContentTitle("Turn right onto Karlova") },
            accepted = false,
            resolveName = { null },
        )

        val extras = WazeNotificationCensus.snapshot().lastRejectedShape!!.extras
        assertTrue(extras.any { it.startsWith("${Notification.EXTRA_TITLE}:") })
        assertTrue(extras.none { it.contains("Karlova") })
    }

    @Test fun `category and channel identify the notification Waze actually posts`() {
        WazeNotificationCensus.record(
            notification { setCategory(Notification.CATEGORY_NAVIGATION) },
            accepted = true,
            resolveName = { null },
        )

        val shape = WazeNotificationCensus.snapshot().lastAcceptedShape!!
        assertEquals(Notification.CATEGORY_NAVIGATION, shape.category)
        assertEquals("channel", shape.channelId)
    }

    /**
     * Waze keeps posting community alerts while a route is running. Holding one "last shape" let
     * the final alert of the drive overwrite the navigation notification the export exists to
     * describe, so the two verdicts retain their evidence independently.
     */
    @Test fun `a later rejected alert does not destroy the accepted navigation shape`() {
        WazeNotificationCensus.record(
            notification { setCategory(Notification.CATEGORY_NAVIGATION) },
            accepted = true,
            resolveName = { "ic_turn_right_24" },
        )
        WazeNotificationCensus.record(
            notification { setCategory(Notification.CATEGORY_SOCIAL) },
            accepted = false,
            resolveName = { "ic_alert" },
        )

        val snapshot = WazeNotificationCensus.snapshot()
        assertEquals(2, snapshot.posted)
        assertEquals(false, snapshot.lastAccepted)
        assertEquals(
            Notification.CATEGORY_NAVIGATION,
            snapshot.lastAcceptedShape!!.category,
        )
        assertEquals("ic_turn_right_24", snapshot.lastAcceptedShape!!.smallIconResource)
        assertEquals(Notification.CATEGORY_SOCIAL, snapshot.lastRejectedShape!!.category)
    }

    @Test fun `an accepted navigation notification never overwrites the rejected shape`() {
        WazeNotificationCensus.record(
            notification { setCategory(Notification.CATEGORY_SOCIAL) },
            accepted = false,
            resolveName = { null },
        )
        WazeNotificationCensus.record(
            notification { setCategory(Notification.CATEGORY_NAVIGATION) },
            accepted = true,
            resolveName = { null },
        )

        val snapshot = WazeNotificationCensus.snapshot()
        assertEquals(Notification.CATEGORY_SOCIAL, snapshot.lastRejectedShape!!.category)
        assertEquals(Notification.CATEGORY_NAVIGATION, snapshot.lastAcceptedShape!!.category)
    }
}
