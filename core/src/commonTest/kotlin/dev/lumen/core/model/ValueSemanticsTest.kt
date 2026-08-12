package dev.lumen.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Value-type and default-value contract (Agent B, M1 gate).
 *
 * `AppKey` and `DeviceId` are the join keys for every table in the schema.
 * If their equality, ordering or defaults drift, dedupe-by-(deviceId, seq)
 * and every PK in `docs/data-model.md` drift with them.
 */
class ValueSemanticsTest {

    @Test
    fun `AppKey equality is by value, not identity`() {
        assertEquals(AppKey("com.apple.Safari"), AppKey("com.apple.Safari"))
        assertEquals(AppKey("com.apple.Safari").hashCode(), AppKey("com.apple.Safari").hashCode())
        assertNotEquals(AppKey("com.apple.Safari"), AppKey("com.apple.safari"))
    }

    @Test
    fun `AppKey is case-sensitive and does not normalise`() {
        // Deliberate: Linux WM_CLASS and Android package names are both
        // case-sensitive identifiers. Silent normalisation would merge two
        // genuinely different apps into one row.
        val keys = setOf(AppKey("Firefox"), AppKey("firefox"))
        assertEquals(2, keys.size)
    }

    @Test
    fun `AppKey round-trips through a set and map as a key`() {
        val counts = mapOf(AppKey("a") to 1L, AppKey("b") to 2L)
        assertEquals(1L, counts[AppKey("a")])
        assertEquals(2L, counts[AppKey("b")])
        assertEquals(null, counts[AppKey("c")])
    }

    @Test
    fun `DeviceId equality is by value`() {
        val raw = "11111111-2222-3333-4444-555555555555"
        assertEquals(DeviceId(raw), DeviceId(raw))
        assertNotEquals(DeviceId(raw), DeviceId("99999999-2222-3333-4444-555555555555"))
    }

    @Test
    fun `DeviceId toString is the bare value, with no wrapper syntax`() {
        // It is written straight into device_id TEXT columns and into the
        // sync envelope; a "DeviceId(value=...)" rendering would corrupt both.
        val raw = "11111111-2222-3333-4444-555555555555"
        assertEquals(raw, DeviceId(raw).toString())
    }

    @Test
    fun `a defaulted DeviceId is a fresh random UUID each time`() {
        val generated = List(50) { DeviceId().value }
        assertEquals(50, generated.toSet().size, "generated DeviceIds must be unique")
        generated.forEach {
            assertTrue(
                Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""").matches(it),
                "DeviceId must be a lowercase UUID, was '$it'",
            )
            assertEquals('4', it[14], "docs/data-model.md specifies UUID v4")
        }
    }

    @Test
    fun `a fresh FocusEvent is local, untitled and uncategorised`() {
        // Defaults matter: a collector that forgets to set syncState must not
        // accidentally mark an event as already acked.
        val event = FocusEvent(
            seq = 1L,
            deviceId = DeviceId("11111111-2222-3333-4444-555555555555"),
            appKey = AppKey("com.apple.Safari"),
            startedAtMs = 1781518620000L,
            durationMs = 60_000L,
        )
        assertEquals(SyncState.LOCAL, event.syncState)
        assertEquals(null, event.titleHash, "titles are opt-in and never synced")
        assertEquals(null, event.category, "category is a lookup, not a capture-time default")
    }

    @Test
    fun `SyncState ordinals match the schema's sync_state column`() {
        // LumenDatabase.sq: "0 local, 1 acked, 2 conflict". The driver stores
        // the ordinal, so reordering this enum silently rewrites history.
        assertEquals(0, SyncState.LOCAL.ordinal)
        assertEquals(1, SyncState.ACKED.ordinal)
        assertEquals(2, SyncState.CONFLICT.ordinal)
        assertEquals(3, SyncState.entries.size)
    }

    @Test
    fun `RecordKind is a closed set of three wire kinds`() {
        // RecordKind travels on the wire. Adding a value is a wire change;
        // this test makes that visible rather than accidental.
        assertEquals(
            listOf("EVENT", "ROLLUP", "SETTING"),
            RecordKind.entries.map { it.name },
        )
    }

    @Test
    fun `a fresh ControlState is active, not released`() {
        val state = ControlState(
            controlKey = "focus_session",
            deviceId = DeviceId("11111111-2222-3333-4444-555555555555"),
            deviceSeq = 7L,
            startedAtMs = 1781518620000L,
            utcDay = "2026-06-15",
        )
        assertEquals(false, state.released, "a declaration is a claim until explicitly released")
    }
}
