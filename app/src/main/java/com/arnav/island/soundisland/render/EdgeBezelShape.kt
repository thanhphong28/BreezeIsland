package com.arnav.island.soundisland.render

import android.graphics.Path

enum class ScreenEdge { RIGHT, LEFT }

/**
 * Builds a continuous path for the Edge Bezel Notch with concave fillets blending
 * seamlessly into the physical screen bezel, matching the iPhone Action Button / Dynamic Island
 * edge protrusion style.
 *
 * Guarantees that cornerRadius and filletRadius are safely constrained by tabWidth so the
 * path never inverts or self-intersects at narrow widths (e.g. Stick mode).
 */
object EdgeBezelShape {

    fun build(
        path: Path,
        edge: ScreenEdge,
        screenWidth: Float,
        top: Float,
        bottom: Float,
        tabWidth: Float,
        cornerRadius: Float = 26f,
        filletRadius: Float = 18f,
    ) {
        path.rewind()
        if (tabWidth < 4f) return

        // Constrain curves so (cR + fR) strictly never exceeds 90% of tabWidth
        val maxCurve = tabWidth * 0.45f
        val cR = cornerRadius.coerceAtMost((bottom - top) / 2f).coerceAtMost(maxCurve)
        val fR = filletRadius.coerceAtMost(maxCurve)

        if (edge == ScreenEdge.RIGHT) {
            val right = screenWidth
            val left = right - tabWidth

            // 1. Start on the right bezel above the notch
            path.moveTo(right, top - fR)

            // 2. Concave fillet curving outward from bezel to notch top edge
            path.cubicTo(
                right, top - fR * 0.45f,
                right - fR * 0.45f, top,
                right - fR, top,
            )

            // 3. Top edge of the notch
            path.lineTo(left + cR, top)

            // 4. Top-left rounded corner
            path.cubicTo(
                left + cR * 0.45f, top,
                left, top + cR * 0.45f,
                left, top + cR,
            )

            // 5. Left outer edge
            path.lineTo(left, bottom - cR)

            // 6. Bottom-left rounded corner
            path.cubicTo(
                left, bottom - cR * 0.45f,
                left + cR * 0.45f, bottom,
                left + cR, bottom,
            )

            // 7. Bottom edge of the notch
            path.lineTo(right - fR, bottom)

            // 8. Concave fillet curving back into the right bezel
            path.cubicTo(
                right - fR * 0.45f, bottom,
                right, bottom + fR * 0.45f,
                right, bottom + fR,
            )

            // 9. Close along the screen bezel edge
            path.lineTo(right, top - fR)
            path.close()
        } else {
            val left = 0f
            val right = tabWidth

            path.moveTo(left, top - fR)
            path.cubicTo(
                left, top - fR * 0.45f,
                left + fR * 0.45f, top,
                left + fR, top,
            )
            path.lineTo(right - cR, top)
            path.cubicTo(
                right - cR * 0.45f, top,
                right, top + cR * 0.45f,
                right, top + cR,
            )
            path.lineTo(right, bottom - cR)
            path.cubicTo(
                right, bottom - cR * 0.45f,
                right - cR * 0.45f, bottom,
                right - cR, bottom,
            )
            path.lineTo(left + fR, bottom)
            path.cubicTo(
                left + fR * 0.45f, bottom,
                left, bottom + fR * 0.45f,
                left, bottom + fR,
            )
            path.lineTo(left, top - fR)
            path.close()
        }
    }
}
