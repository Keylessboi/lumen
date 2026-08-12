package dev.lumen.macos.importer

import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import dev.lumen.macos.permissions.FullDiskAccess
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/**
 * Imports historical app-focus events from the macOS Knowledge store — the
 * same system database that backs Screen Time.
 *
 * This is the macOS answer to Android's `UsageStatsManager`: authoritative,
 * retained for weeks, and available for periods when Lumen was not running.
 * It is what makes `AppUsageCollector.canBackfill` true on this platform.
 *
 * ## Safety rules this class follows
 *
 * - **Never touch the live database.** `knowledgeC.db` is a running system
 *   store in WAL mode. It is copied — with its `-wal` and `-shm` sidecars, or
 *   the copy is missing recent writes — to a temp file, and the copy is read.
 *   Opening the original directly risks lock contention with a system daemon.
 * - **Read-only, always.** The JDBC URL sets `open_mode=1`. Lumen has no
 *   business writing to an Apple-owned store.
 * - **Verify the schema before trusting it.** `knowledgeC` is private and
 *   undocumented; Apple changes it between releases. Every query is guarded by
 *   an introspection check, and a schema we do not recognise produces a clear
 *   [Result.SchemaUnrecognised] rather than a crash or, worse, silent zeros.
 *
 * ## What it reads
 *
 * Rows in `ZOBJECT` whose `ZSTREAMNAME` is the foreground-time stream: one row
 * per foreground period, with the app's bundle id in `ZVALUESTRING` and Core
 * Data timestamps in `ZSTARTDATE` / `ZENDDATE`.
 *
 * The stream is **not** a fixed name — macOS renamed it, and current systems
 * use `/app/usage` where older ones used `/app/inFocus`. It is resolved
 * against the actual store at import time; see [focusStream].
 *
 * Nothing else is read. The Knowledge store also contains web usage, device
 * activity and more; Lumen imports app focus and ignores the rest.
 */
