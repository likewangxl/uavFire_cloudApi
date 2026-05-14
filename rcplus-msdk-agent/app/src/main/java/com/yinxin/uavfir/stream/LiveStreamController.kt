package com.yinxin.uavfir.stream

interface LiveStreamController {
    suspend fun start(droneSn: String)

    suspend fun stop()
}
