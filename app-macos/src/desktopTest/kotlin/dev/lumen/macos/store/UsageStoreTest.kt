package dev.lumen.macos.store

import dev.lumen.core.clock.LocalDay
import dev.lumen.core.clock.UtcDay
import kotlinx.datetime.TimeZone
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import kotlinx.serialization.json.Json
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
    fun `a session spanning LOCAL midnight splits across both days`() {
        // The boundary is the user's midnight, not UTC's (discussion #29).
        // Under a fixed UTC zone the two coincide, which is what makes this
        // assertion identical in shape to the one it replaces.
        val zone = TimeZone.UTC
        val midnight = LocalDay.startOfDayMs("2026-03-06", zone)
        store.append(event("com.apple.Safari", midnight - 120_000, 240_000))

        assertEquals(120_000, store.totalsFor("2026-03-05", zone).single().totalMs)
        assertEquals(120_000, store.totalsFor("2026-03-06", zone).single().totalMs)
    }

    /**
     * The bug LO reported: at 23:50 in New York it is already tomorrow in
     * UTC, so a UTC-day screen showed a day the user had not started living
     * and hid the one they had.
     */
    @Test
    fun `late-evening usage belongs to the local day, not the UTC one`() {
        val newYork = TimeZone.of("America/New_York")
        // 2026-08-12T03:50Z = Tue 11 Aug, 23:50 in New York.
        val lateTuesday = 1_786_506_600_000L
        store.append(event("com.apple.Safari", lateTuesday, 600_000))

        assertEquals("2026-08-12", UtcDay.dayOf(lateTuesday))
        assertEquals(600_000, store.totalsFor("2026-08-11", newYork).single().totalMs)
        assertEquals(emptyList(), store.totalsFor("2026-08-12", newYork))
    }

    @Test
    fun `a session spanning local midnight is split at the local boundary`() {
        val newYork = TimeZone.of("America/New_York")
        val localMidnight = LocalDay.startOfDayMs("2026-08-12", newYork)
        store.append(event("com.apple.Safari", localMidnight - 120_000, 240_000))

        assertEquals(120_000, store.totalsFor("2026-08-11", newYork).single().totalMs)
        assertEquals(120_000, store.totalsFor("2026-08-12", newYork).single().totalMs)
    }

    @Test
    fun `the display zone is stored and honoured`() {
        store.setDisplayZone("Australia/Sydney")
        assertEquals(TimeZone.of("Australia/Sydney"), store.displayZone())
    }

    @Test
    fun `the trend window returns one entry per local day, newest last`() {
        val zone = TimeZone.UTC
        store.setDisplayZone("UTC")
        val days = store.dailyTotals(7, zone)
        assertEquals(7, days.size)
        assertEquals(days.map { it.dayUtc }.sorted(), days.map { it.dayUtc })
        assertTrue(days.last().isToday, "the newest entry is today")
        assertEquals(1, days.count { it.isToday })
    }

    @Test
    fun `an empty day has no totals`() {
        assertEquals(emptyList(), store.totalsFor("2026-01-01"))
    }

    @Test
    fun `events survive a restart`() {
        store.append(event("com.apple.Safari", noon, 90_000))
        assertEquals(90_000, UsageStore(tmp).totalsFor(day).single().totalMs)
    }

    /**
     * The bug this store's move to LumenStore had to survive.
     *
     * `FocusSessionTracker` numbers its events from 0 on every launch, and
     * `(device_id, seq)` is the primary key with `INSERT OR IGNORE` behind
     * it. Trusting the caller's seq means the second session's events are
     * silently discarded — no error, no log, a day that stops growing.
     */
    @Test
    fun `a restarted seq counter does not drop the next session's events`() {
        store.append(event("com.apple.Safari", noon, 60_000, seq = 0))
        store.append(event("com.apple.Terminal", noon + 60_000, 60_000, seq = 1))

        // A new run of the app, its tracker counting from zero again.
        val restarted = UsageStore(tmp)
        restarted.append(event("com.apple.Mail", noon + 120_000, 60_000, seq = 0))
        restarted.append(event("com.apple.Notes", noon + 180_000, 60_000, seq = 1))

        val totals = UsageStore(tmp).totalsFor(day)
        assertEquals(4, totals.size, "events from the restarted session were dropped")
        assertEquals(60_000, totals.first { it.appKey.value == "com.apple.Mail" }.totalMs)
    }

    @Test
    fun `legacy NDJSON is migrated in on open and archived`() {
        // The state every existing install is in: events in files, nothing in
        // the database yet.
        val device = UsageStore(tmp).deviceId()
        File(tmp, "events-$day.ndjson").writeText(
            Json.encodeToString(
                FocusEvent.serializer(),
                FocusEvent(
                    seq = 0,
                    deviceId = device,
                    appKey = AppKey("com.apple.Safari"),
                    startedAtMs = noon,
                    durationMs = 120_000,
                ),
            ) + "\n",
        )

        val opened = UsageStore(tmp)

        assertEquals(1, opened.migration.migratedEvents)
        assertEquals(120_000, opened.totalsFor(day).single().totalMs)
        assertTrue(File(tmp, "migrated/events-$day.ndjson").exists(), "the source was destroyed")
        // Opening again must not re-import what it already holds.
        assertEquals(0, UsageStore(tmp).migration.migratedEvents)
        assertEquals(120_000, UsageStore(tmp).totalsFor(day).single().totalMs)
    }
}
