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
 * Rows in `ZOBJECT` whose `ZSTREAMNAME` is `/app/inFocus`: one row per
 * foreground period, with the app's bundle id in `ZVALUESTRING` and Core Data
 * timestamps in `ZSTARTDATE` / `ZENDDATE`.
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

    fun import(sinceMs: Long, startSeq: Long = 0L): Result {
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

                val cutoffCocoa = (sinceMs / 1000.0) - cocoaEpochOffsetSeconds
                val events = mutableListOf<FocusEvent>()
                var seq = startSeq

                conn.prepareStatement(
                    """
                    SELECT ZVALUESTRING, ZSTARTDATE, ZENDDATE
                    FROM ZOBJECT
                    WHERE ZSTREAMNAME = '/app/inFocus'
                      AND ZSTARTDATE IS NOT NULL
                      AND ZENDDATE IS NOT NULL
                      AND ZVALUESTRING IS NOT NULL
                      AND ZSTARTDATE >= ?
                    ORDER BY ZSTARTDATE ASC
                    """.trimIndent(),
                ).use { st ->
                    st.setDouble(1, cutoffCocoa)
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
