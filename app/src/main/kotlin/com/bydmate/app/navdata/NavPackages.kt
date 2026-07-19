package com.bydmate.app.navdata

import com.bydmate.app.navigation.WazeNavigation

/** Navigation packages whose window/notification data may feed BYDMate and the factory HUD. */
object NavPackages {
    val WAZE = setOf(WazeNavigation.PACKAGE_NAME)

    fun isNavigationPackage(packageName: String?): Boolean = packageName in WAZE
}
