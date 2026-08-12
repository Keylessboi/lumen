package dev.lumen.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app list is sized in whole rows.
 *
 * Sized to the raw leftover height it ends part-way through a row, and a row
 * sliced through its own text reads as a rendering fault rather than as
 * "there is more below this".
 */
class VisibleRowCountTest {

    private val pitch = 40.dp

    @Test
    fun `an exact fit shows every row it has room for`() {
        assertEquals(3, visibleRowCount(120.dp, pitch))
    }

    @Test
    fun `a partial row is not counted`() {
        assertEquals(3, visibleRowCount(159.dp, pitch))
        assertEquals(4, visibleRowCount(160.dp, pitch))
    }

    /**
     * The case that produced an APPS heading with nothing under it: the
     * history banner took enough of the window that the list's share was
     * under one row. Zero rows says "no apps", which is false.
     */
    @Test
    fun `too little room still shows one row`() {
        assertEquals(1, visibleRowCount(12.dp, pitch))
        assertEquals(1, visibleRowCount(0.dp, pitch))
    }

    @Test
    fun `a negative share cannot produce a negative count`() {
        assertEquals(1, visibleRowCount((-50).dp, pitch))
    }
}
