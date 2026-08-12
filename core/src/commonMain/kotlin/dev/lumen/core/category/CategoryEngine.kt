package dev.lumen.core.category

import dev.lumen.core.model.AppKey

/**
 * Resolves an app to a category — M6, gate G5.
 *
 * Precedence, highest first:
 *
 *  1. **the user's manual override** — sticky, and never reclassified by a
 *     later registry update. `docs/plan.md`: "sticky overrides". If someone
 *     has told Lumen that Slack is Development for them, a shipped dataset
 *     saying Communication does not get to argue.
 *  2. **the shipped registry** — the top-500 human-reviewed list.
 *  3. **[Category.Uncategorized]** — never a guess.
 *
 * There is deliberately no fourth step. No keyword matching on the app name,
 * no inference from the bundle id's TLD, no ML: `docs/plan.md` cuts "on-device
 * ML/LLM" and locks "never a confident wrong guess". Every one of those
 * heuristics is right most of the time, and being wrong about someone's own
 * behaviour is worse than admitting you do not know.
 */
class CategoryEngine(
    private val registry: CategoryRegistry,
    private val overrides: OverrideStore,
) {

    fun categoryOf(appKey: AppKey): Category =
        overrides.override(appKey)
            ?: registry.lookup(appKey)
            ?: Category.Uncategorized

    /**
     * Why an app is in the category it is in.
     *
     * `docs/design-spec.md` requires a "why is this here" affordance on every
     * category. A user who cannot find out why their editor counts as
     * Communication cannot correct it, and will distrust the whole screen.
     */
    fun explain(appKey: AppKey): CategorySource = when {
        overrides.override(appKey) != null -> CategorySource.UserOverride
        registry.lookup(appKey) != null -> CategorySource.Registry
        else -> CategorySource.Unknown
    }

    /** Group per-app totals into per-category totals, largest first. */
    fun summarize(totals: Map<AppKey, Long>): List<CategoryTotal> =
        totals.entries
            .groupBy { categoryOf(it.key) }
            .map { (category, entries) -> CategoryTotal(category, entries.sumOf { it.value }) }
            .sortedByDescending { it.totalMs }
}

/** Where an app's category came from. Drives the "why is this here" answer. */
enum class CategorySource {
    UserOverride,
    Registry,
    Unknown,
    ;

    /** Plain-language explanation, per docs/design-spec.md. */
    fun explanation(category: Category): String = when (this) {
        UserOverride -> "You set this app to ${category.displayName}."
        Registry -> "Lumen's app list has this as ${category.displayName}."
        Unknown -> "Lumen doesn't recognise this app, so it isn't categorised. " +
            "You can set a category yourself."
    }
}

data class CategoryTotal(val category: Category, val totalMs: Long)

/** The shipped dataset. */
fun interface CategoryRegistry {
    fun lookup(appKey: AppKey): Category?
}

/** The user's own choices. Sticky: a registry update never overwrites one. */
interface OverrideStore {
    fun override(appKey: AppKey): Category?
    fun setOverride(appKey: AppKey, category: Category)
    fun clearOverride(appKey: AppKey)
}
