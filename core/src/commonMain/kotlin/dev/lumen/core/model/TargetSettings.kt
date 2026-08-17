package dev.lumen.core.model

import dev.lumen.core.clock.UtcDay
import dev.lumen.core.store.LumenStore

const val TARGET_SCREENTIME_KEY = "target.screentime.ms"

val DEFAULT_TARGET_MS = 4 * 60 * 60 * 1000L // 4 hours

fun LumenStore.targetScreentime(deviceId: DeviceId): Long {
    val setting = setting(TARGET_SCREENTIME_KEY) ?: return DEFAULT_TARGET_MS
    return setting.value.decodeToString().toLongOrNull() ?: DEFAULT_TARGET_MS
}

fun LumenStore.setTargetScreentime(deviceId: DeviceId, ms: Long) {
    val now = System.currentTimeMillis()
    val setting = Setting(
        key = TARGET_SCREENTIME_KEY,
        value = ms.toString().encodeToByteArray(),
        updatedAtMs = now,
        updatedDayUtc = UtcDay.dayOf(now),
        deviceId = deviceId,
    )
    upsertSetting(setting)
}
