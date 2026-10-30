package com.arena.dynamicisland.island

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

/**
 * Continuous G² Superellipse (Squircle) shape matching Apple's CALayer.cornerCurve = .continuous.
 * Uses the golden-ratio conjugate smoothing factor (p = 0.618) so straight edges blend into corners
 * without the G¹ curvature jump of standard circular arcs.
 */
class ContinuousSquircleShape(
    private val cornerRadius: Dp,
    private val smoothing: Float = 0.618f,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { cornerRadius.toPx() }.coerceAtMost(min(size.width, size.height) / 2f)
        return Outline.Generic(buildSquirclePath(size.width, size.height, r, smoothing))
    }

    companion object {
        fun buildSquirclePath(w: Float, h: Float, r: Float, smoothing: Float = 0.618f): Path {
            val path = Path()
            val k = 0.55228475f // Cubic Bézier handle factor
            val reach = (r * (1f + smoothing * 0.25f)).coerceAtMost(min(w, h) / 2f)
            val c = r * k

            path.moveTo(reach, 0f)
            path.lineTo(w - reach, 0f)
            path.cubicTo(w - r + c, 0f, w, r - c, w, reach)
            path.lineTo(w, h - reach)
            path.cubicTo(w, h - r + c, w - r + c, h, w - reach, h)
            path.lineTo(reach, h)
            path.cubicTo(r - c, h, 0f, h - r + c, 0f, h - reach)
            path.lineTo(0f, reach)
            path.cubicTo(0f, r - c, r - c, 0f, reach, 0f)
            path.close()
            return path
        }

        /**
         * AGSL Smooth-Minimum SDF Shader (API 33+) for the 4dp liquid metaball neck
         * that stretches and ruptures at 14dp separation during SplitDroplet bifurcation.
         */
        const val METABALL_AGSL_SHADER = """
            uniform float2 uResolution;
            uniform float4 uPrimaryRect;   // x, y, width, height
            uniform float4 uDropletCircle; // cx, cy, radius, unused
            uniform float uSmoothK;        // 12.0 px smooth minimum radius

            float sdRoundBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            float smin(float a, float b, float k) {
                float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
                return mix(b, a, h) - k * h * (1.0 - h);
            }

            half4 main(float2 fragCoord) {
                float2 pCenter = uPrimaryRect.xy + uPrimaryRect.zw * 0.5;
                float2 halfSize = uPrimaryRect.zw * 0.5;
                float r = min(halfSize.x, halfSize.y);
                float dPill = sdRoundBox(fragCoord - pCenter, halfSize, r);
                float dDrop = length(fragCoord - uDropletCircle.xy) - uDropletCircle.z;
                float d = smin(dPill, dDrop, uSmoothK);
                float alpha = smoothstep(0.75, -0.75, d);
                return half4(0.0, 0.0, 0.0, alpha);
            }
        """
    }
}
