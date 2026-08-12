package dev.lumen.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/e2ee.md` §7: "the UI shows an honest strength estimate and does not
 * fabricate a security guarantee."
 *
 * These tests are mostly about what the UI must NOT say. A strength meter is
 * the easy thing to build here and it would be a lie: nothing a client can
 * compute distinguishes a memorable passphrase from a sentence out of a
 * popular book.
 */
class PassphraseAssessmentTest {

    @Test
    fun `an empty passphrase says nothing and blocks the button`() {
        val a = assessPassphrase("")
        assertEquals(false, a.usable)
        assertEquals("", a.message, "should not nag before the user has typed")
    }

    @Test
    fun `a trivially short passphrase is refused with the reason`() {
        val a = assessPassphrase("hunter2")
        assertEquals(false, a.usable)
        assertTrue(a.message.contains("8"), a.message)
    }

    @Test
    fun `the minimum is a floor, not a recommendation`() {
        // Exactly at the floor is usable. The app does not get to decide what
        // is strong enough — it only refuses what is trivially openable.
        assertTrue(assessPassphrase("12345678").usable)
    }

    @Test
    fun `a multi-word passphrase is reported as facts, not praised`() {
        val a = assessPassphrase("correct horse battery staple")
        assertTrue(a.usable)
        assertTrue(a.message.contains("28 characters"), a.message)
        assertTrue(a.message.contains("4 words"), a.message)
    }

    @Test
    fun `a short-ish passphrase gets a suggestion, not a verdict`() {
        val a = assessPassphrase("Tr0ub4dor&3")
        assertTrue(a.usable, "a real passphrase must not be blocked")
        assertTrue(a.message.contains("unrelated words"), a.message)
    }

    @Test
    fun `it never claims strength, weakness, or safety`() {
        // The whole point. A green tick would be a guarantee nobody can make.
        val banned = listOf(
            "strong", "weak", "secure", "safe", "excellent", "good", "poor",
            "score", "unbreakable", "guaranteed", "perfect",
        )
        listOf(
            "", "short", "12345678", "correct horse battery staple",
            "Tr0ub4dor&3", "a".repeat(80), "пароль из нескольких слов",
        ).forEach { input ->
            val message = assessPassphrase(input).message.lowercase()
            banned.forEach {
                assertTrue(!message.contains(it), "assessment of '$input' claims '$it': $message")
            }
        }
    }

    @Test
    fun `a long passphrase is simply counted`() {
        val a = assessPassphrase("a".repeat(64))
        assertTrue(a.usable)
        assertEquals("64 characters.", a.message)
    }

    @Test
    fun `whitespace does not inflate the word count`() {
        // Padding and runs of spaces must not be counted as words, or a
        // passphrase would look more varied than it is — which is the exact
        // flattery this assessment exists to avoid.
        val padded = assessPassphrase("   correct   horse  battery    staple   ")
        assertTrue(padded.message.contains("4 words"), padded.message)
        assertTrue(!padded.message.contains("5 words") && !padded.message.contains("8 words"), padded.message)
    }

    @Test
    fun `unicode counts as characters rather than crashing`() {
        val a = assessPassphrase("правильная лошадь батарейка скрепка")
        assertTrue(a.usable)
        assertTrue(a.message.contains("4 words"), a.message)
    }
}
