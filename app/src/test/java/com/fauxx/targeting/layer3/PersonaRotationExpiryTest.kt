package com.fauxx.targeting.layer3

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.util.Clock
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the expiry-driven rotation contract (issue #275).
 *
 * Before this, [PersonaRotationLayer.setEnabled] refreshed the persona ONLY when none was loaded.
 * A persona already in memory but past its `activeUntil` was left untouched, so every engine start
 * that kept the process alive (pause/resume, quiet hours, the resume scheduler) skipped the
 * catch-up and rotation was left entirely to the in-process 30-minute ticker. Users reported the
 * persona frozen well past the 8-10 day window.
 *
 * This is also the coverage gap that let the regression ship: every other persona test pins
 * `activeUntil` to [Long.MAX_VALUE], so nothing exercised the expiry branch at all.
 */
class PersonaRotationExpiryTest {

    private val gson = Gson()
    private val oneDay = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    private class FakeClock(var nowMs: Long) : Clock {
        override fun currentTimeMillis() = nowMs
        override fun elapsedRealtime() = nowMs
    }

    /** In-memory [PersonaHistoryDao] modelling autoincrement ids and both orderings. */
    private class FakeHistoryDao : PersonaHistoryDao {
        val rows = mutableListOf<PersonaHistoryEntity>()
        private var nextId = 1L
        override suspend fun insert(entry: PersonaHistoryEntity) {
            rows += if (entry.id == 0L) entry.copy(id = nextId++) else entry.also {
                nextId = maxOf(nextId, it.id + 1)
            }
        }
        override suspend fun getRecentPersonas(sinceMillis: Long) =
            rows.filter { it.createdAt > sinceMillis }.sortedByDescending { it.createdAt }
        override suspend fun getRecentByInsertOrder(sinceMillis: Long) =
            rows.filter { it.createdAt > sinceMillis }.sortedByDescending { it.id }
        override suspend fun deleteAll() = rows.clear()
        override suspend fun pruneOlderThan(beforeMillis: Long) {
            rows.removeAll { it.createdAt < beforeMillis }
        }
    }

    private fun persona(name: String, createdAt: Long, activeUntil: Long) = SyntheticPersona(
        id = name,
        name = name,
        ageRange = "AGE_35_44",
        profession = "ENGINEER",
        region = "US_MIDWEST",
        interests = setOf(CategoryPool.TECHNOLOGY),
        createdAt = createdAt,
        activeUntil = activeUntil,
    )

    private fun layerWith(
        clock: FakeClock,
        dao: FakeHistoryDao = FakeHistoryDao(),
        generated: SyntheticPersona = persona("Fresh Fran", now, now + 9 * oneDay),
    ): Pair<PersonaRotationLayer, FakeHistoryDao> {
        val generator: PersonaGenerator = mockk(relaxed = true)
        coEvery { generator.generate(any()) } returns generated
        return PersonaRotationLayer(generator, dao, clock) to dao
    }

    @Test
    fun `an expired in-memory persona is treated as needing refresh`() {
        val clock = FakeClock(now)
        val (layer, _) = layerWith(clock)
        layer.setPersonasForTest(
            current = persona("Stale Sam", now - 10 * oneDay, activeUntil = now - oneDay),
            previous = null,
        )
        // The #275 fix: this returned false before, so setEnabled() did nothing and rotation
        // waited on the ticker.
        assertTrue("An expired persona must trigger a refresh", layer.needsPersonaRefresh())
    }

    @Test
    fun `a still-active in-memory persona is left alone`() {
        val clock = FakeClock(now)
        val (layer, _) = layerWith(clock)
        layer.setPersonasForTest(
            current = persona("Active Ann", now - oneDay, activeUntil = now + 5 * oneDay),
            previous = null,
        )
        // Guards #63 in the other direction: restarts must not burn a rotation.
        assertFalse("An active persona must not be rotated early", layer.needsPersonaRefresh())
    }

    @Test
    fun `no persona loaded needs a refresh`() {
        val (layer, _) = layerWith(FakeClock(now))
        assertTrue(layer.needsPersonaRefresh())
    }

    @Test
    fun `expired persona with no usable history rotates to a freshly generated one`() = runTest {
        val clock = FakeClock(now)
        val fresh = persona("Fresh Fran", now, now + 9 * oneDay)
        val (layer, dao) = layerWith(clock, generated = fresh)
        layer.setPersonasForTest(
            current = persona("Stale Sam", now - 10 * oneDay, activeUntil = now - oneDay),
            previous = null,
        )

        layer.ensureActivePersonaInternal()

        assertEquals("Fresh Fran", layer.currentPersona.first()?.name)
        assertEquals("rotation must be journalled to history", 1, dao.rows.size)
    }

    @Test
    fun `expired persona prefers a still-active persona from history over generating`() = runTest {
        val clock = FakeClock(now)
        val dao = FakeHistoryDao()
        // e.g. adopted from a paired device (#234) while this process held an older persona.
        val synced = persona("Synced Sam", now - 2 * oneDay, activeUntil = now + 6 * oneDay)
        dao.insert(PersonaHistoryEntity(personaJson = gson.toJson(synced), createdAt = synced.createdAt))

        val (layer, _) = layerWith(clock, dao = dao)
        layer.setPersonasForTest(
            current = persona("Stale Sam", now - 10 * oneDay, activeUntil = now - oneDay),
            previous = null,
        )

        layer.ensureActivePersonaInternal()

        assertEquals("Synced Sam", layer.currentPersona.first()?.name)
        assertEquals("restore must not write a new history row", 1, dao.rows.size)
    }

    /**
     * The one that actually pins the #275 fix.
     *
     * The other tests here drive [PersonaRotationLayer.needsPersonaRefresh] and
     * [PersonaRotationLayer.ensureActivePersonaInternal] directly, which pins each piece but NOT
     * that `setEnabled` wires them together — and the guard inside `setEnabled` IS the fix. With
     * only those tests, reverting that line to the old `_currentPersona.value == null` check leaves
     * the entire suite green and silently restores the reported bug.
     *
     * `setEnabled` dispatches onto the layer's own IO scope, so this awaits the resulting emission
     * rather than asserting synchronously (same shape as PersonaRotationChannelTest).
     */
    @Test
    fun `setEnabled with an expired persona rotates through the real entry point`() {
        val clock = FakeClock(now)
        val fresh = persona("Fresh Fran", now, now + 9 * oneDay)
        val (layer, _) = layerWith(clock, generated = fresh)
        layer.setPersonasForTest(
            current = persona("Stale Sam", now - 10 * oneDay, activeUntil = now - oneDay),
            previous = null,
        )

        layer.setEnabled(true)

        val rotated = runBlocking {
            withTimeout(5_000) { layer.currentPersona.first { it?.name == "Fresh Fran" } }
        }
        assertEquals("Fresh Fran", rotated?.name)
    }

    /**
     * The other direction, guarding #63: an engine restart while the persona is still valid must
     * NOT burn a rotation. Together with the test above this pins the guard in both directions, so
     * neither "never refreshes" nor "always refreshes" can slip through.
     */
    @Test
    fun `setEnabled with an active persona does not rotate`() {
        val clock = FakeClock(now)
        val (layer, dao) = layerWith(clock)
        val active = persona("Active Ann", now - oneDay, activeUntil = now + 5 * oneDay)
        layer.setPersonasForTest(current = active, previous = null)

        layer.setEnabled(true)

        // Give the IO scope a real window to misbehave in, then assert nothing moved.
        val stillActive = runBlocking {
            delay(500)
            layer.currentPersona.first()
        }
        assertEquals("the active persona must be left alone", "Active Ann", stillActive?.name)
        assertEquals("no rotation may be journalled", 0, dao.rows.size)
    }

    @Test
    fun `an expired persona in history is not restored`() = runTest {
        val clock = FakeClock(now)
        val dao = FakeHistoryDao()
        val dead = persona("Dead Dan", now - 12 * oneDay, activeUntil = now - 2 * oneDay)
        dao.insert(PersonaHistoryEntity(personaJson = gson.toJson(dead), createdAt = dead.createdAt))

        val fresh = persona("Fresh Fran", now, now + 9 * oneDay)
        val (layer, _) = layerWith(clock, dao = dao, generated = fresh)

        layer.ensureActivePersonaInternal()

        assertEquals("Fresh Fran", layer.currentPersona.first()?.name)
    }
}
