package com.dante.zeekrcapabilitylab.service.recorder

/** Pure formatting/filtering rules for recorder camera-conflict evidence. */
object CameraConflictDiagnostics {
    const val NONE_DECLARED = "NONE_DECLARED"
    private val knownVehicleCameraIds = setOf("0", "1", "2")

    fun encodeCameraIds(cameraIds: Collection<String>): String =
        cameraIds.distinct().sorted().joinToString(",").ifEmpty { "NONE" }

    fun encodeConcurrentSets(concurrentSets: Collection<Set<String>>): String =
        concurrentSets
            .map { it.sorted().joinToString("+") }
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .joinToString("|")
            .ifEmpty { NONE_DECLARED }

    fun supportsPair(
        concurrentSets: Collection<Set<String>>,
        firstCameraId: String,
        secondCameraId: String,
    ): Boolean = concurrentSets.any { set ->
        firstCameraId in set && secondCameraId in set
    }

    fun shouldLogAvailability(cameraId: String, activeCameraId: String?): Boolean =
        cameraId in knownVehicleCameraIds || cameraId == activeCameraId

    fun encodePhysicalIds(physicalIdsByCamera: Map<String, Set<String>>): String =
        physicalIdsByCamera.entries
            .sortedBy(Map.Entry<String, Set<String>>::key)
            .joinToString("|") { (cameraId, physicalIds) ->
                "$cameraId=${physicalIds.sorted().joinToString("+").ifEmpty { "-" }}"
            }
            .ifEmpty { "UNAVAILABLE" }
}
