package com.dante.zeekrcapabilitylab.preflight.remote

import com.dante.zeekrcapabilitylab.preflight.obj
import org.junit.Assert.*
import org.junit.Test

class LabEvidenceApprovalTest {
    private val a="11111111-1111-4111-8111-111111111111"
    private val b="22222222-2222-4222-8222-222222222222"
    private val hash="a".repeat(64)
    private fun request(vararg ids:String)=obj("inventoryHash" to hash,"operationIds" to ids.toList(),"acknowledgeEvidenceLoss" to true)
    @Test fun noSelectionIsAuthorizedUnlessTheEntireReviewedListStillMatches() {
        assertEquals(listOf(a),LabEvidenceApproval.selected(request(a),hash,mapOf(a to true,b to true)))
        assertThrows(IllegalStateException::class.java) {LabEvidenceApproval.selected(request(a),"b".repeat(64),mapOf(a to true))}
        assertThrows(IllegalStateException::class.java) {LabEvidenceApproval.selected(request(a,b),hash,mapOf(a to true))}
        assertThrows(IllegalStateException::class.java) {LabEvidenceApproval.selected(request(a,b),hash,mapOf(a to true,b to false))}
        assertThrows(IllegalArgumentException::class.java) {LabEvidenceApproval.selected(request(a,a),hash,mapOf(a to true))}
    }
    @Test fun lossAcknowledgementIsRequiredEvenForCorrectFileIdentities() {
        assertThrows(IllegalArgumentException::class.java) {LabEvidenceApproval.selected(
            obj("inventoryHash" to hash,"operationIds" to listOf(a),"acknowledgeEvidenceLoss" to false),hash,mapOf(a to true))}
    }
}
