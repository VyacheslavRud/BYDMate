package com.bydmate.app.navigation

import android.content.Context
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.ui.widget.WidgetPreferences

/** Explicit, one-way migration of navigation targets saved by pre-Waze builds. */
object NavigationPreferenceMigration {
    fun run(context: Context) {
        WidgetPreferences.migrateLegacyPreferences(context)
        ClusterProjectionManager.migrateLegacyNavigationTarget(context)
    }
}
