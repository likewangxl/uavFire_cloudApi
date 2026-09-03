plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val djiMsdkVersion = providers.gradleProperty("djiMsdkVersion").orElse("5.18.0")
val djiApiKey = providers.gradleProperty("djiApiKey").orElse("")
val maplibreToken = providers.gradleProperty("maplibreToken").orElse("unused")
val agentBackendBaseUrl = providers.gradleProperty("agentBackendBaseUrl").orElse("http://192.168.1.10:6789/")
val agentAiServiceBaseUrl = providers.gradleProperty("agentAiServiceBaseUrl").orElse("http://192.168.1.10:9000/")
val agentMediaHost = providers.gradleProperty("agentMediaHost").orElse("192.168.1.10")
val agentMediaRtmpPort = providers.gradleProperty("agentMediaRtmpPort").orElse("1935")
val agentMediaStreamApp = providers.gradleProperty("agentMediaStreamApp").orElse("live")
val agentMqttBrokerUrl = providers.gradleProperty("agentMqttBrokerUrl").orElse("tcp://192.168.1.10:1883")
val agentMqttBrokerUsername = providers.gradleProperty("agentMqttBrokerUsername").orElse("")
val agentMqttBrokerPassword = providers.gradleProperty("agentMqttBrokerPassword").orElse("")
val agentWaylineSharedSecret = providers.gradleProperty("agentWaylineSharedSecret").orElse("")
val agentAircraftSn = providers.gradleProperty("agentAircraftSn").orElse("")
val agentGatewaySn = providers.gradleProperty("agentGatewaySn").orElse("")
val m300FireClosedLoopEnabled = providers.gradleProperty("m300FireClosedLoopEnabled").orElse("false")
val agentPayloadPositionIndex = providers.gradleProperty("agentPayloadPositionIndex").orElse("-1")
val agentFireOnnxEnabled = providers.gradleProperty("agentFireOnnxEnabled").orElse("false")
val agentFireModelProfile = providers.gradleProperty("agentFireModelProfile").orElse("legacy416")
val agentFireIntraOpThreads = providers.gradleProperty("agentFireIntraOpThreads").orElse("4")
val agentFireInterOpThreads = providers.gradleProperty("agentFireInterOpThreads").orElse("1")

android {
    namespace = "com.yinxin.uavfir"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
        viewBinding = true
        dataBinding = true
    }

    defaultConfig {
        applicationId = "com.yinxin.uavfir"
        minSdk = 26
        targetSdk = 34
        versionCode = 17
        versionName = "0.1.16"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["DJI_API_KEY"] = djiApiKey.get()
        manifestPlaceholders["MAPLIBRE_TOKEN"] = maplibreToken.get()
        buildConfigField("String", "AGENT_BACKEND_BASE_URL", "\"${agentBackendBaseUrl.get()}\"")
        buildConfigField("String", "AGENT_AI_SERVICE_BASE_URL", "\"${agentAiServiceBaseUrl.get()}\"")
        buildConfigField("String", "AGENT_MEDIA_HOST", "\"${agentMediaHost.get()}\"")
        buildConfigField("int", "AGENT_MEDIA_RTMP_PORT", agentMediaRtmpPort.get())
        buildConfigField("String", "AGENT_MEDIA_STREAM_APP", "\"${agentMediaStreamApp.get()}\"")
        buildConfigField("String", "AGENT_MQTT_BROKER_URL", "\"${agentMqttBrokerUrl.get()}\"")
        buildConfigField("String", "AGENT_MQTT_BROKER_USERNAME", "\"${agentMqttBrokerUsername.get()}\"")
        buildConfigField("String", "AGENT_MQTT_BROKER_PASSWORD", "\"${agentMqttBrokerPassword.get()}\"")
        buildConfigField("String", "AGENT_WAYLINE_SHARED_SECRET", "\"${agentWaylineSharedSecret.get()}\"")
        buildConfigField("String", "AGENT_AIRCRAFT_SN", "\"${agentAircraftSn.get()}\"")
        buildConfigField("String", "AGENT_GATEWAY_SN", "\"${agentGatewaySn.get()}\"")
        buildConfigField("boolean", "M300_FIRE_CLOSED_LOOP_ENABLED", m300FireClosedLoopEnabled.get())
        buildConfigField("int", "AGENT_PAYLOAD_POSITION_INDEX", agentPayloadPositionIndex.get())
        buildConfigField("boolean", "AGENT_FIRE_ONNX_ENABLED", agentFireOnnxEnabled.get())
        buildConfigField("String", "AGENT_FIRE_MODEL_PROFILE", "\"${agentFireModelProfile.get()}\"")
        buildConfigField("int", "AGENT_FIRE_INTRA_OP_THREADS", agentFireIntraOpThreads.get())
        buildConfigField("int", "AGENT_FIRE_INTER_OP_THREADS", agentFireInterOpThreads.get())
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("lib/arm64-v8a/libc++_shared.so")
            keepDebugSymbols += setOf(
                "lib/arm64-v8a/libconstants.so",
                "lib/arm64-v8a/libdjibase.so",
                "lib/arm64-v8a/libDJICSDKCommon.so",
                "lib/arm64-v8a/libDJIFlySafeCore-CSDK.so",
                "lib/arm64-v8a/libdjifs_jni-CSDK.so",
                "lib/arm64-v8a/libDJIRegister.so",
                "lib/arm64-v8a/libdjisdk_jni.so",
                "lib/arm64-v8a/libDJIUpgradeCore.so",
                "lib/arm64-v8a/libDJIUpgradeJNI.so",
                "lib/arm64-v8a/libDJIWaypointV2Core-CSDK.so",
                "lib/arm64-v8a/libdjiwpv2-CSDK.so",
                "lib/arm64-v8a/libFlightRecordEngine.so",
                "lib/arm64-v8a/libvideo-framing.so",
                "lib/arm64-v8a/libwaes.so",
                "lib/arm64-v8a/libagora-rtsa-sdk.so",
                "lib/arm64-v8a/libc++_shared.so",
                "lib/arm64-v8a/libmrtc_28181.so",
                "lib/arm64-v8a/libmrtc_agora.so",
                "lib/arm64-v8a/libmrtc_core.so",
                "lib/arm64-v8a/libmrtc_core_jni.so",
                "lib/arm64-v8a/libmrtc_data.so",
                "lib/arm64-v8a/libmrtc_log.so",
                "lib/arm64-v8a/libmrtc_onvif.so",
                "lib/arm64-v8a/libmrtc_rtmp.so",
                "lib/arm64-v8a/libmrtc_rtsp.so",
            )
        }
    }
}

dependencies {

    implementation(project(":uxsdk"))
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")
    implementation("io.reactivex.rxjava3:rxkotlin:3.0.1")
    implementation("org.dom4j:dom4j:2.1.4")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:${djiMsdkVersion.get()}")
    implementation("com.dji:dji-sdk-v5-aircraft:${djiMsdkVersion.get()}")
    implementation("com.dji:wpmzsdk:1.0.5.0")
    runtimeOnly("com.dji:dji-sdk-v5-networkImp:${djiMsdkVersion.get()}")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
