package dev.lumen.core.model

import kotlinx.serialization.Serializable

/**
 * A single app's contribution to a recap period.
 *
 * [percentage] is `totalMs / periodTotalMs * 100`, rounded to two decimal
 * places. When the period total is zero, percentage is 0.0.
 */
@Serializable
data class RecapAppBreakdown(
    val appKey: AppKey,
    val totalMs: Long,
    val percentage: Double,
)

/**
 * One recap period (week, month, or year).
 *
 * [startMs]/[endMs] are the UTC epoch boundaries of the period.
 * [totalMs] is the sum of all apps' active time in the period.
 * [targetMs] is the user's screen-time target for the period, if set.
 * When the target is null, the UI omits the progress ring.
 */
@Serializable
data class RecapPeriod(
    val startMs: Long,
    val endMs: Long,
    val totalMs: Long,
    val targetMs: Long?,
    val appBreakdown: List<RecapAppBreakdown>,
)

/**
 * The full recap — weekly, monthly, and yearly views.
 *
 * Any period may be null when the store has no data in that range.
 */
@Serializable
data class RecapSummary(
    val week: RecapPeriod?,
    val month: RecapPeriod?,
    val year: RecapPeriod?,
)
