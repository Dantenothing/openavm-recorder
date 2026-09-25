package com.dante.zeekrcapabilitylab.usbexport

import java.util.Locale

/** Catalog evidence is scoped to one locked batch, never retained between calls. */
internal class UsbManualDeleteBatch<T>(
    private val withTarget: (String, () -> Unit) -> Unit,
    private val inspect: (String, Set<OpenAvmOwnedUnitKind>) -> T,
    private val remove: (T, OpenAvmUsbDeleteRequest) -> Long,
    private val remaining: (T, Set<OpenAvmOwnedUnitKind>) -> Set<OpenAvmOwnedUnitRef>,
) {
    private data class Deleted(val units: Int, val bytes: Long)

    fun delete(requests: List<OpenAvmUsbDeleteRequest>): OpenAvmUsbDeleteResult {
        val unique = requests.distinctBy { it.recordingKey }.map { it.copy(units = it.units.distinct()) }
        val outcomes = linkedMapOf<String, Result<Deleted>>()
        unique.groupBy { it.storageUuid.lowercase(Locale.ROOT) }.values.forEach { group ->
            val kinds = group.flatMap { it.units }.mapTo(mutableSetOf()) { it.kind }
            runCatching {
                withTarget(group.first().storageUuid) {
                    val evidence = inspect(group.first().storageUuid, kinds)
                    val attempted = mutableSetOf<OpenAvmOwnedUnitRef>()
                    group.forEach { request ->
                        outcomes[request.recordingKey] = runCatching {
                            require(request.units.isNotEmpty()) { "NO_VERIFIED_OWNED_UNITS" }
                            require(request.units.none { it in attempted }) { "DUPLICATE_OWNED_UNIT" }
                            attempted.addAll(request.units)
                            Deleted(request.units.size, remove(evidence, request))
                        }
                    }
                    // All removals still perform their own exact-item checks. Publish success only
                    // after a fresh catalog on the same mounted volume confirms the whole batch.
                    if (group.any { outcomes[it.recordingKey]?.isSuccess == true }) {
                        val present = remaining(evidence, kinds)
                        group.filter { outcomes[it.recordingKey]?.isSuccess == true }.forEach { request ->
                            if (request.units.any { it in present }) outcomes[request.recordingKey] =
                                Result.failure(IllegalStateException("POST_DELETE_CATALOG_VERIFY_FAILED"))
                        }
                    }
                }
            }.onFailure { failure ->
                group.forEach { request ->
                    if (outcomes[request.recordingKey]?.isFailure != true) outcomes[request.recordingKey] = Result.failure(failure)
                }
            }
        }
        val deleted = outcomes.values.mapNotNull { it.getOrNull() }
        return OpenAvmUsbDeleteResult(unique.size, deleted.size, deleted.sumOf { it.units }, deleted.sumOf { it.bytes },
            unique.size - deleted.size, outcomes.mapNotNull { (key, outcome) ->
                outcome.exceptionOrNull()?.let { "$key:${it.message ?: it.javaClass.simpleName}" }
            })
    }
}
