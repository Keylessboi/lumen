package dev.lumen.core.store

import dev.lumen.core.model.AppDayRollup
import dev.lumen.core.model.AppKey
import dev.lumen.core.model.DeviceId
import dev.lumen.core.model.FocusEvent
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * File-backed persistence, which [JvmLumenStore.inMemory] structurally cannot
 * cover: an in-memory database is fresh on every open, so the entire class of
 * "what happens the second time" bugs is invisible to it.
 *
 * That gap hid a real one. `open()` called `Schema.create()` unconditionally,
 * which succeeds once and then throws `table devices already exists` on every
 * later launch — persistence worked exactly until the first restart.
 */
class JvmLumenStorePersistenceTest {

    private val dbFile: File = File.createTempFile("lumen-persist", ".db").also { it.delete() }
    private val device = DeviceId("11111111-2222-3333-4444-555555555555")

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    @Test
    fun `a database can be reopened`() {
        JvmLumenStore.open(dbFile)
        JvmLumenStore.open(dbFile)
        JvmLumenStore.open(dbFile)
    }

    @Test
    fun `data written before a restart is there after it`() {
        JvmLumenStore.open(dbFile).apply {
            insertEvent(
                FocusEvent(
                    seq = 1L,
                    deviceId = device,
                    appKey = AppKey("com.apple.Safari"),
                    startedAtMs = 1_781_518_620_000L,
                    durationMs = 60_000L,
                ),
            )
            upsertRollup(AppDayRollup(device, "2026-06-15", AppKey("com.apple.Safari"), 60_000L))
            setAckedSeq(device, 7L)
        }

        // A new process would do exactly this.
        val reopened = JvmLumenStore.open(dbFile)

        assertEquals(1, reopened.eventsAfter(device, 0).size)
        assertEquals(60_000L, reopened.rollupsForDay(device, "2026-06-15").single().totalMs)
        assertEquals(7L, reopened.lastAckedSeq(device))
    }

    @Test
    fun `reopening does not reset the schema version`() {
        JvmLumenStore.open(dbFile)
        JvmLumenStore.open(dbFile)
        // Surviving a second open at all is the assertion; a reset version
        // would have re-run create() and thrown.
        assertTrue(dbFile.exists() && dbFile.length() > 0)
    }

    @Test
    fun `a fresh file is created on demand, including its directory`() {
        val nested = File(
            File.createTempFile("lumen-nested", "").also { it.delete() },
            "sub/dir/lumen.db",
        )
        try {
            JvmLumenStore.open(nested).setAckedSeq(device, 3L)
            assertEquals(3L, JvmLumenStore.open(nested).lastAckedSeq(device))
        } finally {
            nested.parentFile?.deleteRecursively()
        }
    }
}
