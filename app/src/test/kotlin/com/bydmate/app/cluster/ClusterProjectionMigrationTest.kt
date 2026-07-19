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
}
