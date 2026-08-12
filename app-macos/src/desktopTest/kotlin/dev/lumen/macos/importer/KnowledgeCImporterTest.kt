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
                        st.setString(1, "/app/inFocus")
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
    fun `ignores streams other than app inFocus`() {
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
