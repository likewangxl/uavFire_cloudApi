package com.yinxin.uavfir.sdk

import com.yinxin.uavfir.session.AgentConnectionState
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.RtkMobileStationKey
import dji.sdk.keyvalue.value.rtkmobilestation.RTKPositioningSolution
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.v5.et.create
import dji.v5.et.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

interface DjiDeviceSessionAdapter {
    suspend fun initialize(): DjiDeviceState
}

class DjiDeviceSession(
    private val djiSdkGateway: DjiSdkGateway,
) : DjiDeviceSessionAdapter {
    private val initializeMutex = Mutex()

    override suspend fun initialize(): DjiDeviceState {
        return initializeMutex.withLock {
            if (!djiSdkGateway.initialize()) {
                return@withLock DjiDeviceState(connectionState = AgentConnectionState.ERROR)
            }

            if (!djiSdkGateway.isAircraftConnected()) {
                return@withLock DjiDeviceState(connectionState = AgentConnectionState.SDK_READY)
            }

            DjiDeviceState(
                connectionState = AgentConnectionState.CAPABILITY_READY,
                identity = djiSdkGateway.loadDeviceIdentity(),
                capability = djiSdkGateway.loadCapability(),
                telemetry = loadTelemetry(),
                aircraftName = djiSdkGateway.loadAircraftName(),
                aircraftModel = djiSdkGateway.loadAircraftModel(),
                flightLimit = djiSdkGateway.loadFlightLimit(),
            )
        }
    }

    private fun loadTelemetry(): DjiTelemetry {
        val location: LocationCoordinate3D? = runCatching {
            FlightControllerKey.KeyAircraftLocation3D.create().get(LocationCoordinate3D(0.0, 0.0, 0.0))
        }.getOrNull()
        val velocity: Velocity3D? = runCatching {
            FlightControllerKey.KeyAircraftVelocity.create().get(Velocity3D(0.0, 0.0, 0.0))
        }.getOrNull()
        val elevation: Double? = runCatching {
            FlightControllerKey.KeyAltitude.create().get(0.0)
        }.getOrNull()
        val batteryPercent: Int? = runCatching {
            BatteryKey.KeyChargeRemainingInPercent.create().get(0)
        }.getOrNull()
        val gpsCount: Int? = runCatching {
            FlightControllerKey.KeyGPSSatelliteCount.create().get()
        }.getOrNull()
        val rtkCount: Int? = runCatching {
            RtkMobileStationKey.KeyRTKSatelliteCount.create().get()
        }.getOrNull()
        val positionFixed: Boolean? = runCatching {
            RtkMobileStationKey.KeyRTKLocation.create().get()?.positioningSolution
        }.getOrNull()?.let { it == RTKPositioningSolution.FIXED_POINT }
        val horizontalSpeed = velocity?.let { sqrt(it.x * it.x + it.y * it.y) }

        return DjiTelemetry(
            latitude = location?.latitude,
            longitude = location?.longitude,
            height = location?.altitude,
            elevation = elevation,
            horizontalSpeed = horizontalSpeed,
            verticalSpeed = velocity?.z,
            batteryPercent = batteryPercent,
            gpsCount = gpsCount,
            rtkCount = rtkCount,
            positionFixed = positionFixed,
        )
    }
}
