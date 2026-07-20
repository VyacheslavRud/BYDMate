package com.bydmate.app.cluster

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ClusterLabProjectionLeaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var ownedLease: ClusterLabProjectionLease? = null

    @After fun tearDown() = runTest {
        ownedLease?.let { ClusterProjectionManager.releaseClusterLabLease(it) }
        ownedLease = null
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun `only one lab session can own projection`() = runTest {
        val first = ClusterProjectionManager.acquireClusterLabLease()
        ownedLease = first

        assertNotNull(first)
        assertNull(ClusterProjectionManager.acquireClusterLabLease())
        assertTrue(ClusterProjectionManager.releaseClusterLabLease(checkNotNull(first)))
        ownedLease = null

        val next = ClusterProjectionManager.acquireClusterLabLease()
        ownedLease = next
        assertNotNull(next)
    }
}
