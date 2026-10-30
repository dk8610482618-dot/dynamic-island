package com.dynamicisland.core.geometry

/** Minute-of-day quiet period, inclusive start and exclusive end. Equal endpoints disable it. */
object QuietHours {
    fun contains(now: Int, start: Int, end: Int): Boolean {
        require(now in 0..1439 && start in 0..1439 && end in 0..1439)
        return when { start == end -> false; start < end -> now in start until end; else -> now >= start || now < end }
    }
}
