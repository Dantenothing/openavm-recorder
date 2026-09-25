package com.dante.zeekrcheck.core

/** Bind a street label to the coordinates actually geocoded, not to each successive GPS update. */
object VehicleAddress {
    const val MATCH_METRES = 40
    fun updated(point: CarLocation, previous: CarLocation?): CarLocation {
        val anchor = previous?.addressLatitude?.let { lat -> previous.addressLongitude?.let { lon -> CarLocation(lat, lon, null) } }
        val reuse = anchor != null && point.distance(anchor) <= MATCH_METRES
        return point.copy(verified = previous?.verified == true,
            address = if (reuse) previous!!.address else "",
            addressLatitude = if (reuse) anchor!!.latitude else null, addressLongitude = if (reuse) anchor!!.longitude else null)
    }
    fun resolved(current: CarLocation?, requested: CarLocation, label: String): CarLocation? =
        if (current == null || label.isBlank() || current.distance(requested) > MATCH_METRES) current
        else current.copy(address = label.trim().take(160), addressLatitude = requested.latitude, addressLongitude = requested.longitude)

    fun label(number: String?, road: String?, area: String?): String? {
        val street = road?.trim()?.takeIf { it.isNotEmpty() }
        val suburb = area?.trim()?.takeIf { it.isNotEmpty() }
        return if (street != null) listOfNotNull(listOfNotNull(number?.trim()?.takeIf { it.isNotEmpty() }, street).joinToString(" "),
            suburb?.takeIf { !street.contains(it, ignoreCase = true) }).joinToString(" · ").take(150) + " 附近"
        else suburb?.take(150)?.let { "$it 附近" }
    }
}
