package com.yinxin.uavfir.wayline

import android.util.Log
import com.google.gson.Gson
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
 * Connects lazily on first publish and reconnects on failure. Single instance
 * per agent (one drone).
 *
 * Threading: [connect] and [publishEvent] perform blocking network I/O — must
 * be called off the Android main thread (e.g. from a coroutine on Dispatchers.IO
 * or the existing AgentRuntimeLoop executor). On the main thread the Android
 * runtime will throw NetworkOnMainThreadException.
 */
class WaylineMqttPublisher(
    private val brokerUrl: String,
    private val droneSn: String,
    private val username: String?,
    private val password: String?,
    private val gson: Gson = Gson(),
) {
    private var client: MqttClient? = null

    @Synchronized
    fun connect() {
        if (client?.isConnected == true) return
        val c = MqttClient(brokerUrl, "wayline-agent-$droneSn", MemoryPersistence())
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "connection lost", cause)
            }
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                // publisher-only; not subscribing
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = false
            connectionTimeout = 10
            keepAliveInterval = 30
            username?.let { userName = it }
            password?.let { this.password = it.toCharArray() }
        }
        c.connect(opts)
        client = c
    }

    fun publishEvent(method: String, payload: Map<String, Any?>) {
        try {
            connect()
            val topic = "uavfire/agent/$droneSn/events/$method"
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

    @Synchronized
    fun disconnect() {
        runCatching { client?.disconnect() }
        client = null
    }

    companion object {
        private const val TAG = "WaylineMqttPublisher"
    }
}
