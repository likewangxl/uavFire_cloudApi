package com.yinxin.uavfir.api

data class DualStreamEventRequest(
    val taskId: String? = null,
    val droneSn: String,
    val sourceTs: Long,
    val visibleScore: Double = 0.0,
    val thermalScore: Double,
    val fusionScore: Double,
    val riskLevel: String,
    val analysisChannel: String = "thermal",
    val reviewStatus: String? = null,
    val thermalImageUrl: String? = null,
    val visibleImageUrl: String? = null,
    val thermalSourceEventId: String? = null,
    val thermalTemperature: Double? = null,
    val thermalMeasureRoi: Map<String, Double>? = null,
    val thermalMeasurements: List<ThermalMeasurementPayload> = emptyList(),
    val geoSnapshot: GeoSnapshot? = null,
)

data class ThermalMeasurementPayload(
    val temperatureC: Double,
    val roi: Map<String, Double>,
)

data class GeoSnapshot(
    val aircraftPosition: AircraftPosition? = null,
    val aircraftAttitude: Attitude? = null,
    val gimbalAttitude: Attitude? = null,
    val cameraModel: CameraModel? = null,
    val frameSize: FrameSize? = null,
    val thermalRoi: ThermalRoi? = null,
    val rtkStatus: String? = null,
    val sourceTs: Long? = null,
)

data class AircraftPosition(
    val lat: Double? = null,
    val lng: Double? = null,
    val alt: Double? = null,
    val relativeAlt: Double? = null,
)

data class Attitude(
    val yaw: Double? = null,
    val pitch: Double? = null,
    val roll: Double? = null,
)

data class CameraModel(
    val model: String? = null,
    val focalLengthMm: Double? = null,
    val horizontalFovDeg: Double? = null,
    val verticalFovDeg: Double? = null,
)

data class FrameSize(
    val width: Int? = null,
    val height: Int? = null,
)

data class ThermalRoi(
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
)
