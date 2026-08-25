package com.yinxin.uavfir.sdk

import com.yinxin.uavfir.BuildConfig
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.manager.KeyManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

interface MsdkKeyValueClient {
    fun isAircraftConnected(): Boolean

    fun loadCapability(): CameraCapability

    fun loadAircraftModel(): String?

    fun loadFlightLimit(): DjiFlightLimit

    suspend fun loadDeviceIdentity(): DjiDeviceIdentity?
}

class RealMsdkKeyValueClient : MsdkKeyValueClient {
    override fun isAircraftConnected(): Boolean {
        return KeyManager.getInstance().getValue(
            KeyTools.createKey(ProductKey.KeyConnection),
        ) == true
    }

    override fun loadCapability(): CameraCapability {
        val aircraftModelKey = normalizeAircraftModel(loadAircraftModel())
        val controllerModelKey = loadControllerModel()
        val payloads = listOf(0, 1, 2).mapNotNull { position ->
            val component = PayloadSelectionRegistry.componentForPosition(position) ?: return@mapNotNull null
            val cameraType = runCatching {
                KeyManager.getInstance().getValue(KeyTools.createKey(CameraKey.KeyCameraType, component))?.toString()
            }.getOrNull()
            val modelKey = normalizePayloadModel(cameraType) ?: return@mapNotNull null
            val sources = (runCatching {
                KeyManager.getInstance().getValue(
                    KeyTools.createKey(CameraKey.KeyCameraVideoStreamSourceRange, component),
                ) as? List<*>
            }.getOrNull()).orEmpty().mapNotNull { it?.toString() }
            val thermal = modelKey == "H20T" || modelKey == "H30T"
            PayloadCapability(
                payloadModelKey = modelKey,
                payloadPositionIndex = position,
                visibleSupported = sources.isEmpty() || sources.any { it != "INFRARED_CAMERA" },
                thermalSupported = thermal && (sources.isEmpty() || sources.any { it == "INFRARED_CAMERA" }),
                laserSupported = true,
                tapZoomSupported = true,
            )
        }
        if (aircraftModelKey != "M300" && payloads.isEmpty()) {
            val legacySources = (runCatching {
                KeyManager.getInstance().getValue(
                    KeyTools.createKey(
                        CameraKey.KeyCameraVideoStreamSourceRange,
                        ComponentIndexType.LEFT_OR_MAIN,
                    ),
                ) as? List<*>
            }.getOrNull()).orEmpty().mapNotNull { it?.toString() }
            val legacyCapability = CameraCapability(
                visibleSupported = legacySources.any { it != "INFRARED_CAMERA" },
                thermalSupported = legacySources.any { it == "INFRARED_CAMERA" },
                aircraftModelKey = aircraftModelKey,
                controllerModelKey = controllerModelKey,
                selectedPayloadPositionIndex = 0,
                laserSupported = true,
                fireClosedLoopReady = legacySources.isNotEmpty(),
            )
            PayloadSelectionRegistry.update(legacyCapability)
            return legacyCapability
        }
        val capability = PayloadCapabilityResolver.resolve(
            aircraftModelKey = aircraftModelKey,
            controllerModelKey = controllerModelKey,
            payloads = payloads,
            operatorSelectedPositionIndex = BuildConfig.AGENT_PAYLOAD_POSITION_INDEX.takeIf { it in 0..2 },
            m300FireClosedLoopEnabled = BuildConfig.M300_FIRE_CLOSED_LOOP_ENABLED,
        )
        PayloadSelectionRegistry.update(capability)
        return capability
    }

