package com.dynamicisland.core.geometry

/** Separate windows preserve touch pass-through in the gap; never enlarge the primary. */
object SecondaryBubblePlacement {
    fun resolve(
        primary: CapsuleAnchor.Window,
        screenWidth: Int,
        screenHeight: Int,
        diameter: Int,
        gap: Int,
        rtl: Boolean = false
    ): CapsuleAnchor.Window? {
        require(diameter > 0 && gap >= 0)
        val p = primary
        val candidates = if (p.rotation == 90f || p.rotation == -90f) listOf(
            CapsuleAnchor.Window(p.left + (p.width - diameter) / 2, p.top + p.height + gap, diameter, diameter, p.rotation),
            CapsuleAnchor.Window(p.left + (p.width - diameter) / 2, p.top - gap - diameter, diameter, diameter, p.rotation)
        ) else listOf(
            CapsuleAnchor.Window(p.left + p.width + gap, p.top + (p.height - diameter) / 2, diameter, diameter, p.rotation),
            CapsuleAnchor.Window(p.left - gap - diameter, p.top + (p.height - diameter) / 2, diameter, diameter, p.rotation)
        ).let { if (rtl) it.reversed() else it }
        return candidates.firstOrNull {
            it.left >= 0 && it.top >= 0 && it.left + diameter <= screenWidth && it.top + diameter <= screenHeight
        }
    }

    /**
     * Resolves an optional tertiary droplet window (iPhone 18 Pro 3-activity mitosis standard)
     * positioned opposite the secondary droplet or beside it with a preserved touch pass-through gap.
     */
    fun resolveTertiary(
        primary: CapsuleAnchor.Window,
        secondary: CapsuleAnchor.Window,
        screenWidth: Int,
        screenHeight: Int,
        diameter: Int,
        gap: Int,
        rtl: Boolean = false
    ): CapsuleAnchor.Window? {
        require(diameter > 0 && gap >= 0)
        val p = primary
        val s = secondary
        val candidates = if (p.rotation == 90f || p.rotation == -90f) listOf(
            CapsuleAnchor.Window(p.left + (p.width - diameter) / 2, p.top - gap - diameter, diameter, diameter, p.rotation),
            CapsuleAnchor.Window(s.left, s.top + s.height + gap, diameter, diameter, p.rotation)
        ) else listOf(
            // Opposite side of primary pill first (balanced 3-slot layout), then outer side of secondary droplet
            CapsuleAnchor.Window(p.left - gap - diameter, p.top + (p.height - diameter) / 2, diameter, diameter, p.rotation),
            CapsuleAnchor.Window(s.left + s.width + gap, p.top + (p.height - diameter) / 2, diameter, diameter, p.rotation)
        ).let { if (rtl) it.reversed() else it }

        return candidates
            .filter { c -> !overlaps(c, p) && !overlaps(c, s) }
            .firstOrNull {
                it.left >= 0 && it.top >= 0 && it.left + diameter <= screenWidth && it.top + diameter <= screenHeight
            }
    }

    private fun overlaps(a: CapsuleAnchor.Window, b: CapsuleAnchor.Window): Boolean {
        return a.left < b.left + b.width &&
            a.left + a.width > b.left &&
            a.top < b.top + b.height &&
            a.top + a.height > b.top
    }
}
