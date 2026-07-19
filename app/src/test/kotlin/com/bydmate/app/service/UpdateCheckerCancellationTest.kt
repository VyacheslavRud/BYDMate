package com.bydmate.app.service

import android.app.DownloadManager
import android.content.Context
import android.database.MatrixCursor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateCheckerCancellationTest {
    @Test fun `cancelling progress polling removes DownloadManager job`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val downloadManager = mockk<DownloadManager>(relaxed = true)
        val enqueued = CountDownLatch(1)
        every { context.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        every { downloadManager.enqueue(any()) } answers {
            enqueued.countDown()
            42L
        }
        every { downloadManager.query(any()) } answers {
            MatrixCursor(
                arrayOf(
                    DownloadManager.COLUMN_STATUS,
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                )
            ).apply {
                addRow(arrayOf<Any>(DownloadManager.STATUS_RUNNING, 100L, 10L))
            }
        }

        val job = launch(Dispatchers.Default) {
            UpdateChecker(OkHttpClient()).downloadAndInstall(
                context,
                UpdateChecker.UpdateInfo(
                    version = "9.9.9",
                    downloadUrl = "https://example.com/BYDMate-v9.9.9.apk",
                    releaseNotes = "",
                ),
            )
        }

        assertTrue(enqueued.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()

        verify(exactly = 1) { downloadManager.remove(42L) }
    }
}
