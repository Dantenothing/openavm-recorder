package com.dante.zeekrcapabilitylab.preflight.continuous

import com.dante.zeekrcapabilitylab.preflight.remote.LabExperiment

/** Legacy target/reference still require CBR. A new trial selects one explicit profile and mode. */
internal object ProbeExperimentCandidates {
    fun specs(full:Boolean, shared:Boolean, stress:Boolean, experiment:LabExperiment?):List<ContinuousProbeSpec> {
        val controlled=experiment?.rateControlled==true
        require(!controlled || (full && shared && !stress)) { "RATE_TRIAL_ROUTE_MISMATCH" }
        val profiles=if(controlled)listOf(ProbeAvcProfile.valueOf(requireNotNull(experiment?.avcProfile)))
            else ProbeAvcProfile.entries
        return profiles.map { profile ->
            val original=if(full)ContinuousProbeSpec.surroundRepack(profile) else ContinuousProbeSpec.healthCheck(profile)
            val base=experiment?.let { e -> original.copy(encoding=original.encoding.copy(
                bitrateBps=e.bitrateBps,iFrameIntervalSeconds=e.iFrameIntervalSeconds)) } ?: original
            val mode=if(controlled)ProbeBitrateMode.valueOf(requireNotNull(experiment?.bitrateMode))
                else if(shared && full && !stress)ProbeBitrateMode.CBR else null
            base.copy(encoding=base.encoding.copy(bitrateMode=mode))
        }
    }
}
