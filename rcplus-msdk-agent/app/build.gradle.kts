plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val djiMsdkVersion = providers.gradleProperty("djiMsdkVersion").orElse("5.17.0")
val djiApiKey = providers.gradleProperty("djiApiKey").orElse("")
val agentBackendBaseUrl = providers.gradleProperty("agentBackendBaseUrl").orElse("http://127.0.0.1:6789/")
val agentMediaHost = providers.gradleProperty("agentMediaHost").orElse("127.0.0.1")
val agentMediaRtmpPort = providers.gradleProperty("agentMediaRtmpPort").orElse("1935")
val agentMediaStreamApp = providers.gradleProperty("agentMediaStreamApp").orElse("live")

android {
    namespace = "com.yinxin.uavfir"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.yinxin.uavfir"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["DJI_API_KEY"] = djiApiKey.get()
        buildConfigField("String", "AGENT_BACKEND_BASE_URL", "\"${agentBackendBaseUrl.get()}\"")
        buildConfigField("String", "AGENT_MEDIA_HOST", "\"${agentMediaHost.get()}\"")
        buildConfigField("int", "AGENT_MEDIA_RTMP_PORT", agentMediaRtmpPort.get())
        buildConfigField("String", "AGENT_MEDIA_STREAM_APP", "\"${agentMediaStreamApp.get()}\"")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:${djiMsdkVersion.get()}")
    implementation("com.dji:dji-sdk-v5-aircraft:${djiMsdkVersion.get()}")
    runtimeOnly("com.dji:dji-sdk-v5-networkImp:${djiMsdkVersion.get()}")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
