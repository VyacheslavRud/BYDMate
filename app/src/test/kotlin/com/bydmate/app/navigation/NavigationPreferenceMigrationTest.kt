package com.bydmate.app.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.ui.widget.WidgetPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavigationPreferenceMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearPreferences() {
        context.getSharedPreferences(WidgetPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun `single startup migration converts widget and cluster legacy targets`() {
        context.getSharedPreferences(WidgetPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(WidgetPreferences.KEY_LEFT_TAP_APP_PKG, "ru.yandex.yandexnavi")
            .putString(WidgetPreferences.KEY_LEFT_TAP_APP_LABEL, "Яндекс.Навигатор")
            .commit()
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(ClusterProjectionManager.KEY_TARGET_PACKAGE, "ru.yandex.yandexnavi")
            .putString(ClusterProjectionManager.KEY_TARGET_LABEL, "Яндекс.Навигатор")
            .commit()

        NavigationPreferenceMigration.run(context)

        assertEquals(
            WazeNavigation.PACKAGE_NAME,
            WidgetPreferences(context).getLeftTapAppPackage(),
        )
        assertEquals(
            WazeNavigation.PACKAGE_NAME,
            context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(ClusterProjectionManager.KEY_TARGET_PACKAGE, null),
        )
    }

    @Test fun `migration preserves legacy task owner before replacing active direct target`() {
        val prefs = context.getSharedPreferences(
            ClusterProjectionManager.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        prefs.edit()
            .putString(
                ClusterProjectionManager.KEY_TARGET_PACKAGE,
                WazeNavigation.LEGACY_DEFAULT_PACKAGE,
            )
            .putInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, 2)
            .commit()

        NavigationPreferenceMigration.run(context)

        assertEquals(
            WazeNavigation.PACKAGE_NAME,
            prefs.getString(ClusterProjectionManager.KEY_TARGET_PACKAGE, null),
        )
        assertEquals(
            WazeNavigation.LEGACY_DEFAULT_PACKAGE,
            prefs.getString(ClusterProjectionManager.KEY_DIRECT_PACKAGE, null),
        )
    }

    @Test fun `migration knows old default owner when active direct target was absent`() {
        val prefs = context.getSharedPreferences(
            ClusterProjectionManager.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        prefs.edit()
            .putInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, 2)
            .commit()

        NavigationPreferenceMigration.run(context)

        assertEquals(
            WazeNavigation.LEGACY_DEFAULT_PACKAGE,
            prefs.getString(ClusterProjectionManager.KEY_DIRECT_PACKAGE, null),
        )
    }
}
