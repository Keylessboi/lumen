package dev.lumen.macos.store

import dev.lumen.core.clock.UtcDay
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsageStoreTest {

    private val tmp: File = Files.createTempDirectory("lumen-store-test").toFile()
    private val store = UsageStore(tmp)
    private val device = DeviceId("test-device")

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun event(app: String, startMs: Long, durationMs: Long, seq: Long = 0) =
        FocusEvent(
            seq = seq,
            deviceId = device,
            appKey = AppKey(app),
            startedAtMs = startMs,
            durationMs = durationMs,
        )

    /** Noon UTC on a fixed day, so tests never straddle a real midnight. */
    private val noon = UtcDay.boundary("2026-03-05") + 12 * 3_600_000L
    private val day = "2026-03-05"

    @Test
    fun `device id is stable across instances`() {
        val first = store.deviceId()
        val second = UsageStore(tmp).deviceId()
        assertEquals(first.value, second.value)
        assertTrue(first.value.isNotBlank())
    }

    @Test
    fun `totals sum per app and sort largest first`() {
        store.append(event("com.apple.Safari", noon, 60_000, seq = 0))
        store.append(event("com.apple.Terminal", noon + 60_000, 120_000, seq = 1))
        store.append(event("com.apple.Safari", noon + 180_000, 30_000, seq = 2))

        val totals = store.totalsFor(day)

        assertEquals(listOf("com.apple.Terminal", "com.apple.Safari"), totals.map { it.appKey.value })
        assertEquals(120_000, totals[0].totalMs)
        assertEquals(90_000, totals[1].totalMs)
    }

    @Test
    fun `display name is used when known and falls back to the bundle id`() {
        store.rememberName(AppKey("com.apple.Safari"), "Safari")
        store.append(event("com.apple.Safari", noon, 60_000))
        store.append(event("com.unknown.app", noon + 60_000, 60_000, seq = 1))

        val byKey = store.totalsFor(day).associateBy { it.appKey.value }
        assertEquals("Safari", byKey.getValue("com.apple.Safari").displayName)
        assertEquals("com.unknown.app", byKey.getValue("com.unknown.app").displayName)
    }

    @Test
    fun `a later name wins`() {
        store.rememberName(AppKey("com.foo"), "Old Name")
        store.rememberName(AppKey("com.foo"), "New Name")
        assertEquals("New Name", store.names()["com.foo"])
    }

    /**
     * The locked UTC-day rule, where it actually bites: a session running
     * through midnight belongs to BOTH days, split at the boundary — not
     * wholly to the day it started in.
     */
    @Test
    fun `a session spanning UTC midnight splits across both days`() {
        val midnight = UtcDay.boundary("2026-03-06")
        // Starts 2 minutes before midnight, runs 4 minutes.
        store.append(event("com.apple.Safari", midnight - 120_000, 240_000))

        assertEquals(120_000, store.totalsFor("2026-03-05").single().totalMs)
        assertEquals(120_000, store.totalsFor("2026-03-06").single().totalMs)
    }

    @Test
    fun `an empty day has no totals`() {
        assertEquals(emptyList(), store.totalsFor("2026-01-01"))
    }

    /** One bad line must not cost the whole day's history. */
    @Test
    fun `a corrupt line is skipped and the rest survives`() {
        store.append(event("com.apple.Safari", noon, 60_000))
        File(tmp, "events-$day.ndjson").appendText("{not valid json\n")
        store.append(event("com.apple.Terminal", noon + 60_000, 30_000, seq = 1))

        val totals = store.totalsFor(day)
        assertEquals(2, totals.size)
        assertEquals(60_000, totals.first { it.appKey.value == "com.apple.Safari" }.totalMs)
    }

    @Test
    fun `events survive a restart`() {
        store.append(event("com.apple.Safari", noon, 90_000))
        assertEquals(90_000, UsageStore(tmp).totalsFor(day).single().totalMs)
    }
}
