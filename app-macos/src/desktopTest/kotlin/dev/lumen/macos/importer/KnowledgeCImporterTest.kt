package dev.lumen.macos.importer

import dev.lumen.core.model.DeviceId
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exercises the importer against a synthetic Knowledge store built to the
 * schema this importer expects.
 *
 * That is the only honest way to test this without Full Disk Access, and it
 * pins the two things most likely to be silently wrong: the Core Data epoch
 * conversion, and the behaviour when the schema is not what we assumed.
 */
class KnowledgeCImporterTest {

    private val tmp: File = Files.createTempDirectory("lumen-knowledge-test").toFile()
    private val device = DeviceId("test-device")

    /** 2026-03-05T12:00:00Z in epoch millis. */
    private val noonMs = 1_772_884_800_000L

    /** Core Data reference date: 2001-01-01T00:00:00Z. */
    private val cocoaOffset = 978_307_200L

    private fun toCocoa(epochMs: Long): Double = (epochMs / 1000.0) - cocoaOffset

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun buildStore(
        name: String = "knowledgeC.db",
        withZObject: Boolean = true,
        rows: List<Triple<String, Long, Long>> = emptyList(),
        /**
         * Current macOS records app time under `/app/usage`; older systems
         * used `/app/inFocus`. Both are supported, so both are tested.
         */
        stream: String = "/app/usage",
    ): File {
        val db = File(tmp, name)
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            if (withZObject) {
                c.createStatement().use {
                    it.execute(
                        """
                        CREATE TABLE ZOBJECT (
                          Z_PK INTEGER PRIMARY KEY,
                          ZSTREAMNAME TEXT,
                          ZVALUESTRING TEXT,
                          ZSTARTDATE REAL,
                          ZENDDATE REAL
                        )
                        """.trimIndent(),
                    )
                }
                c.prepareStatement(
                    "INSERT INTO ZOBJECT (ZSTREAMNAME, ZVALUESTRING, ZSTARTDATE, ZENDDATE) VALUES (?,?,?,?)",
                ).use { st ->
                    rows.forEach { (bundle, startMs, endMs) ->
                        st.setString(1, stream)
                        st.setString(2, bundle)
                        st.setDouble(3, toCocoa(startMs))
                        st.setDouble(4, toCocoa(endMs))
                        st.addBatch()
                    }
                    // A non-app row that must be ignored.
                    st.setString(1, "/display/isBacklit")
                    st.setString(2, "backlight")
                    st.setDouble(3, toCocoa(noonMs))
                    st.setDouble(4, toCocoa(noonMs + 60_000))
                    st.addBatch()
                    st.executeBatch()
                }
            } else {
                c.createStatement().use { it.execute("CREATE TABLE SOMETHING_ELSE (x INTEGER)") }
            }
        }
        return db
    }

    private fun import(db: File, sinceMs: Long = 0L) =
        KnowledgeCImporter(device, db).import(sinceMs)

    /**
     * The imported history follows the same inclusion rule as the live stream
     * (`docs/design-spec.md`, LO's decision in `248446f`). Apple's Screen Time
     * store has been recording Lumen's own frontmost time all along; dropping
     * those rows on import would make imported history disagree with live
     * tracking, which is the one inconsistency a screen-time app cannot have.
     */
    @Test
    fun `imported history includes lumen's own rows`() {
        val db = buildStore(
            rows = listOf(
                Triple("com.apple.Safari", noonMs, noonMs + 300_000),
                Triple("dev.lumen.macos", noonMs + 300_000, noonMs + 900_000),
            ),
        )

        val imported = assertIs<KnowledgeCImporter.Result.Imported>(import(db))
        assertEquals(
            listOf("com.apple.Safari", "dev.lumen.macos"),
            imported.events.map { it.appKey.value },
        )
    }

    // ---- stream resolution (the bug LO hit: import silently returned nothing)
    //
    // macOS renamed the foreground-time stream. On macOS 26 `/app/inFocus`
    // does not exist at all and app time lives in `/app/usage`. The importer
    // had the old name inlined in its query, so it returned Imported(empty)
    // and the UI said "No new history to import" — indistinguishable from
    // "you have no history", on a Mac with weeks of it.

    @Test
    fun `imports from the current macOS stream, app usage`() {
        val db = buildStore(
            stream = "/app/usage",
            rows = listOf(Triple("com.apple.Safari", noonMs, noonMs + 300_000)),
        )
        val imported = assertIs<KnowledgeCImporter.Result.Imported>(import(db))
        assertEquals(listOf("com.apple.Safari"), imported.events.map { it.appKey.value })
    }

    @Test
    fun `imports from the legacy stream, app inFocus`() {
        val db = buildStore(
            stream = "/app/inFocus",
            rows = listOf(Triple("com.apple.Safari", noonMs, noonMs + 300_000)),
        )
        val imported = assertIs<KnowledgeCImporter.Result.Imported>(import(db))
        assertEquals(listOf("com.apple.Safari"), imported.events.map { it.appKey.value })
    }

    /**
     * The regression guard. A well-formed ZOBJECT carrying only streams we do
     * not know about must report [KnowledgeCImporter.Result.SchemaUnrecognised],
     * NOT an empty successful import.
     *
     * The old schema check verified the table's *columns*, which are identical
     * across macOS versions, so a renamed stream passed the guard and produced
     * silent zeros — exactly what this class's header promises it will not do.
     */
    @Test
    fun `an unknown stream name is an unrecognised schema, not an empty import`() {
        val db = buildStore(
            stream = "/app/somethingAppleRenamedItTo",
            rows = listOf(Triple("com.apple.Safari", noonMs, noonMs + 300_000)),
        )
        assertIs<KnowledgeCImporter.Result.SchemaUnrecognised>(import(db))
    }

    // ---- the double-count guard ----
    //
    // Apple records the same apps Lumen records. Any period Lumen tracked
    // itself exists twice — once live, once in the Knowledge store — so the
    // import must stop where Lumen's own coverage begins.

    @Test
    fun `untilMs excludes sessions Lumen already tracked itself`() {
        val db = buildStore(
            rows = listOf(
                Triple("com.before.lumen", noonMs, noonMs + 60_000),
                Triple("com.during.lumen", noonMs + 300_000, noonMs + 360_000),
            ),
        )
        val imported = assertIs<KnowledgeCImporter.Result.Imported>(
            KnowledgeCImporter(device, db).import(sinceMs = 0L, untilMs = noonMs + 300_000),
        )
        assertEquals(
            listOf("com.before.lumen"),
            imported.events.map { it.appKey.value },
            "the boundary is exclusive — a session starting exactly at it is Lumen's own",
        )
    }

    @Test
    fun `without untilMs everything from sinceMs is imported`() {
        val db = buildStore(
            rows = listOf(
                Triple("a", noonMs, noonMs + 60_000),
                Triple("b", noonMs + 300_000, noonMs + 360_000),
            ),
        )
        val imported = assertIs<KnowledgeCImporter.Result.Imported>(import(db))
        assertEquals(listOf("a", "b"), imported.events.map { it.appKey.value })
    }

    @Test
    fun `startSeq continues an existing sequence rather than restarting it`() {
        // Harmless in the NDJSON cache, fatal once app-macos moves to
        // LumenStore: (device_id, seq) is the PK there and INSERT OR IGNORE
        // would silently drop every colliding imported row.
        val db = buildStore(
            rows = listOf(
                Triple("a", noonMs, noonMs + 1000),
                Triple("b", noonMs + 1000, noonMs + 2000),
            ),
        )
        val imported = assertIs<KnowledgeCImporter.Result.Imported>(
            KnowledgeCImporter(device, db).import(sinceMs = 0L, startSeq = 500L),
        )
        assertEquals(listOf(500L, 501L), imported.events.map { it.seq })
    }

    @Test
    fun `maps focus rows to events with correct epoch conversion`() {
        val db = buildStore(
            rows = listOf(
                Triple("com.apple.Safari", noonMs, noonMs + 300_000),
                Triple("com.apple.Terminal", noonMs + 300_000, noonMs + 420_000),
            ),
        )

        val result = import(db)
        val imported = assertIs<KnowledgeCImporter.Result.Imported>(result)
        assertEquals(2, imported.events.size)

        val safari = imported.events.first()
        assertEquals("com.apple.Safari", safari.appKey.value)
        // The epoch conversion is the thing most likely to be silently wrong:
        // a missed offset lands the event in 1993 or 2049.
        assertEquals(noonMs, safari.startedAtMs)
        assertEquals(300_000, safari.durationMs)
    }

    @Test
    fun `ignores streams other than the foreground-time stream`() {
        val db = buildStore(rows = listOf(Triple("com.apple.Safari", noonMs, noonMs + 60_000)))
        val imported = assertIs<KnowledgeCImporter.Result.Imported>(import(db))
        assertEquals(listOf("com.apple.Safari"), imported.events.map { it.appKey.value })
    }

    @Test
    fun `respects the since cutoff`() {
        val db = buildStore(
            rows = listOf(
                Triple("com.old.app", noonMs - 86_400_000, noonMs - 86_400_000 + 60_000),
                Triple("com.new.app", noonMs, noonMs + 60_000),
            ),
        )
        val imported = assertIs<KnowledgeCImporter.Result.Imported>(import(db, sinceMs = noonMs - 1000))
        assertEquals(listOf("com.new.app"), imported.events.map { it.appKey.value })
    }

    /** Zero and negative spans show up around sleep/wake; they are not usage. */
    @Test
    fun `drops zero and negative duration rows`() {
        val db = buildStore(
            rows = listOf(
                Triple("com.zero.app", noonMs, noonMs),
                Triple("com.negative.app", noonMs + 60_000, noonMs),
                Triple("com.real.app", noonMs, noonMs + 60_000),
            ),
        )
        val imported = assertIs<KnowledgeCImporter.Result.Imported>(import(db))
        assertEquals(listOf("com.real.app"), imported.events.map { it.appKey.value })
    }

    @Test
    fun `assigns increasing seq values`() {
        val db = buildStore(
            rows = listOf(
                Triple("a", noonMs, noonMs + 1000),
                Triple("b", noonMs + 1000, noonMs + 2000),
                Triple("c", noonMs + 2000, noonMs + 3000),
            ),
        )
        val imported = assertIs<KnowledgeCImporter.Result.Imported>(import(db))
        assertEquals(listOf(0L, 1L, 2L), imported.events.map { it.seq })
    }

    /**
     * The store is private and undocumented; Apple moves things between
     * releases. An unrecognised schema must be reported, because importing
     * nothing looks exactly like "you used no apps".
     */
    @Test
    fun `reports an unrecognised schema rather than importing nothing`() {
        val db = buildStore(withZObject = false)
        assertIs<KnowledgeCImporter.Result.SchemaUnrecognised>(import(db))
    }

    @Test
    fun `reports unavailable when there is no store`() {
        assertIs<KnowledgeCImporter.Result.Unavailable>(
            import(File(tmp, "does-not-exist.db")),
        )
    }

    /** The live system store must never be opened directly. */
    @Test
    fun `leaves the source database untouched`() {
        val db = buildStore(rows = listOf(Triple("com.apple.Safari", noonMs, noonMs + 60_000)))
        val before = db.readBytes()
        import(db)
        assertTrue(before.contentEquals(db.readBytes()), "importer modified the source store")
    }
}
