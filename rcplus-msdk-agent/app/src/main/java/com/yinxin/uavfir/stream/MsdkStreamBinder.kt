package com.yinxin.uavfir.stream

interface MsdkStreamBinder {
    suspend fun bindVisible(droneSn: String)

    suspend fun bindThermal(droneSn: String)

    suspend fun focusVisible(droneSn: String)

    suspend fun focusThermal(droneSn: String)

    suspend fun unbindAll()
}
