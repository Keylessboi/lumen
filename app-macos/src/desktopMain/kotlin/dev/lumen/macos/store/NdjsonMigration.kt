package dev.lumen.macos.store

import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.core.store.LumenStore
import kotlinx.serialization.json.Json
import java.io.File

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
 * - **Never delete the source.** The NDJSON is renamed to `migrated/`, not
 *   removed. If the database is later found to be wrong, the original record
 *   still exists. Disk is cheap; a month of someone's history is not.
 * - **Idempotent.** Events insert by `(device_id, seq)`, which is the primary
 *   key, so running twice inserts nothing the second time. A migration that
 *   half-ran and was retried must not double every number.
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
        // Continue the sequence rather than restarting it: (device_id, seq) is
        // the PK, and a restarted sequence would collide with anything already
        // there and be silently dropped by INSERT OR IGNORE.
        var nextSeq = (store.eventsAfter(deviceId, -1L).maxOfOrNull { it.seq } ?: -1L) + 1L

        for (file in files) {
            for (line in file.readLines()) {
                if (line.isBlank()) continue
                val event = runCatching { json.decodeFromString(FocusEvent.serializer(), line) }.getOrNull()
                if (event == null) {
                    skipped++
                    continue
                }
                // Rewrite the seq into the store's own space. The NDJSON's
                // seq was only ever unique within its own file set.
                store.insertEvent(event.copy(deviceId = deviceId, seq = nextSeq++))
                migrated++
            }
        }

        // Only now, with everything written, put the originals beyond reach of
        // a second migration — without destroying them.
        val archive = File(root, "migrated").also { it.mkdirs() }
        var moved = 0
        for (file in files) {
            if (file.renameTo(File(archive, file.name))) moved++
        }

        return Report(migratedEvents = migrated, skippedLines = skipped, filesMoved = moved)
    }

    private fun eventFiles(root: File): List<File> =
        (root.listFiles { f -> f.isFile && f.name.startsWith("events-") && f.name.endsWith(".ndjson") } ?: emptyArray())
            .sortedBy { it.name }
}