    override fun loadAircraftModel(): String? {
        return runCatching {
            KeyManager.getInstance().getValue(KeyTools.createKey(ProductKey.KeyProductType))?.toString()
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun loadControllerModel(): String? {
        val value = runCatching {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(RemoteControllerKey.KeyRemoteControllerType),
            )?.toString()
        }.getOrNull()?.uppercase() ?: return null
        return when (value) {
            "DJI_RC_PLUS" -> "RC_PLUS"
            "DJI_RC_PLUS_2" -> "RC_PLUS_2"
            else -> value.takeIf { it != "NONE" && it != "UNKNOWN" }
        }
    }

    private fun normalizeAircraftModel(value: String?): String? = when (
        value?.uppercase()?.replace("_", "")?.replace("-", "")
    ) {
        "M300RTK", "MATRICE300RTK" -> "M300"
        "DJIMATRICE4SERIES", "MATRICE4SERIES", "M4T" -> "M4T"
        else -> value?.takeIf { it.isNotBlank() }
    }

    private fun normalizePayloadModel(value: String?): String? {
        val normalized = value?.uppercase()?.replace("ZENMUSE_", "") ?: return null
        return when {
            normalized.contains("H30T") -> "H30T"
            normalized.contains("H30") -> "H30"
            normalized.contains("H20T") -> "H20T"
            normalized.contains("H20") -> "H20"
            else -> null
        }
    }

    override fun loadFlightLimit(): DjiFlightLimit {
        val heightLimit = runCatching {
            FlightControllerKey.KeyHeightLimit.create().get(0)
        }.getOrNull()?.takeIf { it > 0 }
        val distanceEnabled = runCatching {
            FlightControllerKey.KeyDistanceLimitEnabled.create().get(false)
        }.getOrNull()
        val distanceLimit = runCatching {
            FlightControllerKey.KeyDistanceLimit.create().get(0)
        }.getOrNull()?.takeIf { it > 0 }

        return DjiFlightLimit(
            heightLimitMeters = heightLimit,
            distanceLimitEnabled = distanceEnabled,
            distanceLimitMeters = distanceLimit,
        )
    }

    override suspend fun loadDeviceIdentity(): DjiDeviceIdentity? {
        refreshSerialNumberCache()
        val cachedAircraftSn = firstSerialNumber(
            { FlightControllerKey.KeySerialNumber.create().get("") },
            { FlightControllerKey.KeyComplianceSerialNumber.create().get("") },
            { FlightControllerKey.KeyEidProductId.create().get("") },
            { FlightControllerKey.KeyOnBoardSerialNumber.create().get("") },
            { KeyManager.getInstance().getValue(KeyTools.createKey(ProductKey.KeySerialNumber)) as? String },
        )
        val aircraftSn = cachedAircraftSn ?: forceGetProductSerialNumber()
        val gatewaySn = firstSerialNumber(
            { RemoteControllerKey.KeySerialNumber.create().get("") },
            { RemoteControllerKey.KeyProductSerialNumber.create().get("") },
            { android.os.Build.SERIAL },
        )
        val effectiveAircraftSn = aircraftSn ?: gatewaySn?.let { UNKNOWN_AIRCRAFT_PREFIX + sanitizeSerial(it) }
        println("MsdkKeyValueClient: identity aircraftSn=${effectiveAircraftSn ?: "(blank)"} gatewaySn=${gatewaySn ?: "(blank)"}")
        return if (effectiveAircraftSn.isNullOrBlank() || gatewaySn.isNullOrBlank()) {
            null
        } else {
            DjiDeviceIdentity(
                gatewaySn = gatewaySn,
                aircraftSn = effectiveAircraftSn,
            )
        }
    }

    private suspend fun refreshSerialNumberCache() {
        forceUpdateCache(KeyTools.createKey(FlightControllerKey.KeyForceUpdateCacheValue), "SerialNumber")
        forceUpdateCache(KeyTools.createKey(RemoteControllerKey.KeyForceUpdateCacheValue), "SerialNumber")
        forceUpdateCache(KeyTools.createKey(RemoteControllerKey.KeyForceUpdateCacheValue), "ProductSerialNumber")
    }

    private suspend fun forceUpdateCache(
        key: DJIKey.ActionKey<String, EmptyMsg>,
        field: String,
    ) {
        withTimeoutOrNull(MSDK_IDENTITY_ACTION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                KeyManager.getInstance().performAction(
                    key,
                    field,
                    object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                        override fun onSuccess(result: EmptyMsg?) {
                            continuation.takeIf { it.isActive }?.resume(Unit)
                        }

                        override fun onFailure(error: IDJIError) {
                            continuation.takeIf { it.isActive }?.resume(Unit)
                        }
                    },
                )
            }
        }
    }

    private suspend fun forceGetProductSerialNumber(): String? {
        return withTimeoutOrNull(MSDK_IDENTITY_ACTION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                KeyManager.getInstance().performAction(
                    KeyTools.createKey(ProductKey.KeyForceGetSerialNumber),
                    EmptyMsg(),
                    object : CommonCallbacks.CompletionCallbackWithParam<String> {
                        override fun onSuccess(result: String?) {
                            continuation.takeIf { it.isActive }?.resume(result?.trim()?.takeIf { it.isNotBlank() })
                        }

                        override fun onFailure(error: IDJIError) {
                            continuation.takeIf { it.isActive }?.resume(null)
                        }
                    },
                )
            }
        }
    }

    private fun firstSerialNumber(vararg suppliers: () -> String?): String? {
        return suppliers.firstNotNullOfOrNull { supplier ->
            runCatching { supplier()?.trim()?.takeIf { it.isNotBlank() } }.getOrNull()
        }
    }

    private fun sanitizeSerial(value: String): String {
        return value.trim().replace(Regex("[^A-Za-z0-9_-]"), "")
    }

    companion object {
        private const val MSDK_IDENTITY_ACTION_TIMEOUT_MS = 1_500L
        private const val UNKNOWN_AIRCRAFT_PREFIX = "UNKNOWN-AIRCRAFT-"
    }
}
