package dev.lumen.core.category

import dev.lumen.core.model.AppKey
import dev.lumen.core.model.AppTotal

/**
 * Turns per-app totals into what the Today screen needs.
 *
 * Every platform was about to do this the same way: attach a category to each
 * row so the app list can be coloured by it, and group the same numbers into
 * the summary strip. macOS did it first; Linux and Android would each have
 * written their own version, and the three would have drifted — which is the
 * mistake that produced the window-title leak, the axis labels, and two
 * missing dedupes tonight.
 *
 * So it lives here once. A platform supplies totals and gets back both views,
 * guaranteed consistent with each other: the strip is always exactly the app
 * list grouped, so the two can never disagree about a day.
 */
class DayView(private val engine: CategoryEngine) {

    /** App rows with their category attached, largest first. */
    fun rows(totals: List<AppTotal>): List<AppTotal> =
        totals
            .map { it.copy(category = engine.categoryOf(it.appKey).displayName) }
            .sortedByDescending { it.totalMs }

    /**
     * The same totals grouped by category, largest first.
     *
     * Derived from the identical input as [rows] rather than from a separate
     * query, so the strip and the list are guaranteed to add up to the same
     * number. A summary that disagrees with the detail below it is the
     * "numbers and charts must agree" failure in `docs/design-spec.md`.
     */
    fun categories(totals: List<AppTotal>): List<CategoryTotal> =
        engine.summarize(totals.associate { it.appKey to it.totalMs })

    /** Category display names, for a UI that only needs the labels. */
    fun categoryNames(totals: List<AppTotal>): List<Pair<String, Long>> =
        categories(totals).map { it.category.displayName to it.totalMs }
}

/**
 * A [CategoryEngine] backed by the shipped registry and an in-memory
 * override store.
 *
 * For a platform whose persistence is not wired up yet: the registry half is
 * real, so apps categorise correctly, and overrides work for the session
 * without pretending to be durable. Better than no categories at all, and
 * honest about what it is — nothing here claims a user's choice will survive
 * a restart.
 */
fun sessionCategoryEngine(): CategoryEngine {
    val overrides = mutableMapOf<AppKey, Category>()
    return CategoryEngine(
        registry = GeneratedRegistry,
        overrides = object : OverrideStore {
            override fun override(appKey: AppKey): Category? = overrides[appKey]
            override fun setOverride(appKey: AppKey, category: Category) {
                overrides[appKey] = category
            }
            override fun clearOverride(appKey: AppKey) {
                overrides.remove(appKey)
            }
        },
    )
}
