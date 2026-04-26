package io.klimiter.core.internal.store

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class InMemoryRateLimitStoreTest {

    private val store = InMemoryRateLimitStore()

    @Test
    fun `same key returns same AtomicLong instance`() {
        val a = store.getOrCreate("k", 60)
        val b = store.getOrCreate("k", 60)
        assertSame(a, b)
    }

    @Test
    fun `different keys return distinct instances`() {
        val a = store.getOrCreate("k1", 60)
        val b = store.getOrCreate("k2", 60)
        assertNotSame(a, b)
    }

    @Test
    fun `ttlSeconds zero is rejected`() {
        assertFailsWith<IllegalArgumentException> { store.getOrCreate("k", 0) }
    }

    @Test
    fun `ttlSeconds negative is rejected`() {
        assertFailsWith<IllegalArgumentException> { store.getOrCreate("k", -1) }
    }

    @Test
    fun `counter starts at zero`() {
        val counter = store.getOrCreate("fresh-key", 60)
        assertEquals(0L, counter.get())
    }

    @Test
    fun `concurrent calls for the same key return the same instance`() = runTest {
        val results = mutableListOf<Any>()
        val jobs = (1..20).map {
            launch { synchronized(results) { results += store.getOrCreate("concurrent-key", 60) } }
        }
        jobs.forEach { it.join() }
        val first = results.first()
        results.forEach { assertSame(first, it) }
    }
}
