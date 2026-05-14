package com.yinxin.uavfir.api

data class CapabilityReportRequest(
    val droneSn: String,
    val visibleSupported: Boolean,
    val thermalSupported: Boolean,
)
