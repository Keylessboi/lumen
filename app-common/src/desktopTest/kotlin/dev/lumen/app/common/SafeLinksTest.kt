package dev.lumen.app.common

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeLinksTest {
    @Test
    fun `only web links with a host get out`() {
        assertTrue(isSafeWebLink("https://libre.fm/api/auth/?api_key=k&token=t"))
        assertTrue(isSafeWebLink("http://example.org/register"))
        assertFalse(isSafeWebLink("file:///etc/passwd"))
        assertFalse(isSafeWebLink("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isSafeWebLink("--help"))
        assertFalse(isSafeWebLink("https:///nohost"))
        assertFalse(isSafeWebLink("https://example.org/a b"))
        assertFalse(isSafeWebLink("javascript:alert(1)"))
    }
}
