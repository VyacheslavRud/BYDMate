package com.bydmate.app.cluster

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.navigation.WazeNavigation
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClusterProjectionMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs by lazy {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Before fun clear() {
        prefs.edit().clear().commit()
    }

    @Test fun `legacy Yandex projection target migrates to Waze`() {
        prefs.edit()
            .putString(ClusterProjectionManager.KEY_TARGET_PACKAGE, "ru.yandex.yandexnavi")
            .putString(ClusterProjectionManager.KEY_TARGET_LABEL, "Яндекс.Навигатор")
            .commit()

        ClusterProjectionManager.migrateLegacyNavigationTarget(context)

        assertEquals(WazeNavigation.PACKAGE_NAME,
            prefs.getString(ClusterProjectionManager.KEY_TARGET_PACKAGE, null))
        assertEquals(WazeNavigation.APP_LABEL,
            prefs.getString(ClusterProjectionManager.KEY_TARGET_LABEL, null))
    }

    @Test fun `custom projection target is preserved`() {
        prefs.edit()
            .putString(ClusterProjectionManager.KEY_TARGET_PACKAGE, "com.example.player")
            .putString(ClusterProjectionManager.KEY_TARGET_LABEL, "Player")
            .commit()

        ClusterProjectionManager.migrateLegacyNavigationTarget(context)

        assertEquals("com.example.player",
            prefs.getString(ClusterProjectionManager.KEY_TARGET_PACKAGE, null))
        assertEquals("Player", prefs.getString(ClusterProjectionManager.KEY_TARGET_LABEL, null))
    }

    @Test fun `factory projection migration disables direct mode and preserves explicit container choice`() {
        prefs.edit()
            .putBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, true)
            .putBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION_ENABLED, true)
            .putBoolean(ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING, true)
            .putInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, 2)
            .putString(ClusterProjectionManager.KEY_DIRECT_PACKAGE, WazeNavigation.PACKAGE_NAME)
            .commit()

        ClusterProjectionManager.migrateFactoryProjectionDefaults(context)

        assertEquals(true, prefs.getBoolean(ClusterProjectionManager.KEY_AUTO_CONTAINER, false))
        assertEquals(false, prefs.getBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION_ENABLED, true))
        assertEquals(true, prefs.getBoolean(ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING, false))
        assertEquals(2, prefs.getInt(ClusterProjectionManager.KEY_DIRECT_DISPLAY_ID, -1))
        assertEquals(
            WazeNavigation.PACKAGE_NAME,
            prefs.getString(ClusterProjectionManager.KEY_DIRECT_PACKAGE, null),
        )

        // The one-shot migration must not overwrite a future explicit compatibility choice.
        prefs.edit().putBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION_ENABLED, true).commit()
        ClusterProjectionManager.migrateFactoryProjectionDefaults(context)
        assertEquals(true, prefs.getBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION_ENABLED, false))
    }

    @Test fun `fresh install does not require a factory transport reboot`() {
        ClusterProjectionManager.migrateFactoryProjectionDefaults(context)

        assertEquals(false, prefs.getBoolean(ClusterProjectionManager.KEY_DIRECT_PROJECTION_ENABLED, true))
        assertEquals(false, prefs.getBoolean(ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING, true))
    }
}
