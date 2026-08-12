package dev.lumen.core.category

/**
 * The categories an app can fall into — M6, `docs/plan.md` locked constraint.
 *
 * A closed set, not free text. The palette is eight colourblind-safe hues
 * (`docs/design-spec.md`), the Today screen groups by category, and a
 * user-invented category would have no colour and no place in the donut.
 *
 * ## Uncategorized is a real member, not an absence
 *
 * The locked rule is "never a confident wrong guess", and the honest
 * expression of that is a neutral bucket the user can see. An app with no
 * entry in the registry is Uncategorized — visibly, with its time counted —
 * rather than hidden or guessed at. `docs/design-spec.md` calls for exactly
 * that: "Unknown apps land in a neutral 'Uncategorized' bucket, never a
 * confident wrong guess."
 *
 * These names are user-visible, so they are plain words rather than jargon,
 * and none of them is a judgement. There is no "Productive" and no
 * "Distracting": the app is a mirror, and deciding that Reading is better
 * than Games is the user's business.
 */
enum class Category(val displayName: String) {
    Communication("Communication"),
    Development("Development"),
    Reading("Reading"),
    Writing("Writing"),
    Browsing("Browsing"),
    Media("Media"),
    Games("Games"),
    Utilities("Utilities"),
    Uncategorized("Uncategorized"),
    ;

    companion object {
        /**
         * Parse a stored category name, falling back to [Uncategorized].
         *
         * Stored rows carry the name as TEXT, and a registry update could
         * remove a category that history still references. Falling back keeps
         * old rows readable instead of failing to render a day from last year.
         */
        fun fromStored(value: String?): Category =
            entries.firstOrNull { it.name == value || it.displayName == value } ?: Uncategorized
    }
}
