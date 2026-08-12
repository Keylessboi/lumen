package dev.lumen.macos.store

import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.store.LumenStore
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

/**
 * Moves an existing NDJSON cache into [LumenStore], once.
 *
 * `app-macos` shipped ahead of the store seam and kept its own append-only
 * event files. Those files are a real user's real history — on this machine,
 * 1731 events across 25 days, most of it recovered from Apple's Screen Time
 * store and impossible to recover again if dropped. So the migration is
 * written to be boring and safe rather than clever.
 *
 * ## The rules it follows
 *
 * - **Never delete the source, and never write over an archived one.** The
 *   NDJSON is moved into `migrated/`, not removed, and a name already taken
 *   there gets a suffix. If the database is later found to be wrong, the
 *   original record still exists. Disk is cheap; a month of someone's history
 *   is not.
 * - **Idempotent by content.** An event already in the store is not inserted
 *   again, identified by app, start and duration rather than by seq — the seq
 *   is rewritten on the way in, so it cannot be the identity. A migration that
 *   half-ran and was retried must not double every number, and that must not
 *   depend on the archiving step having succeeded.
 * - **Skip, don't abort, on a bad line.** One corrupt line should cost one
 *   event, not the other 1730. The count of skipped lines is reported so a
 *   silent partial migration is impossible.
 * - **Only after the write succeeds** is the source moved aside.
 */
object NdjsonMigration {

    private val json = Json { ignoreUnknownKeys = true }

    data class Report(
        val migratedEvents: Int,
        val skippedLines: Int,
        val filesMoved: Int,
        /**
         * Sources that could not be archived and are therefore still on disk.
         *
         * Not cosmetic: a file left in place is read again by the next run.
         * The content check in [run] keeps that from doubling anyone's
         * history, but a migration that cannot put its sources beyond reach
         * has half-failed and should say so.
         */
        val filesLeftBehind: List<String> = emptyList(),
    ) {
        val ranAtAll: Boolean get() = migratedEvents > 0 || filesMoved > 0
    }

    /** True when there is legacy data still to move. */
    fun isPending(root: File): Boolean = eventFiles(root).isNotEmpty()

    /**
     * Migrate every legacy event file into [store].
     *
     * [deviceId] is the store's device, which must be the one the NDJSON was
     * written under — otherwise the migrated events belong to a device that
     * never existed and will not join to anything.
     */
    fun run(root: File, store: LumenStore, deviceId: DeviceId): Report {
        val files = eventFiles(root)
        if (files.isEmpty()) return Report(0, 0, 0)

        var migrated = 0
        var skipped = 0
        val existing = store.eventsAfter(deviceId, -1L)
        // Continue the sequence rather than restarting it: (device_id, seq) is
        // the PK, and a restarted sequence would collide with anything already
        // there and be silently dropped by INSERT OR IGNORE.
        var nextSeq = (existing.maxOfOrNull { it.seq } ?: -1L) + 1L
        // Identity by CONTENT, not by seq, because the seq is rewritten on the
        // way in — so re-reading a file the store already holds would insert
        // every one of its events a second time under fresh keys and double
        // that history. One device cannot start two sessions of the same app
        // at the same millisecond, so this triple is an identity.
        val seen = existing.mapTo(mutableSetOf()) { identityOf(it) }

        for (file in files) {
            for (line in file.readLines()) {
                if (line.isBlank()) continue
                val event = runCatching { json.decodeFromString(FocusEvent.serializer(), line) }.getOrNull()
                if (event == null) {
                    skipped++
                    continue
                }
                val stamped = event.copy(deviceId = deviceId, seq = nextSeq)
                if (!seen.add(identityOf(stamped))) continue
                store.insertEvent(stamped)
                nextSeq++
                migrated++
            }
        }

        // Only now, with everything written, put the originals beyond reach of
        // a second migration — without destroying them.
        val archive = File(root, "migrated").also { it.mkdirs() }
        var moved = 0
        val leftBehind = mutableListOf<String>()
        for (file in files) {
            if (archive(file, archive)) moved++ else leftBehind += file.name
        }

        return Report(
            migratedEvents = migrated,
            skippedLines = skipped,
            filesMoved = moved,
            filesLeftBehind = leftBehind,
        )
    }

    private fun identityOf(event: FocusEvent): Triple<String, Long, Long> =
        Triple(event.appKey.value, event.startedAtMs, event.durationMs)

    /**
     * Move [file] into [archive] without ever writing over what is already
     * there.
     *
     * `File.renameTo` is `rename(2)` on macOS, which replaces the destination
     * silently. A second migration — one legacy file reappearing, an older
     * build having written one, a restore from backup — therefore destroyed
     * the archive of the first: on this Mac a 223-line archive of a real day
     * was replaced by a 2-line one, which is precisely the loss the archive
     * exists to prevent. So a taken name gets a suffix rather than a victim.
     */
    private fun archive(file: File, archive: File): Boolean {
        var candidate = File(archive, file.name)
        var n = 1
        while (candidate.exists() && n < MAX_ARCHIVE_SUFFIX) {
            candidate = File(archive, "${file.name}.$n")
            n++
        }
        if (candidate.exists()) return false
        return runCatching {
            Files.move(file.toPath(), candidate.toPath())
            true
        }.getOrDefault(false)
    }

    /** Enough to be effectively unbounded; a guard against spinning, not a policy. */
    private const val MAX_ARCHIVE_SUFFIX = 1_000

    private fun eventFiles(root: File): List<File> =
        (root.listFiles { f -> f.isFile && f.name.startsWith("events-") && f.name.endsWith(".ndjson") } ?: emptyArray())
            .sortedBy { it.name }
}
