package dev.lumen.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Serialization contract (Agent B, M1 gate).
 *
 * Agent A pinned the format in discussion #12: **kotlinx.serialization JSON,
 * versioned envelope, stable at M1**, with the rule that a reader MUST reject
 * an unknown version rather than best-effort parse. The M5 Argon2id export
 * writes these types to disk, so their JSON shape is a migration contract —
 * the one file whose whole job is surviving migrations cannot have a format
 * that drifts underneath it.
 *
 * These tests pin the wire names and the strictness. A renamed field or a
 * silently-tolerated unknown key fails here rather than in someone's restore.
 */
class SerializationContractTest {

    private val json = Json
    private val device = DeviceId("11111111-2222-3333-4444-555555555555")

    // ---- round-trips ----

    @Test
    fun `FocusEvent round-trips`() {
        val event = FocusEvent(
            seq = 42L,
            deviceId = device,
            appKey = AppKey("com.apple.Safari"),
            titleHash = "abc123",
            startedAtMs = 1781518620000L,
            durationMs = 90_000L,
            category = "Browsing",
            syncState = SyncState.ACKED,
        )
        assertEquals(event, json.decodeFromString<FocusEvent>(json.encodeToString(event)))
    }

    @Test
    fun `MinuteBucket round-trips`() {
        val bucket = MinuteBucket(device, 1781518620000L, AppKey("com.apple.Safari"), 60_000L)
        assertEquals(bucket, json.decodeFromString<MinuteBucket>(json.encodeToString(bucket)))
    }

    @Test
    fun `AppDayRollup round-trips`() {
        val rollup = AppDayRollup(device, "2026-06-15", AppKey("com.apple.Safari"), 7_200_000L, "Browsing")
        assertEquals(rollup, json.decodeFromString<AppDayRollup>(json.encodeToString(rollup)))
    }

    @Test
    fun `Setting round-trips field-wise`() {
        // Setting holds a ByteArray, so data-class equality is identity-based
        // and cannot be used here — compare the bytes explicitly.
        val setting = Setting(
            key = "nudge.break.enabled",
            value = byteArrayOf(1, 0, -128, 127),
            updatedAtMs = 1781518620000L,
            updatedDayUtc = "2026-06-15",
            deviceId = device,
        )
        val decoded = json.decodeFromString<Setting>(json.encodeToString(setting))
        assertEquals(setting.key, decoded.key)
        assertTrue(setting.value.contentEquals(decoded.value), "setting bytes must survive the round-trip")
        assertEquals(setting.updatedAtMs, decoded.updatedAtMs)
        assertEquals(setting.updatedDayUtc, decoded.updatedDayUtc)
        assertEquals(setting.deviceId, decoded.deviceId)
    }

    @Test
    fun `SyncRecord round-trips field-wise`() {
        val record = SyncRecord(device, 9L, RecordKind.EVENT, byteArrayOf(4, 8, 15, 16, 23, 42))
        val decoded = json.decodeFromString<SyncRecord>(json.encodeToString(record))
        assertEquals(record.deviceId, decoded.deviceId)
        assertEquals(record.seq, decoded.seq)
        assertEquals(record.kind, decoded.kind)
        assertTrue(record.payload.contentEquals(decoded.payload))
    }

    @Test
    fun `ControlState round-trips`() {
        val state = ControlState("focus_session", device, 7L, 1781518620000L, "2026-06-15", released = true)
        assertEquals(state, json.decodeFromString<ControlState>(json.encodeToString(state)))
    }

    // ---- wire shape ----

    @Test
    fun `value classes serialize as bare strings, not objects`() {
        // AppKey and DeviceId are @JvmInline value classes. If they ever
        // serialized as {"value":"..."} the export format would change shape
        // without anyone editing the export code.
        val bucket = MinuteBucket(device, 1781518620000L, AppKey("com.apple.Safari"), 60_000L)
        val encoded = json.encodeToString(bucket)
        assertTrue(
            encoded.contains(""""appKey":"com.apple.Safari""""),
            "AppKey must encode as a bare string, was: $encoded",
        )
        assertTrue(
            encoded.contains(""""deviceId":"11111111-2222-3333-4444-555555555555""""),
            "DeviceId must encode as a bare string, was: $encoded",
        )
    }

    @Test
    fun `enums serialize by name, not ordinal`() {
        // Ordinals would make a reordered enum silently reinterpret history.
        val record = SyncRecord(device, 1L, RecordKind.SETTING, byteArrayOf())
        assertTrue(json.encodeToString(record).contains(""""kind":"SETTING""""))
    }

    @Test
    fun `field names are the wire contract`() {
        val rollup = AppDayRollup(device, "2026-06-15", AppKey("x"), 1L, null)
        val encoded = json.encodeToString(rollup)
        listOf("deviceId", "dayUtc", "appKey", "totalMs").forEach {
            assertTrue(encoded.contains(""""$it":"""), "expected field '$it' in $encoded")
        }
    }

    // ---- strictness ----

    @Test
    fun `an unknown field is rejected, not silently ignored`() {
        // This is the strictness the versioned export envelope depends on: a
        // v1 reader meeting a v2 file must fail loudly. If the default parser
        // tolerated unknown keys, a v2 export would half-load into a v1
        // reader and look like success.
        val withExtra = """
            {"deviceId":"11111111-2222-3333-4444-555555555555","dayUtc":"2026-06-15",
             "appKey":"x","totalMs":1,"unexpectedFutureField":true}
        """.trimIndent()
        assertFailsWith<SerializationException> { json.decodeFromString<AppDayRollup>(withExtra) }
    }

    @Test
    fun `a missing required field is rejected`() {
        val missingTotal = """
            {"deviceId":"11111111-2222-3333-4444-555555555555","dayUtc":"2026-06-15","appKey":"x"}
        """.trimIndent()
        assertFailsWith<SerializationException> { json.decodeFromString<AppDayRollup>(missingTotal) }
    }

    @Test
    fun `optional fields may be omitted and take their documented defaults`() {
        val minimal = """
            {"seq":1,"deviceId":"11111111-2222-3333-4444-555555555555",
             "appKey":"x","startedAtMs":0,"durationMs":1}
        """.trimIndent()
        val decoded = json.decodeFromString<FocusEvent>(minimal)
        assertEquals(null, decoded.titleHash)
        assertEquals(null, decoded.category)
        assertEquals(SyncState.LOCAL, decoded.syncState)
    }
}
