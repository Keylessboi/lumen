package dev.lumen.macos.store

import dev.lumen.core.clock.UtcDay
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.store.JvmLumenStore
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The migration runs once, against a real user's only copy of their history.
 * These tests are about not losing it.
 */
class NdjsonMigrationTest {

    private val tmp: File = Files.createTempDirectory("lumen-migration").toFile()
    private val device = DeviceId("11111111-2222-3333-4444-555555555555")
    private val json = Json

    @AfterTest
    fun cleanup() = tmp.deleteRecursively().let { }

    /**
     * A day's worth of events, written into that day.
     *
     * The timestamps are derived from [day] rather than fixed: one file per
     * UTC day is the whole shape of this cache, so two files cannot describe
     * the same instant, and a fixture that says they can is asserting on
     * something that cannot occur.
     */
    private fun writeEvents(day: String, count: Int, startSeq: Long = 0L) {
        val noon = UtcDay.boundary(day) + 12 * 3_600_000L
        val text = (0 until count).joinToString("\n") { i ->
            json.encodeToString(
                FocusEvent.serializer(),
                FocusEvent(
                    seq = startSeq + i,
                    deviceId = device,
                    appKey = AppKey("com.app.$i"),
                    startedAtMs = noon + i * 60_000L,
                    durationMs = 60_000L,
                ),
            )
        }
        File(tmp, "events-$day.ndjson").writeText(text + "\n")
    }

    @Test
    fun `every event survives the move`() {
        writeEvents("2026-06-15", 10)
        writeEvents("2026-06-16", 5, startSeq = 100)
        val store = JvmLumenStore.inMemory()

        val report = NdjsonMigration.run(tmp, store, device)

        assertEquals(15, report.migratedEvents)
        assertEquals(0, report.skippedLines)
        assertEquals(15, store.eventsAfter(device, -1L).size)
    }

    @Test
    fun `the source files are archived, never deleted`() {
        // The NDJSON is a real user's only copy. If the database later turns
        // out to be wrong, the original must still exist.
        writeEvents("2026-06-15", 3)
        NdjsonMigration.run(tmp, JvmLumenStore.inMemory(), device)

        assertTrue(!File(tmp, "events-2026-06-15.ndjson").exists(), "source left in place")
        assertTrue(File(tmp, "migrated/events-2026-06-15.ndjson").exists(), "source was destroyed")
    }

    @Test
    fun `running twice does not double anything`() {
        // A migration that half-ran and was retried must not double every
        // number in the user's history.
        writeEvents("2026-06-15", 10)
        val store = JvmLumenStore.inMemory()

        NdjsonMigration.run(tmp, store, device)
        val second = NdjsonMigration.run(tmp, store, device)

        assertEquals(0, second.migratedEvents)
        assertEquals(10, store.eventsAfter(device, -1L).size)
    }

    @Test
    fun `one corrupt line costs one event, not the file`() {
        writeEvents("2026-06-15", 5)
        File(tmp, "events-2026-06-15.ndjson").appendText("{not json at all\n")
        File(tmp, "events-2026-06-15.ndjson").appendText("\n")

        val report = NdjsonMigration.run(tmp, JvmLumenStore.inMemory(), device)

        assertEquals(5, report.migratedEvents)
        assertEquals(1, report.skippedLines, "blank lines must not count as corruption")
    }

    @Test
    fun `seq is rewritten into the store's space rather than colliding`() {
        // Two legacy files can both contain seq 0. (device_id, seq) is the
        // primary key, so a naive copy would silently drop the second.
        writeEvents("2026-06-15", 3, startSeq = 0)
        writeEvents("2026-06-16", 3, startSeq = 0)
        val store = JvmLumenStore.inMemory()

        NdjsonMigration.run(tmp, store, device)

        val seqs = store.eventsAfter(device, -1L).map { it.seq }
        assertEquals(6, seqs.size, "colliding seqs were dropped")
        assertEquals(seqs.distinct().size, seqs.size)
    }

    @Test
    fun `migrating into a store that already holds events continues the sequence`() {
        val store = JvmLumenStore.inMemory()
        store.insertEvent(
            FocusEvent(seq = 0L, deviceId = device, appKey = AppKey("existing"), startedAtMs = 1L, durationMs = 1L),
        )
        writeEvents("2026-06-15", 3)

        NdjsonMigration.run(tmp, store, device)

        assertEquals(4, store.eventsAfter(device, -1L).size)
    }

