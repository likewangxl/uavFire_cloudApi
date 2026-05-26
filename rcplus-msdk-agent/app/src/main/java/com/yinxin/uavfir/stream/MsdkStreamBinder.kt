package com.yinxin.uavfir.stream

interface MsdkStreamBinder {
    suspend fun bindVisible(droneSn: String)

    suspend fun bindThermal(droneSn: String)

    suspend fun focusVisible(droneSn: String)

    suspend fun focusThermal(droneSn: String)

    suspend fun measureThermalRegionTemperatureC(region: ThermalMeasureRegion): Double?

    suspend fun measureThermalCenterTemperatureC(): Double?

    suspend fun locateAndMeasureThermalHotspotC(
        seedRegion: ThermalMeasureRegion?,
    ): ThermalMeasurementResult? {
        val region = seedRegion ?: ThermalMeasureRegion.CENTER
        val temperature = measureThermalRegionTemperatureC(region) ?: return null
        return ThermalMeasurementResult(
            temperatureC = temperature,
            region = region,
        )
    }

    suspend fun unbindAll()
}

data class ThermalMeasurementResult(
    val temperatureC: Double,
    val region: ThermalMeasureRegion,
)

data class ThermalMeasureRegion(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    companion object {
        val CENTER = ThermalMeasureRegion(x = 0.35, y = 0.35, width = 0.30, height = 0.30)
    }
}
