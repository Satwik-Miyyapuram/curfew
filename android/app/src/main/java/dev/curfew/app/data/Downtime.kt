package dev.curfew.app.data

/**
 * A period Curfew could not account for.
 *
 * [backwards] separates the two causes, because they deserve different words: a gap means Curfew
 * was not running between [from] and [to]; a backwards clock means the device's time moved from
 * [from] back to [to] while it was.
 */
data class Downtime(val from: Long, val to: Long, val backwards: Boolean) {
    /** Always the size of the discrepancy: [to] is where the device ended up, [from] where it was. */
    val seconds: Long get() = kotlin.math.abs(to - from)
}
