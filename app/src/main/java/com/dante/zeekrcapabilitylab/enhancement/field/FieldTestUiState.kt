package com.dante.zeekrcapabilitylab.enhancement.field

data class FieldSignalUi(val key: String, val nameEn: String, val nameZh: String,
    val value: String, val status: String, val ageMs: Long?, val changes: Int)

data class FieldTestUiState(
    val active: Boolean = false,
    val busy: Boolean = false,
    val phase: String = "IDLE",
    val reason: String? = null,
    val elapsedSeconds: Long = 0,
    val durationSeconds: Int = 120,
    val sampledSignals: Int = 0,
    val validSignals: Int = 0,
    val changedSignals: Int = 0,
    val vehicleSpeedKmh: Double? = null,
    val gpsSpeedKmh: Double? = null,
    val gpsStatus: String = "NOT_STARTED",
    val sdkStatus: String = "NOT_STARTED",
    val recorderRunning: Boolean = false,
    val savedReports: Int = 0,
    val reportAvailable: Boolean = false,
    val notice: String? = null,
    val signals: List<FieldSignalUi> = emptyList(),
)
