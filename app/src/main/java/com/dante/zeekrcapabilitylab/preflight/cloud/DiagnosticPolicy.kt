package com.dante.zeekrcapabilitylab.preflight.cloud

import com.dante.zeekrcapabilitylab.preflight.sha
import kotlinx.serialization.json.*
import java.net.URI

internal data class DiagnosticIdentity(val run: String, val version: String, val startedAt: Long,
    val phase: String, val hash: String, val bytes: Int)

internal object DiagnosticPolicy {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_PENDING = 32
    const val MAX_ATTEMPTS = 6
    const val SERVICE = "openavm-diagnostics-1"
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val privateField = Regex("itemUri|directoryPath|pairingCode|accessToken|refreshToken|password|vin|latitude|longitude|email", RegexOption.IGNORE_CASE)
    fun expectedRun(attemptPresent: Boolean, attempt: JsonObject?): String? {
        if (!attemptPresent) return null // Reports created before Beta20 have no attempt journal.
        return (attempt?.get("runId") as? JsonPrimitive)?.content
            ?.takeIf { uuid.matches(it) } ?: error("CURRENT_RUN_IDENTITY_UNAVAILABLE")
    }
    fun origin(text: String): String {
        val uri = URI(text.trim())
        require(uri.scheme == "https" && uri.host?.contains('.') == true && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath in listOf("", "/")) { "HTTPS_ORIGIN_REQUIRED" }
        return "https://${uri.host.lowercase()}"
    }
    fun identity(bytes: ByteArray, expectedRun: String? = null): DiagnosticIdentity {
        require(bytes.size in 1..MAX_BYTES) { "REPORT_SIZE_LIMIT" }
        val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(value["format"] == JsonPrimitive("OPENAVM_PREFLIGHT") && value["schemaVersion"] == JsonPrimitive(1) &&
            value["exampleOnly"] == JsonPrimitive(false) && value["productionSwitchAllowed"] == JsonPrimitive(false) &&
            value["tests"] is JsonArray) { "REPORT_SCHEMA_REJECTED" }
        val nodes = ArrayDeque<Pair<JsonElement,Int>>()
        nodes.add(value to 0)
        var visited = 0
        while (nodes.isNotEmpty()) {
            val (node,depth) = nodes.removeLast()
            require(++visited <= 250_000 && depth <= 48) { "REPORT_STRUCTURE_LIMIT" }
            when (node) {
                is JsonObject -> node.forEach { (key,child) ->
                    require(!privateField.matches(key)) { "REPORT_PRIVATE_FIELD_REJECTED" }
                    nodes.add(child to depth+1)
                }
                is JsonArray -> node.forEach { nodes.add(it to depth+1) }
                else -> Unit
            }
        }
        val run = value["runId"]?.jsonPrimitive?.content.orEmpty()
        require(uuid.matches(run)) { "REPORT_RUN_INVALID" }
        check(expectedRun == null || run == expectedRun) { "CURRENT_RUN_REPORT_UNAVAILABLE" }
        val version = value["version"]?.jsonPrimitive?.content.orEmpty()
        require(Regex("4\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.]+)?").matches(version)) { "REPORT_VERSION_INVALID" }
        return DiagnosticIdentity(run,version,value["startEpochMs"]?.jsonPrimitive?.longOrNull ?: 0,
            value["phase"]?.jsonPrimitive?.content.orEmpty(),sha(bytes),bytes.size)
    }
    fun verifyReceipt(receipt: JsonObject, report: DiagnosticIdentity) {
        require(receipt["schemaVersion"] == JsonPrimitive(1) && receipt["format"] == JsonPrimitive("OPENAVM_DIAGNOSTIC_RECEIPT") &&
            receipt["runId"] == JsonPrimitive(report.run) && receipt["reportSha256"] == JsonPrimitive(report.hash) &&
            receipt["reportVersion"] == JsonPrimitive(report.version) && receipt["bytes"]?.jsonPrimitive?.intOrNull == report.bytes &&
            receipt["receiptId"] == JsonPrimitive(report.hash) &&
            (receipt["receivedAtEpochMs"]?.jsonPrimitive?.longOrNull ?: 0)>0) { "RECEIPT_IDENTITY_MISMATCH" }
    }
    fun retryHttp(status: Int) = status == 408 || status == 429 || status in 500..506 || status in 508..599
    fun retryAllowed(attempts: Int, status: Int? = null) = attempts < MAX_ATTEMPTS && (status == null || retryHttp(status))
    fun fileName(report: DiagnosticIdentity) = "${report.run}-${report.hash}.json"
}
