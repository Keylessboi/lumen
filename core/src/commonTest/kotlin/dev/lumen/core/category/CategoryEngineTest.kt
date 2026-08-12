package dev.lumen.core.category

import dev.lumen.core.model.AppKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate G5: "corpus test, unknown→Uncategorized, overrides sticky".
 */
class CategoryEngineTest {

    private class FakeOverrides(
        private val map: MutableMap<AppKey, Category> = mutableMapOf(),
    ) : OverrideStore {
        override fun override(appKey: AppKey) = map[appKey]
        override fun setOverride(appKey: AppKey, category: Category) { map[appKey] = category }
        override fun clearOverride(appKey: AppKey) { map.remove(appKey) }
    }

    private fun engine(overrides: OverrideStore = FakeOverrides()) =
        CategoryEngine(GeneratedRegistry, overrides)

    // ---- the corpus ----

    @Test
    fun `popular apps are categorised correctly on every platform`() {
        // "popular ~100% correct" from the v1 acceptance criteria. The same
        // app has three different identities and all three must resolve —
        // this is the check that a macOS-only registry would fail.
        val expected = mapOf(
            "com.apple.Safari" to Category.Browsing,
            "org.mozilla.firefox" to Category.Browsing,
            "firefox" to Category.Browsing,
            "com.google.Chrome" to Category.Browsing,
            "google-chrome" to Category.Browsing,
            "com.android.chrome" to Category.Browsing,
            "com.spotify.client" to Category.Media,
            "spotify" to Category.Media,
            "com.tinyspeck.slackmacgap" to Category.Communication,
            "slack" to Category.Communication,
            "com.microsoft.VSCode" to Category.Development,
            "code" to Category.Development,
            "com.apple.Terminal" to Category.Development,
            "alacritty" to Category.Development,
            "md.obsidian" to Category.Writing,
            "com.apple.Preview" to Category.Reading,
            "steam" to Category.Games,
            "com.apple.finder" to Category.Utilities,
        )
        expected.forEach { (key, category) ->
            assertEquals(category, engine().categoryOf(AppKey(key)), "wrong category for $key")
        }
    }

    @Test
    fun `lookup is case-insensitive, because platforms disagree about case`() {
        // Linux WM classes arrive lowercased by the collector, macOS bundle
        // ids do not, and the same registry serves both.
        assertEquals(Category.Browsing, engine().categoryOf(AppKey("COM.APPLE.SAFARI")))
        assertEquals(Category.Development, engine().categoryOf(AppKey("Code")))
    }

    @Test
    fun `surrounding whitespace does not defeat a lookup`() {
        assertEquals(Category.Browsing, engine().categoryOf(AppKey("  com.apple.Safari  ")))
    }

    // ---- unknown -> Uncategorized, never a guess ----

    @Test
    fun `an unknown app is Uncategorized`() {
        assertEquals(Category.Uncategorized, engine().categoryOf(AppKey("com.some.internal.tool")))
    }

    @Test
    fun `a name that merely LOOKS like a known app is not guessed at`() {
        // The locked rule: "never a confident wrong guess". Substring or
        // fuzzy matching would file all of these confidently and wrongly.
        listOf(
            "com.apple.Safari.helper",
            "firefox-bin-wrapper",
            "not.slack.at.all",
            "com.evil.spotify",
            "code-review-tool",
        ).forEach {
            assertEquals(
                Category.Uncategorized,
                engine().categoryOf(AppKey(it)),
                "$it was guessed at instead of left uncategorised",
            )
        }
    }

    @Test
    fun `an empty key is Uncategorized rather than an error`() {
        assertEquals(Category.Uncategorized, engine().categoryOf(AppKey("")))
    }

    // ---- overrides are sticky ----

    @Test
    fun `a user override beats the registry`() {
        val overrides = FakeOverrides()
        val slack = AppKey("com.tinyspeck.slackmacgap")
        assertEquals(Category.Communication, engine(overrides).categoryOf(slack))

        overrides.setOverride(slack, Category.Development)
        assertEquals(Category.Development, engine(overrides).categoryOf(slack))
    }

    @Test
    fun `an override survives a registry that says otherwise`() {
        // "Sticky" means a shipped dataset update does not silently undo the
        // user's choice. The registry here is the real one, and it disagrees.
        val overrides = FakeOverrides()
        overrides.setOverride(AppKey("com.apple.Safari"), Category.Development)
        assertEquals(Category.Development, engine(overrides).categoryOf(AppKey("com.apple.Safari")))
    }

