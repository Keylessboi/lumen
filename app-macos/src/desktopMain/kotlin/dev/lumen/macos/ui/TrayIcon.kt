package dev.lumen.macos.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter

/**
 * Menu-bar icon, drawn rather than shipped as a binary asset.
 *
 * A ring with a filled core — a small aperture, matching the product name
 * without resorting to a clock or an hourglass, both of which read as
 * deadline pressure. The spec's tone rule applies to the icon too.
 *
 * Deliberately monochrome. macOS menu-bar icons are expected to be template
 * images that invert with light/dark menu bars and with the highlight state; a
 * coloured icon looks wrong in every context except the one it was designed
 * for. [tint] is supplied by the caller so the icon can follow the menu bar
 * rather than fight it.
 */
class LumenTrayIcon(private val tint: Color = Color.Black) : Painter() {

    override val intrinsicSize: Size = Size(22f, 22f)

    override fun DrawScope.onDraw() {
        val d = minOf(size.width, size.height)
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = d * 0.34f
        val strokeWidth = d * 0.10f

        drawCircle(
            color = tint,
            radius = ringRadius,
            center = center,
            style = Stroke(width = strokeWidth),
        )
        drawCircle(
            color = tint,
            radius = d * 0.13f,
            center = center,
        )
    }
}
