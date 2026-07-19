package com.bydmate.app.data.loop

import app.cash.turbine.test
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.diParsData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAdaptiveLoopLifecycleTest {

    @Test fun `emits ticks from parsReader on the schedule`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = mockk<ParsReader>()
        var n = 0
        coEvery { reader.fetch() } answers {
            n++
            // powerState=0 (OFF) -> IDLE cadence (30s)
            diParsData(soc = 80 - n, mileage = 100.0 + n, voltage12v = 12.5, powerState = 0)
        }
        val lastState = mockk<LastStateDao>(relaxed = true)
        val energy = mockk<EnergyDataReader> { every { isAvailable() } returns true }
        val loop = SharedAdaptiveLoop(reader, lastState, energy, dispatcher)

        val job = loop.start(TestScope(dispatcher))
        try {
            loop.flow.test {
                advanceTimeBy(1)             // first fetch runs immediately
                val t1 = awaitItem()
                assertEquals(79, t1.soc)
                advanceTimeBy(30_000)        // idle cadence
                val t2 = awaitItem()
                assertEquals(78, t2.soc)
            }
        } finally {
            job.cancel()
        }
    }

    @Test fun `start is idempotent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reader = mockk<ParsReader>(relaxed = true)
        coEvery { reader.fetch() } returns null
        val lastState = mockk<LastStateDao>(relaxed = true)
        val energy = mockk<EnergyDataReader>(relaxed = true)
        val loop = SharedAdaptiveLoop(reader, lastState, energy, dispatcher)
        val scope = TestScope(dispatcher)
        val j1 = loop.start(scope)
        val j2 = loop.start(scope)
        assertEquals(j1, j2)
        j1.cancel()
    }

    @Test fun `restart clears the previous generation replay`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val nextFetch = CompletableDeferred<DiParsData?>()
        var calls = 0
        val reader = object : ParsReader {
            override suspend fun fetch(): DiParsData? = when (++calls) {
                1 -> diParsData(soc = 81, powerState = 0)
                else -> nextFetch.await()
            }
        }
        val loop = SharedAdaptiveLoop(
            reader,
            mockk(relaxed = true),
            mockk(relaxed = true),
            dispatcher,
        )
        val scope = TestScope(dispatcher)

        loop.flow.test {
            loop.start(scope)
            runCurrent()
            assertEquals(81, awaitItem().soc)

            loop.stop()
            loop.start(scope)
            runCurrent()

            expectNoEvents()
            loop.stop()
        }
    }

    @Test fun `late fetch from stopped generation cannot overwrite the new generation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val oldFetchStarted = CompletableDeferred<Unit>()
        val releaseOldFetch = CompletableDeferred<DiParsData?>()
        val keepLatestFetchOpen = CompletableDeferred<DiParsData?>()
        var calls = 0
        val reader = object : ParsReader {
            override suspend fun fetch(): DiParsData? = when (++calls) {
                1 -> withContext(NonCancellable) {
                    oldFetchStarted.complete(Unit)
                    releaseOldFetch.await()
                }
                2 -> diParsData(soc = 82, powerState = 0)
                else -> keepLatestFetchOpen.await()
            }
        }
        val loop = SharedAdaptiveLoop(
            reader,
            mockk(relaxed = true),
            mockk(relaxed = true),
            dispatcher,
        )
        val scope = TestScope(dispatcher)

        loop.flow.test {
            loop.start(scope)
            runCurrent()
            oldFetchStarted.await()

            loop.stop()
            loop.start(scope)
            runCurrent()
            assertEquals(82, awaitItem().soc)

            releaseOldFetch.complete(diParsData(soc = 13, powerState = 0))
            runCurrent()
            expectNoEvents()
            assertEquals(true, loop.connected.value)
            loop.stop()
        }
    }

    @Test fun `late persistence from stopped generation cannot overwrite the new snapshot`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val oldPersistStarted = CompletableDeferred<Unit>()
        val releaseOldPersist = CompletableDeferred<Unit>()
        val keepLatestFetchOpen = CompletableDeferred<DiParsData?>()
        var fetchCalls = 0
        val reader = object : ParsReader {
            override suspend fun fetch(): DiParsData? = when (++fetchCalls) {
                1 -> diParsData(soc = 14, mileage = 14.0, powerState = 0)
                2 -> diParsData(soc = 82, mileage = 82.0, powerState = 0)
                else -> keepLatestFetchOpen.await()
            }
        }
        val persistedSoc = mutableListOf<Int?>()
        var getCurrentCalls = 0
        val lastState = mockk<LastStateDao>(relaxed = true)
        coEvery { lastState.getCurrent() } coAnswers {
            if (++getCurrentCalls == 1) {
                withContext(NonCancellable) {
                    oldPersistStarted.complete(Unit)
                    releaseOldPersist.await()
                }
            }
            null
        }
        coEvery { lastState.upsert(any()) } answers {
            persistedSoc += firstArg<com.bydmate.app.data.local.entity.LastStateEntity>().soc
        }
        val loop = SharedAdaptiveLoop(
            reader,
            lastState,
            mockk(relaxed = true),
            dispatcher,
        )
        val scope = TestScope(dispatcher)

        loop.flow.test {
            loop.start(scope)
            runCurrent()
            assertEquals(14, awaitItem().soc)
            oldPersistStarted.await()

            loop.stop()
            loop.start(scope)
            runCurrent()
            assertEquals(82, awaitItem().soc)
            assertEquals(listOf(82), persistedSoc)

            releaseOldPersist.complete(Unit)
            runCurrent()
            assertEquals(listOf(82), persistedSoc)
            loop.stop()
        }
    }
}
