package com.gpm.virtual.xposed

data class SortirJobInfo(
    val bookingCode: String,
    val vertical: String,
    val verticalId: Int,
    val variant: String,
    val jobTypeFlags: Map<String, Boolean>,
    val price: Float,
    val distanceKm: Float,
    val pickupAddr: String,
    val dropoffAddr: String,
    val pickupDist: String,
    val dropoffDist: String
) {
    val isBike:   Boolean get() = verticalId == 1
    val isCar:    Boolean get() = verticalId == 2
    val isFood:   Boolean get() = verticalId == 3
    val isNormal: Boolean get() = verticalId == 0

    override fun toString(): String =
        "Job[$bookingCode, $vertical, variant=$variant, price=$price]"
}