class KnowledgeCImporter(
    private val deviceId: DeviceId,
    private val source: File = FullDiskAccess.knowledgeDb,
) {

    /** Core Data reference date: 2001-01-01T00:00:00Z, in epoch seconds. */
    private val cocoaEpochOffsetSeconds = 978_307_200L

    /**
     * Import foreground sessions that started in `[sinceMs, untilMs)`.
     *
     * [untilMs] exists to stop the import double-counting. Apple has been
     * recording the same apps Lumen records, so any period Lumen already
     * tracked itself exists twice — once from the live collector, once in the
     * Knowledge store — and appending both inflates every number for that
     * period. The caller passes the start of Lumen's own coverage; everything
     * before it is history Lumen genuinely missed, everything after it is a
     * duplicate.
     *
     * [startSeq] must continue the store's existing sequence. Imported events
     * sharing a seq range with live ones is harmless in the NDJSON cache,
     * which never reads seq — but the moment `app-macos` moves to
     * `LumenStore`, `(device_id, seq)` is the primary key and `INSERT OR
     * IGNORE` would silently drop every colliding row.
     */
    fun import(sinceMs: Long, untilMs: Long = Long.MAX_VALUE, startSeq: Long = 0L): Result {
        // Probe the actual source rather than a global permission flag: the
        // file either opens or it doesn't, and that answer is the truth on any
        // Mac regardless of how TCC was configured.
        if (!source.exists()) return Result.Unavailable
        if (!canRead(source)) return Result.PermissionDenied

        val copy = try {
            copyForReading()
        } catch (e: Exception) {
            return Result.Failed("could not copy the Knowledge store: ${e.message}")
        }

        return try {
            DriverManager.getConnection("jdbc:sqlite:${copy.absolutePath}?open_mode=1").use { conn ->
                if (!hasExpectedSchema(conn)) return Result.SchemaUnrecognised

                // Which stream carries app foreground time varies by macOS
                // version, so resolve it against the actual store instead of
                // assuming. A store with none of them is an unrecognised
                // schema, NOT an empty import — see [focusStream].
                val stream = focusStream(conn) ?: return Result.SchemaUnrecognised

                val cutoffCocoa = (sinceMs / 1000.0) - cocoaEpochOffsetSeconds
                val untilCocoa = if (untilMs == Long.MAX_VALUE) {
                    Double.MAX_VALUE
                } else {
                    (untilMs / 1000.0) - cocoaEpochOffsetSeconds
                }
                val events = mutableListOf<FocusEvent>()
                var seq = startSeq

                conn.prepareStatement(
                    """
                    SELECT ZVALUESTRING, ZSTARTDATE, ZENDDATE
                    FROM ZOBJECT
                    WHERE ZSTREAMNAME = ?
                      AND ZSTARTDATE IS NOT NULL
                      AND ZENDDATE IS NOT NULL
                      AND ZVALUESTRING IS NOT NULL
                      AND ZSTARTDATE >= ?
                      AND ZSTARTDATE < ?
                    ORDER BY ZSTARTDATE ASC
                    """.trimIndent(),
                ).use { st ->
                    st.setString(1, stream)
                    st.setDouble(2, cutoffCocoa)
                    st.setDouble(3, untilCocoa)
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            val bundle = rs.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                            val startMs = toEpochMs(rs.getDouble(2))
                            val endMs = toEpochMs(rs.getDouble(3))
                            val duration = endMs - startMs
                            // Zero and negative spans appear in the store around
                            // sleep/wake. They are not usage; drop them rather
                            // than recording a bogus session.
                            if (duration <= 0) continue
                            events += FocusEvent(
                                seq = seq++,
                                deviceId = deviceId,
                                appKey = AppKey(bundle),
                                startedAtMs = startMs,
                                durationMs = duration,
                            )
                        }
                    }
                }
                Result.Imported(events)
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: e::class.simpleName ?: "unknown error")
        } finally {
            copy.delete()
            File(copy.absolutePath + "-wal").delete()
            File(copy.absolutePath + "-shm").delete()
        }
    }

    private fun toEpochMs(cocoaSeconds: Double): Long =
        ((cocoaSeconds + cocoaEpochOffsetSeconds) * 1000).toLong()

    private fun canRead(f: File): Boolean = try {
        f.inputStream().use { it.read() }
        true
    } catch (_: Exception) {
        false
    }

    private fun copyForReading(): File {
        val dir = Files.createTempDirectory("lumen-knowledge").toFile()
        val dest = File(dir, "knowledgeC.db")
        Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        // Without the WAL sidecar the copy silently omits the most recent
        // writes — which is exactly the period a user checking "did it pick up
        // today?" will look at first.
        for (suffix in listOf("-wal", "-shm")) {
            val side = File(source.absolutePath + suffix)
            if (side.exists()) {
                runCatching {
                    Files.copy(
                        side.toPath(),
                        File(dest.absolutePath + suffix).toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
        }
        return dest
    }

    /**
     * The `ZSTREAMNAME` carrying per-app foreground time, or null if this
     * store has none of the ones we know about.
     *
     * macOS moved this. On the machine this was found on (macOS 26), app time
     * lives in **`/app/usage`** and `/app/inFocus` does not exist at all —
     * `SELECT COUNT(*) ... WHERE ZSTREAMNAME='/app/inFocus'` returns 0 while
     * `/app/usage` holds 1675 rows of exactly the same shape. Older systems
     * (and iOS-derived stores) use `/app/inFocus`, so both are probed, most
     * recent naming first.
     *
     * Only ONE stream is ever used. If a future macOS ships both, querying
     * them together would double-count every session.
     *
     * Returning null here is what makes a naming change loud. Previously the
     * stream name was inlined in the query and the schema check only verified
     * that `ZOBJECT` had the right *columns* — which it does on every macOS —
     * so an unknown stream sailed past the guard and produced
     * `Imported(emptyList())`. The user saw "No new history to import", which
     * is indistinguishable from "you have no history". That is precisely the
     * silent-zeros failure this class's own header promises not to have.
     */
    private fun focusStream(conn: java.sql.Connection): String? =
        KNOWN_FOCUS_STREAMS.firstOrNull { stream ->
            conn.prepareStatement(
                "SELECT 1 FROM ZOBJECT WHERE ZSTREAMNAME = ? LIMIT 1",
            ).use { st ->
                st.setString(1, stream)
                st.executeQuery().use { it.next() }
            }
        }

    /** Confirms ZOBJECT exists and carries the columns this import depends on. */
    private fun hasExpectedSchema(conn: java.sql.Connection): Boolean {
        return try {
            val hasTable = conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='ZOBJECT'",
                ).use { it.next() }
            }
            if (!hasTable) {
                false
            } else {
                val columns = mutableSetOf<String>()
                conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA table_info(ZOBJECT)").use { rs ->
                        while (rs.next()) columns += rs.getString("name").uppercase()
                    }
                }
                columns.containsAll(listOf("ZSTREAMNAME", "ZVALUESTRING", "ZSTARTDATE", "ZENDDATE"))
            }
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        /**
         * Foreground-time streams, most recent macOS naming first. See
         * [focusStream] for why this is a list and why only one is used.
         */
        val KNOWN_FOCUS_STREAMS = listOf("/app/usage", "/app/inFocus")
    }

    sealed interface Result {
        data class Imported(val events: List<FocusEvent>) : Result

        /** Full Disk Access not granted. The user can fix this. */
        data object PermissionDenied : Result

        /** No Knowledge store on this Mac. Not a failure. */
        data object Unavailable : Result

        /**
         * The store exists and is readable, but does not have the shape this
         * importer knows. Most likely a macOS version that moved things.
         * Reported rather than swallowed — silently importing nothing would
         * look identical to "you used no apps".
         */
        data object SchemaUnrecognised : Result

        data class Failed(val reason: String) : Result
    }
}
