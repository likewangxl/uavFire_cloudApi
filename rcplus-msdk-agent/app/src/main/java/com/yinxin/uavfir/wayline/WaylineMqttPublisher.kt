package com.yinxin.uavfir.wayline

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Publishes wayline mission events to the backend MQTT broker on topics
 * `uavfire/agent/{droneSn}/events/{method}`.
 *
 * Connects lazily on first publish and reconnects on failure.
 *
 * Threading: [connect] and [publishEvent] perform blocking network I/O — must
 * be called off the Android main thread (e.g. from a coroutine on
 * Dispatchers.IO). On the main thread the Android runtime will throw
 * NetworkOnMainThreadException.
 */
class WaylineMqttPublisher(
    private val brokerUrl: String,
    private val clientIdPrefix: String,
    private val username: String?,
    private val password: String?,
    private val gson: Gson = GsonBuilder().serializeNulls().create(),
) {
    private var client: MqttClient? = null

    /**
     * Default droneSn used when callers don't supply one. Tracked externally
     * (typically set when MSDK reports the connected aircraft SN) so the
     * publisher can be wired before the SN is known.
     */
    @Volatile
    private var defaultDroneSn: String? = null

    fun setDefaultDroneSn(droneSn: String?) {
        defaultDroneSn = droneSn
    }

    @Synchronized
    fun connect() {
        if (client?.isConnected == true) return
        val c = MqttClient(brokerUrl, "${clientIdPrefix}-${java.util.UUID.randomUUID()}", MemoryPersistence())
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "connection lost", cause)
            }
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                // publisher-only; not subscribing
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val brokerUser = username
        val brokerPassword = password
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = false
            connectionTimeout = 10
            keepAliveInterval = 30
            brokerUser?.takeIf { it.isNotEmpty() }?.let { userName = it }
            brokerPassword?.takeIf { it.isNotEmpty() }?.let { this.password = it.toCharArray() }
        }
        c.connect(opts)
        client = c
    }

    fun publishEvent(method: String, payload: Map<String, Any?>, droneSn: String? = null) {
        val effectiveSn = droneSn ?: defaultDroneSn
        if (effectiveSn == null) {
            Log.w(TAG, "publishEvent skipped: no droneSn available (method=$method)")
            return
        }
        try {
            connect()
            val topic = "uavfire/agent/$effectiveSn/events/$method"
            val envelope = mapOf(
                "tid" to (payload["tid"] ?: java.util.UUID.randomUUID().toString()),
                "method" to method,
                "timestamp" to System.currentTimeMillis(),
                "data" to payload,
            )
            val bytes = gson.toJson(envelope).toByteArray()
            val msg = MqttMessage(bytes).apply { qos = 1 }
            client?.publish(topic, msg)
        } catch (e: MqttException) {
            Log.w(TAG, "publish failed method=$method", e)
        }
    }

    /**
     * Publish an OSD payload to the Cloud SDK standard topic
     * `thing/product/{aircraftSn}/osd`. This impersonates the Pilot 2 ↔ Cloud SDK
     * protocol so the unchanged backend can consume it. See OsdReporter.
     */
    fun publishCloudOsd(aircraftSn: String, gatewaySn: String, data: Map<String, Any?>) {
        try {
            connect()
            val topic = "thing/product/$aircraftSn/osd"
            val envelope = buildCloudOsdEnvelope(
                gatewaySn = gatewaySn,
                timestamp = System.currentTimeMillis(),
                bid = java.util.UUID.randomUUID().toString(),
                tid = java.util.UUID.randomUUID().toString(),
                data = data,
            )
            val bytes = gson.toJson(envelope).toByteArray()
            // QoS 0 — OSD is high-frequency telemetry; loss tolerance is fine.
            val msg = MqttMessage(bytes).apply { qos = 0 }
            client?.publish(topic, msg)
        } catch (e: MqttException) {
            Log.w(TAG, "publishCloudOsd failed sn=$aircraftSn", e)
        }
    }

    /**
     * Publish the Cloud SDK `update_topo` device-online announcement on
     * `sys/product/{gatewaySn}/status`. Backend only subscribes per-device OSD
     * topics after updateTopoOnline processes this message; the Redis online:*
     * keys it creates expire when the backend restarts or the RC powers off, so
     * OsdReporter re-sends it periodically to keep the subscription self-healing.
     */
    fun publishUpdateTopo(aircraftSn: String, gatewaySn: String) {
        try {
            connect()
            val topic = "sys/product/$gatewaySn/status"
            val envelope = mapOf(
                "bid" to java.util.UUID.randomUUID().toString(),
                "tid" to java.util.UUID.randomUUID().toString(),
                "timestamp" to System.currentTimeMillis(),
                "method" to "update_topo",
                "data" to mapOf(
                    "domain" to GATEWAY_DOMAIN_RC,
                    "type" to GATEWAY_TYPE_RC_PLUS_2,
                    "sub_type" to GATEWAY_SUB_TYPE_ZERO,
                    "device_secret" to "",
                    "nonce" to "",
                    "thing_version" to THING_VERSION,
                    "sub_devices" to listOf(
                        mapOf(
                            "sn" to aircraftSn,
                            "domain" to DRONE_DOMAIN,
                            "type" to DRONE_TYPE_M4_SERIES,
                            "sub_type" to DRONE_SUB_TYPE_M4T,
                            "index" to "A",
                            "device_secret" to "",
                            "nonce" to "",
                            "thing_version" to THING_VERSION,
                        )
                    ),
                ),
            )
            val bytes = gson.toJson(envelope).toByteArray()
            val msg = MqttMessage(bytes).apply { qos = 1 }
            client?.publish(topic, msg)
            Log.i(TAG, "published update_topo gateway=$gatewaySn aircraft=$aircraftSn")
        } catch (e: MqttException) {
            Log.w(TAG, "publishUpdateTopo failed gateway=$gatewaySn", e)
        }
    }

    /**
     * Publish an events payload (method=hms or others) to the Cloud SDK standard
     * topic `thing/product/{aircraftSn}/events`. See HmsReporter.
     */
    fun publishCloudEvent(aircraftSn: String, gatewaySn: String, method: String, data: Map<String, Any?>) {
        try {
            connect()
            val topic = "thing/product/$aircraftSn/events"
            val envelope = buildCloudEventEnvelope(
                gatewaySn = gatewaySn,
                method = method,
                timestamp = System.currentTimeMillis(),
                bid = java.util.UUID.randomUUID().toString(),
                tid = java.util.UUID.randomUUID().toString(),
                data = data,
            )
            val bytes = gson.toJson(envelope).toByteArray()
            val msg = MqttMessage(bytes).apply { qos = 1 }
            client?.publish(topic, msg)
        } catch (e: MqttException) {
            Log.w(TAG, "publishCloudEvent failed sn=$aircraftSn method=$method", e)
        }
    }

    @Synchronized
    fun disconnect() {
        runCatching { client?.disconnect() }
        client = null
    }

    companion object {
        private const val TAG = "WaylineMqttPublisher"

        // update_topo 设备注册三元组：沿用后端 manage_device 已验证的注册参数
        // （RC Plus 2 网关 2/174/0 + M4T runtime 0/99/1，thing_version 1.2.0）。
        private const val GATEWAY_DOMAIN_RC = 2
        private const val GATEWAY_TYPE_RC_PLUS_2 = 174
        private const val GATEWAY_SUB_TYPE_ZERO = 0
        private const val DRONE_DOMAIN = 0
        private const val DRONE_TYPE_M4_SERIES = 99
        private const val DRONE_SUB_TYPE_M4T = 1
        private const val THING_VERSION = "1.2.0"

        fun buildCloudOsdEnvelope(
            gatewaySn: String,
            timestamp: Long,
            bid: String,
            tid: String,
            data: Map<String, Any?>,
        ): Map<String, Any?> = mapOf(
            "bid" to bid,
            "tid" to tid,
            "timestamp" to timestamp,
            "gateway" to gatewaySn,
            "data" to data,
        )

        fun buildCloudEventEnvelope(
            gatewaySn: String,
            method: String,
            timestamp: Long,
            bid: String,
            tid: String,
            data: Map<String, Any?>,
        ): Map<String, Any?> = mapOf(
            "bid" to bid,
            "tid" to tid,
            "timestamp" to timestamp,
            "gateway" to gatewaySn,
            "method" to method,
            "data" to data,
            "need_reply" to 0,
        )
    }
}
