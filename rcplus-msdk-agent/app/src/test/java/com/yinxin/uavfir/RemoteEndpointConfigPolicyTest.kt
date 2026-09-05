package com.yinxin.uavfir

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEndpointConfigPolicyTest {
    @Test
    fun windowsLanBuild_usesFixedServerAddressAnd1088Model() {
        val properties = Properties().apply {
            File("../gradle.properties").inputStream().use(::load)
        }

        assertEquals("http://192.168.0.100:81/", properties.getProperty("agentBackendBaseUrl"))
        assertEquals("http://192.168.0.100:81/", properties.getProperty("agentAiServiceBaseUrl"))
        assertEquals("192.168.0.100", properties.getProperty("agentMediaHost"))
        assertEquals("8089", properties.getProperty("agentMediaRtmpPort"))
        assertEquals("tcp://192.168.0.100:1883", properties.getProperty("agentMqttBrokerUrl"))
        assertEquals("true", properties.getProperty("agentFireOnnxEnabled"))
        assertEquals("visible1088", properties.getProperty("agentFireModelProfile"))
    }

    @Test
    fun buildDefaults_matchWindowsLanProperties() {
        val buildScript = File("build.gradle.kts").readText()

        assertTrue(buildScript.contains("orElse(\"8089\")"))
        assertTrue(buildScript.contains("orElse(\"tcp://192.168.0.100:1883\")"))
        assertTrue(buildScript.contains("versionCode = 28"))
        assertTrue(buildScript.contains("versionName = \"0.1.27-trial\""))
        assertTrue(buildScript.contains("TRIAL_EXPIRES_AT_EPOCH_MS"))
    }
}
