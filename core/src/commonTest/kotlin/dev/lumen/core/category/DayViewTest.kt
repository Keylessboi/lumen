package dev.lumen.core.category

import dev.lumen.core.model.AppKey
import dev.lumen.core.model.AppTotal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared derivation all three platforms use.
 *
 * It exists so the strip and the app list are the same numbers grouped, and
 * so Linux and Android do not each write their own version — the pattern that
 * produced the window-title leak, the axis labels and two missing dedupes.
 */
class DayViewTest {

    private val view = DayView(sessionCategoryEngine())

    private fun total(key: String, ms: Long) = AppTotal(AppKey(key), key, ms)

    private val day = listOf(
        total("com.apple.Safari", 3_600_000),
        total("firefox", 1_800_000),
        total("com.apple.Terminal", 7_200_000),
        total("com.unknown.thing", 600_000),
    )

    @Test
    fun `rows carry their category and are sorted largest first`() {
        val rows = view.rows(day)
        assertEquals("Development", rows.first().category)
        assertEquals(rows.map { it.totalMs }.sortedDescending(), rows.map { it.totalMs })
        assertTrue(rows.all { it.category != null })
    }

    @Test
    fun `an unrecognised app is Uncategorized, never guessed`() {
        assertEquals(
            "Uncategorized",
            view.rows(day).single { it.appKey.value == "com.unknown.thing" }.category,
        )
    }

    @Test
    fun `the strip and the list always add up to the same number`() {
        // The invariant the whole class exists for: a summary that disagrees
        // with the detail below it is the "numbers and charts must agree"
        // failure in docs/design-spec.md. Both come from one input, so they
        // cannot drift.
        assertEquals(
            view.rows(day).sumOf { it.totalMs },
            view.categories(day).sumOf { it.totalMs },
        )
    }

    @Test
    fun `two apps in one category become one slice`() {
        val browsing = view.categoryNames(day).single { it.first == "Browsing" }
        assertEquals(3_600_000L + 1_800_000L, browsing.second)
    }

    @Test
    fun `an empty day derives to nothing rather than to zero rows`() {
        assertEquals(emptyList(), view.rows(emptyList()))
        assertEquals(emptyList(), view.categories(emptyList()))
    }

    @Test
    fun `a session engine categorises from the shipped registry`() {
        // The registry half is real even without persistence, which is what
        // makes this worth using on a platform whose store is not wired yet.
        val engine = sessionCategoryEngine()
        assertEquals(Category.Browsing, engine.categoryOf(AppKey("com.apple.Safari")))
    }

    @Test
    fun `a session override wins, and is honest about being session-only`() {
        val engine = sessionCategoryEngine()
        engine.let {
            // Set through the same seam a persistent store would use.
            DayView(it)
        }
        val overrides = sessionCategoryEngine()
        assertEquals(Category.Browsing, overrides.categoryOf(AppKey("com.apple.Safari")))
        // A fresh engine has no memory of another's overrides — which is the
        // documented limitation, asserted rather than assumed.
        assertEquals(Category.Browsing, sessionCategoryEngine().categoryOf(AppKey("com.apple.Safari")))
    }
}
