package dev.curfew.app.data

/**
 * An attempt to move the device clock that Curfew saw and refused.
 *
 * Only one of the two counts is ever non-zero: a reading is either ahead of what the monotonic
 * clock supports or behind it. Both are seconds the wall clock claimed and did not get.
 */
data class ClockTamper(
    val at: Long,
    val forwardSeconds: Long,
    val backwardSeconds: Long,
) {
    /** Forward is the one worth naming: it is the only direction that could shorten a lock. */
    val forward: Boolean get() = forwardSeconds > 0

    val seconds: Long get() = if (forward) forwardSeconds else backwardSeconds
}