    @Test
    fun `an override can categorise an app the registry has never heard of`() {
        val overrides = FakeOverrides()
        overrides.setOverride(AppKey("com.internal.dashboard"), Category.Development)
        assertEquals(Category.Development, engine(overrides).categoryOf(AppKey("com.internal.dashboard")))
    }

    @Test
    fun `clearing an override falls back to the registry, not to Uncategorized`() {
        val overrides = FakeOverrides()
        val safari = AppKey("com.apple.Safari")
        overrides.setOverride(safari, Category.Development)
        overrides.clearOverride(safari)
        assertEquals(Category.Browsing, engine(overrides).categoryOf(safari))
    }

    // ---- "why is this here" ----

    @Test
    fun `every category can explain itself`() {
        // docs/design-spec.md requires a "why is this here" option. A user who
        // cannot find out why will distrust the whole screen.
        val overrides = FakeOverrides()
        overrides.setOverride(AppKey("com.mine"), Category.Writing)

        assertEquals(CategorySource.UserOverride, engine(overrides).explain(AppKey("com.mine")))
        assertEquals(CategorySource.Registry, engine(overrides).explain(AppKey("com.apple.Safari")))
        assertEquals(CategorySource.Unknown, engine(overrides).explain(AppKey("com.unknown")))

        CategorySource.entries.forEach {
            val text = it.explanation(Category.Writing)
            assertTrue(text.isNotBlank() && text.endsWith(".") && !text.contains("null"))
        }
    }

    // ---- summarising a day ----

    @Test
    fun `totals group into categories, largest first`() {
        val totals = mapOf(
            AppKey("com.apple.Safari") to 3_600_000L,
            AppKey("firefox") to 1_800_000L,
            AppKey("com.apple.Terminal") to 7_200_000L,
            AppKey("com.unknown.thing") to 600_000L,
        )
        val summary = engine().summarize(totals)

        assertEquals(Category.Development, summary.first().category)
        assertEquals(7_200_000L, summary.first().totalMs)
        // Two browsers sum into one Browsing row.
        assertEquals(5_400_000L, summary.single { it.category == Category.Browsing }.totalMs)
        // Unknown time is VISIBLE, not dropped.
        assertEquals(600_000L, summary.single { it.category == Category.Uncategorized }.totalMs)
    }

    @Test
    fun `summarising conserves time exactly`() {
        // A category donut that does not add up to the day's total is the
        // "charts that lie" case from the spec.
        val totals = mapOf(
            AppKey("com.apple.Safari") to 1_234_567L,
            AppKey("com.unknown") to 7_654_321L,
            AppKey("slack") to 42L,
        )
        assertEquals(totals.values.sum(), engine().summarize(totals).sumOf { it.totalMs })
    }

    @Test
    fun `an empty day summarises to nothing rather than to a zero row`() {
        assertEquals(emptyList(), engine().summarize(emptyMap()))
    }

    // ---- the dataset itself ----

    @Test
    fun `the registry is non-trivial and every entry is a real category`() {
        assertTrue(GeneratedRegistry.keys.size >= 150, "registry has only ${GeneratedRegistry.keys.size} entries")
        GeneratedRegistry.keys.forEach {
            assertTrue(
                GeneratedRegistry.lookup(AppKey(it)) != Category.Uncategorized,
                "$it maps to Uncategorized, which is never a registry answer",
            )
        }
    }

    @Test
    fun `no registry key is stored with uppercase, or case-insensitive lookup would miss it`() {
        GeneratedRegistry.keys.forEach { assertEquals(it, it.lowercase()) }
    }

    @Test
    fun `Uncategorized is never a stored registry value`() {
        // It is the absence of an answer, not an answer.
        assertTrue(GeneratedRegistry.keys.none { GeneratedRegistry.lookup(AppKey(it)) == Category.Uncategorized })
    }

    @Test
    fun `an unrecognised stored name reads back as Uncategorized`() {
        // History can reference a category a later build removed; old rows
        // must still render rather than fail.
        assertEquals(Category.Uncategorized, Category.fromStored("SomethingRemoved"))
        assertEquals(Category.Uncategorized, Category.fromStored(null))
        assertEquals(Category.Browsing, Category.fromStored("Browsing"))
    }
}
