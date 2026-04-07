package com.avsystem.justworks.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoTest {
    @Test
    fun `Memoized should compute only once`() {
        val counter = AtomicInteger(0)
        val memo = Memo { counter.incrementAndGet() }

        assertEquals(1, memo.getValue(null, null))
        assertEquals(1, memo.getValue(null, null))
        assertEquals(1, counter.get())
    }

    @Test
    fun `Memoized reset should force recompute`() {
        val counter = AtomicInteger(0)
        val memo = Memo { counter.incrementAndGet() }

        assertEquals(1, memo.getValue(null, null))
        memo.reset()
        assertEquals(2, memo.getValue(null, null))
        assertEquals(2, counter.get())
    }

    @Test
    fun `Memoized should be thread safe`() {
        val counter = AtomicInteger(0)
        val memo = Memo {
            Thread.sleep(10)
            counter.incrementAndGet()
        }

        val threads = List(10) {
            thread {
                memo.getValue(null, null)
            }
        }
        threads.forEach { it.join() }

        assertEquals(1, counter.get())
    }

    @Test
    fun `MemoScope should reset all memoized instances`() {
        val counter1 = AtomicInteger(0)
        val counter2 = AtomicInteger(0)
        val memoScope = MemoScope()

        val m1 = memoized(memoScope) { counter1.incrementAndGet() }
        val m2 = memoized(memoScope) { counter2.incrementAndGet() }

        assertEquals(1, m1.getValue(null, null))
        assertEquals(1, m2.getValue(null, null))

        memoScope.reset()

        assertEquals(2, m1.getValue(null, null))
        assertEquals(2, m2.getValue(null, null))
    }

    @Test
    fun `memoized helper should add to MemoScope`() {
        val memoScope = MemoScope()
        val counter = AtomicInteger(0)
        val m = memoized(memoScope) { counter.incrementAndGet() }

        assertEquals(1, m.getValue(null, null))
        memoScope.reset()
        assertEquals(2, m.getValue(null, null))
    }
}
