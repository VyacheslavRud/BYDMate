package com.bydmate.app.media

import android.app.Notification
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.navdata.NavManeuverCodes
import com.bydmate.app.navdata.WazeVisualManeuverReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WazeRemoteViewsManeuverReaderTest {
    @Test fun `Waze image resource maps explicit right maneuver`() {
        val selected = WazeRemoteViewsManeuverReader.selectManeuver(
            listOf(
                WazeRemoteViewsManeuverReader.ResourceCandidate(
                    viewName = "next_maneuver_icon",
                    resourceName = "ic_turn_right_24",
                ),
            ),
        )

        assertEquals(NavManeuverCodes.GAODE_RIGHT, selected?.first)
        assertEquals("ic_turn_right_24", selected?.second)
    }

    @Test fun `generic chevron is not accepted as driving direction`() {
        assertEquals(
            0,
            WazeRemoteViewsManeuverReader.semanticResourceManeuver("ic_chevron_right"),
        )
    }

    @Test fun `conflicting image resources are rejected instead of guessed`() {
        val selected = WazeRemoteViewsManeuverReader.selectManeuver(
            listOf(
                WazeRemoteViewsManeuverReader.ResourceCandidate("maneuver", "turn_left"),
                WazeRemoteViewsManeuverReader.ResourceCandidate("maneuver", "turn_right"),
            ),
        )

        assertNull(selected)
    }

    @Test fun `resource-side maneuver wins over generic view-side evidence`() {
        val selected = WazeRemoteViewsManeuverReader.selectManeuver(
            listOf(
                WazeRemoteViewsManeuverReader.ResourceCandidate(
                    viewName = "turn_left",
                    resourceName = "ic_turn_right",
                ),
            ),
        )

        assertEquals(NavManeuverCodes.GAODE_RIGHT, selected?.first)
    }

    @Test fun `repeated visual notification layouts must agree on direction`() {
        val right = com.bydmate.app.navdata.WazeVisualManeuverReader.Classification(
            NavManeuverCodes.GAODE_RIGHT,
            0.22f,
            0.14f,
        )
        val left = right.copy(
            maneuverGaode = NavManeuverCodes.GAODE_LEFT,
            horizontalShift = -0.25f,
        )

        assertEquals(
            NavManeuverCodes.GAODE_RIGHT,
            WazeRemoteViewsManeuverReader.selectVisualManeuver(
                listOf(
                    WazeRemoteViewsManeuverReader.VisualCandidate(right, "bitmap"),
                    WazeRemoteViewsManeuverReader.VisualCandidate(right.copy(horizontalShift = 0.3f), "icon"),
                ),
            )?.classification?.maneuverGaode,
        )
        assertNull(
            WazeRemoteViewsManeuverReader.selectVisualManeuver(
                listOf(
                    WazeRemoteViewsManeuverReader.VisualCandidate(right, "bitmap"),
                    WazeRemoteViewsManeuverReader.VisualCandidate(left, "icon"),
                ),
            ),
        )
    }

    @Test fun `bitmap cache action supplies arrow without accessibility window`() {
        val width = 100
        val pixels = IntArray(width * width) { 0xff202124.toInt() }
        val white = 0xfff7f7f7.toInt()
        for (y in 38 until 92) for (x in 45 until 55) pixels[y * width + x] = white
        for (y in 28 until 43) for (x in 45 until 82) pixels[y * width + x] = white
        for (i in 0 until 20) {
            for (y in 18 + i until 20 + i) {
                for (x in 72 + i / 2 until 78 + i / 2) pixels[y * width + x] = white
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, width, Bitmap.Config.ARGB_8888)
        val packageName = ApplicationProvider.getApplicationContext<android.content.Context>().packageName
        val views = RemoteViews(packageName, android.R.layout.activity_list_item).apply {
            setImageViewBitmap(android.R.id.icon, bitmap)
        }
        val notification = Notification().apply {
            contentView = views
            extras.putString(Notification.EXTRA_TITLE, "50 m")
            extras.putString(Notification.EXTRA_TEXT, "Main Street")
        }
        val classifier: (Any, Int?) -> WazeVisualManeuverReader.Classification? = { value, _ ->
            (value as? Bitmap)?.let(WazeVisualManeuverReader::classifyBitmap)
        }

        val result = WazeRemoteViewsManeuverReader.inspect(
            notification = notification,
            resolveName = { null },
            classifyImage = classifier,
        )
        val parsed = NaviNotificationParser.parse(notification, classifyImage = classifier)

        assertEquals(NavManeuverCodes.GAODE_RIGHT, result.maneuverGaode)
        assertEquals(1, result.imagePayloadsInspected)
        assertEquals(1, result.visualClassifications)
        assertEquals(NavManeuverCodes.GAODE_RIGHT, parsed.guidance?.maneuverGaode)
        assertEquals(50, parsed.guidance?.distanceMeters)
    }
}