    /**
     * Found by running it twice for real: `File.renameTo` is `rename(2)` on
     * macOS and replaces the destination without a word. The second run
     * archived a 2-line file over the 223-line archive of the same day, which
     * is exactly the loss "archives rather than deletes" exists to prevent.
     */
    @Test
    fun `a second archive of the same day does not overwrite the first`() {
        writeEvents("2026-06-15", 10)
        NdjsonMigration.run(tmp, JvmLumenStore.inMemory(), device)
        val firstArchive = File(tmp, "migrated/events-2026-06-15.ndjson").readLines()

        // The same day reappears — an older build wrote it, or a restore did.
        writeEvents("2026-06-15", 1, startSeq = 500)
        NdjsonMigration.run(tmp, JvmLumenStore.inMemory(), device)

        assertEquals(
            firstArchive,
            File(tmp, "migrated/events-2026-06-15.ndjson").readLines(),
            "the first archive was written over",
        )
        assertTrue(
            File(tmp, "migrated/events-2026-06-15.ndjson.1").exists(),
            "the second file was not archived at all",
        )
    }

    /**
     * Idempotence must not depend on the archiving step. If a source cannot
     * be moved aside it is read again next time, and the seq is rewritten on
     * the way in — so seq cannot be what makes the second read harmless.
     */
    @Test
    fun `a source that was never archived is not migrated twice`() {
        writeEvents("2026-06-15", 6)
        val store = JvmLumenStore.inMemory()
        NdjsonMigration.run(tmp, store, device)

        // Put the source back exactly as it was, as a failed move would.
        File(tmp, "migrated/events-2026-06-15.ndjson")
            .copyTo(File(tmp, "events-2026-06-15.ndjson"))
        val second = NdjsonMigration.run(tmp, store, device)

        assertEquals(0, second.migratedEvents, "the same history was inserted twice")
        assertEquals(6, store.eventsAfter(device, -1L).size)
    }

    @Test
    fun `a file left behind is reported rather than silently skipped`() {
        writeEvents("2026-06-15", 2)
        // A directory where the archived file would go: the move cannot
        // succeed, and the report must not claim it did.
        File(tmp, "migrated/events-2026-06-15.ndjson").also { it.mkdirs() }
        // Every suffixed alternative is taken too.
        for (n in 1 until 1_000) File(tmp, "migrated/events-2026-06-15.ndjson.$n").mkdirs()

        val report = NdjsonMigration.run(tmp, JvmLumenStore.inMemory(), device)

        assertEquals(0, report.filesMoved)
        assertEquals(listOf("events-2026-06-15.ndjson"), report.filesLeftBehind)
        assertTrue(File(tmp, "events-2026-06-15.ndjson").exists(), "the source vanished")
    }

    @Test
    fun `nothing to migrate is not an error`() {
        val report = NdjsonMigration.run(tmp, JvmLumenStore.inMemory(), device)
        assertEquals(0, report.migratedEvents)
        assertTrue(!report.ranAtAll)
        assertTrue(!NdjsonMigration.isPending(tmp))
    }

    @Test
    fun `pending is true only while legacy files remain`() {
        writeEvents("2026-06-15", 1)
        assertTrue(NdjsonMigration.isPending(tmp))
        NdjsonMigration.run(tmp, JvmLumenStore.inMemory(), device)
        assertTrue(!NdjsonMigration.isPending(tmp), "still pending after a successful run")
    }

    @Test
    fun `events are attributed to the store's device`() {
        // The NDJSON may carry an older device id; migrated rows must join to
        // the device the store actually uses or they reference nothing.
        File(tmp, "events-2026-06-15.ndjson").writeText(
            json.encodeToString(
                FocusEvent.serializer(),
                FocusEvent(0L, DeviceId("some-other-device"), AppKey("a"), startedAtMs = 1L, durationMs = 1L),
            ) + "\n",
        )
        val store = JvmLumenStore.inMemory()
        NdjsonMigration.run(tmp, store, device)

        assertEquals(1, store.eventsAfter(device, -1L).size)
    }
}
