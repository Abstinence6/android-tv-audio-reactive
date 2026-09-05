package org.hyperion.audioreactive

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureServiceLifecycleTest {
    @Test fun stopThenStartRaceRejectsBeforeRouteAdmissionOrForegroundWork() {
        val events = mutableListOf<String>()
        val lifecycle = CaptureServiceLifecycle(cleanup = { events += "cleanup" })

        assertTrue(lifecycle.stop())
        assertFalse(lifecycle.beginStart { events += "reserve"; true })
        assertFalse(lifecycle.whileStarting { events += "foreground" })

        assertEquals(listOf("cleanup"), events)
    }

    @Test fun stopWaitsForLinearizedStartActionBeforePublishingCancellationAndBlocksLaterStartup() {
        val startActionEntered = CountDownLatch(1)
        val allowStartActionToFinish = CountDownLatch(1)
        val cancellationPublished = CountDownLatch(1)
        val cleanupCompleted = CountDownLatch(1)
        val laterActionRan = AtomicBoolean()
        val stopResult = AtomicBoolean()
        val lifecycle = CaptureServiceLifecycle(
            cleanup = { cleanupCompleted.countDown() },
            onTeardownRequested = { cancellationPublished.countDown() },
        )
        assertTrue(lifecycle.beginStart { true })

        val startup = Thread {
            assertTrue(lifecycle.whileStarting {
                startActionEntered.countDown()
                assertTrue(allowStartActionToFinish.await(5, TimeUnit.SECONDS))
            })
        }
        startup.start()
        assertTrue(startActionEntered.await(5, TimeUnit.SECONDS))
        val stopper = Thread { stopResult.set(lifecycle.stop()) }
        stopper.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (stopper.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.BLOCKED, stopper.state)
        assertEquals(1L, cancellationPublished.count)
        assertEquals(1L, cleanupCompleted.count)

        allowStartActionToFinish.countDown()
        startup.join(5_000)
        stopper.join(5_000)

        assertFalse(startup.isAlive)
        assertFalse(stopper.isAlive)
        assertTrue(stopResult.get())
        assertEquals(0L, cancellationPublished.count)
        assertEquals(0L, cleanupCompleted.count)
        assertFalse(lifecycle.whileStarting { laterActionRan.set(true) })
        assertFalse(lifecycle.acquire(
            acquire = { laterActionRan.set(true); "projection" },
            release = { laterActionRan.set(true) },
            assign = { laterActionRan.set(true) },
        ))
        assertFalse(laterActionRan.get())
    }

    @Test fun cleanupStopsConsumedRouterBeforeOpeningAdmissionAndKeepsEarlyFailureStopActions() {
        val events = mutableListOf<String>()
        val lifecycle = CaptureServiceLifecycle(cleanup = {
            events += "router.stop"
            events += "admission.finish"
            events += "foreground.remove"
            events += "self.stop"
        })
        assertTrue(lifecycle.beginStart { true })

        assertTrue(lifecycle.stop())

        assertEquals(
            listOf("router.stop", "admission.finish", "foreground.remove", "self.stop"),
            events,
        )
    }

    @Test fun invalidStartupTerminationCleansUpOnceAndANewServiceLifecycleCanStart() {
        val events = mutableListOf<String>()
        val invalidProjectionLifecycle = CaptureServiceLifecycle(cleanup = { events += "projection.cleanup" })
        assertTrue(invalidProjectionLifecycle.beginStart { true })
        assertTrue(invalidProjectionLifecycle.stop())
        assertFalse(invalidProjectionLifecycle.stop())
        assertEquals(listOf("projection.cleanup"), events)

        val invalidRouteLifecycle = CaptureServiceLifecycle(cleanup = { events += "route.cleanup" })
        assertTrue(invalidRouteLifecycle.beginStart { true })
        assertTrue(invalidRouteLifecycle.stop())
        assertEquals(listOf("projection.cleanup", "route.cleanup"), events)

        val freshServiceLifecycle = CaptureServiceLifecycle(cleanup = { events += "fresh.cleanup" })
        assertTrue(freshServiceLifecycle.beginStart { true })
    }

    @Test fun reentrantAndConcurrentStopRequestsHaveOneCleanupOwner() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        lateinit var lifecycle: CaptureServiceLifecycle
        lifecycle = CaptureServiceLifecycle(cleanup = {
            events += "cleanup"
            assertFalse(lifecycle.stop())
        })
        assertTrue(lifecycle.beginStart { true })
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val workers = (1..8).map { Thread { results += lifecycle.stop() } }
        workers.forEach(Thread::start)
        workers.forEach { it.join(5_000) }

        assertEquals(1, results.count { it })
        assertEquals(7, results.count { !it })
        assertEquals(listOf("cleanup"), events)
    }

    @Test fun concurrentStartsAdmitExactlyOneRoute() {
        val reserves = AtomicInteger()
        val lifecycle = CaptureServiceLifecycle(cleanup = { })
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val first = Thread {
            results += lifecycle.beginStart {
                reserves.incrementAndGet()
                ready.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                true
            }
        }
        val second = Thread { results += lifecycle.beginStart { reserves.incrementAndGet(); true } }
        first.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        second.start()
        release.countDown()
        first.join(5_000)
        second.join(5_000)

        assertEquals(1, reserves.get())
        assertEquals(1, results.count { it })
        assertEquals(1, results.count { !it })
    }
}
