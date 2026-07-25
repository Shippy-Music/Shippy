package org.oxycblt.auxio.shippy.playback.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.QueueItemId

class SleepTimerPolicyTest {
    private val item = QueueItemId("item")
    private val nextItem = QueueItemId("next")

    @Test
    fun `duration uses a monotonic deadline and expires once`() {
        val policy = SleepTimerPolicy()

        assertEquals(
            1_900_000L,
            policy.arm(SleepTimerMode.MINUTES_30, item, 100_000).deadlineElapsedMs,
        )
        assertFalse(policy.onElapsed(1_899_999))
        assertTrue(policy.onElapsed(1_900_000))
        assertEquals(SleepTimerMode.OFF, policy.presentation().mode)
        assertFalse(policy.onElapsed(9_000_000))
    }

    @Test
    fun `finish current pauses only when its exact queue item changes`() {
        val policy = SleepTimerPolicy()
        policy.arm(SleepTimerMode.FINISH_CURRENT, item, 0)

        assertFalse(policy.onCurrentItem(item))
        assertTrue(policy.onCurrentItem(nextItem))
        assertEquals(SleepTimerMode.OFF, policy.presentation().mode)
    }

    @Test
    fun `finish current without an item and reset are off`() {
        val policy = SleepTimerPolicy()

        assertEquals(SleepTimerMode.OFF, policy.arm(SleepTimerMode.FINISH_CURRENT, null, 0).mode)
        policy.arm(SleepTimerMode.MINUTES_15, item, 0)
        assertEquals(SleepTimerMode.OFF, policy.reset().mode)
    }

    @Test
    fun `controller pauses at the scheduled monotonic deadline and clears presentation`() {
        var now = 50L
        var pauses = 0
        val scheduler = FakeScheduler()
        val controller =
            SleepTimerController(
                SleepTimerPlaybackPort { pauses++ },
                SleepTimerClock { now },
                scheduler,
            )

        controller.select(SleepTimerMode.MINUTES_15)
        assertEquals(900_000L, scheduler.delayMs)
        now += 900_000L
        scheduler.run()

        assertEquals(1, pauses)
        assertEquals(SleepTimerMode.OFF, controller.state.value.mode)
    }

    private class FakeScheduler : SleepTimerScheduler {
        var delayMs = -1L
        private var task: (() -> Unit)? = null

        override fun schedule(delayMs: Long, task: () -> Unit): SleepTimerCancellation {
            this.delayMs = delayMs
            this.task = task
            return SleepTimerCancellation { this.task = null }
        }

        fun run() = requireNotNull(task).invoke()
    }
}
