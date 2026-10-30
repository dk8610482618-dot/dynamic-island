package com.dynamicisland.engine.ui.squircle

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.dynamicisland.core.geometry.SquircleGeometry

/**
 * Apple G2 curvature-continuous superellipse shape.
 *
 * `CALayerCornerCurve.continuous` equivalent: constant curvature along each
 * straight edge, no discontinuity where the fillet meets it, so no optical
 * "pinch" on OLED.
 *
 * All of the geometry lives in [SquircleGeometry], in the Android-free `:core`
 * module, where it is actually executed by unit tests. This class is only the
 * Compose adapter: it converts dp to px and replays the segments into a `Path`.
 */
class SquircleShape(
    private val cornerRadius: Dp
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val radiusPx = with(density) { cornerRadius.toPx() }
        val path = Path()
        for (segment in SquircleGeometry.path(size.width, size.height, radiusPx)) {
            when (segment) {
                is SquircleGeometry.Segment.MoveTo -> path.moveTo(segment.x, segment.y)
                is SquircleGeometry.Segment.LineTo -> path.lineTo(segment.x, segment.y)
                is SquircleGeometry.Segment.CubicTo -> path.cubicTo(
                    segment.cp1x, segment.cp1y,
                    segment.cp2x, segment.cp2y,
                    segment.x, segment.y
                )
                SquircleGeometry.Segment.Close -> path.close()
            }
        }
        return Outline.Generic(path)
    }
}
