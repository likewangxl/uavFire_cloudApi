package com.yinxin.uavfir.api

data class VisibleRoiSnapshotResponse(
    val sourceTs: Long,
    val visibleRoi: Map<String, Double>,
)
