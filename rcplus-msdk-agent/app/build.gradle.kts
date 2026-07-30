import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val djiMsdkVersion = providers.gradleProperty("djiMsdkVersion").orElse("5.18.0")
val djiApiKey = providers.gradleProperty("djiApiKey").orElse("")
val maplibreToken = providers.gradleProperty("maplibreToken").orElse("unused")
val agentBackendBaseUrl = providers.gradleProperty("agentBackendBaseUrl").orElse("http://127.0.0.1:6789/")
val agentSnapshotServiceBaseUrl = providers.gradleProperty("agentSnapshotServiceBaseUrl").orElse("http://127.0.0.1:9000/")
val agentMediaHost = providers.gradleProperty("agentMediaHost").orElse("127.0.0.1")
val agentMediaRtmpPort = providers.gradleProperty("agentMediaRtmpPort").orElse("1935")
val agentMediaStreamApp = providers.gradleProperty("agentMediaStreamApp").orElse("live")
val agentMqttBrokerUrl = providers.gradleProperty("agentMqttBrokerUrl").orElse("tcp://172.20.10.7:1883")
val agentMqttBrokerUsername = providers.gradleProperty("agentMqttBrokerUsername").orElse("")
val agentMqttBrokerPassword = providers.gradleProperty("agentMqttBrokerPassword").orElse("")
val agentWaylineSharedSecret = providers.gradleProperty("agentWaylineSharedSecret").orElse("change-me-in-production")
val agentAircraftSn = providers.gradleProperty("agentAircraftSn").orElse("")
val agentGatewaySn = providers.gradleProperty("agentGatewaySn").orElse("")
val realUxsdkBuild = project(":uxsdk").projectDir.canonicalFile != rootProject.file("uxsdk-stub").canonicalFile
val agentVersionCode = 3
val agentVersionName = "0.1.2"
val agentBuildId = "uavfire-agent-$agentVersionName-$agentVersionCode"
val ncnnVersion = "20260526"
val ncnnArchiveSha256 = "eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1"
fun sha256File(file: java.io.File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
val visibleFireNcnnBridgeSources = listOf(
    layout.projectDirectory.file("src/main/cpp/CMakeLists.txt").asFile,
    layout.projectDirectory.file("src/main/cpp/visible_fire_ncnn_bridge.cpp").asFile,
)
val visibleFireNcnnBridgeSourceSha256 = MessageDigest.getInstance("SHA-256")
    .digest(
        visibleFireNcnnBridgeSources
            .sortedBy { it.name }
            .joinToString("\n") { "${it.name}:${sha256File(it)}" }
            .toByteArray(),
    )
    .joinToString("") { "%02x".format(it) }
val uxsdkSourceSha256 = if (realUxsdkBuild) {
    val sourceFiles = project(":uxsdk").projectDir.walkTopDown()
        .filter { it.isFile && "build" !in it.toPath().map { part -> part.toString() } }
        .sortedBy { it.relativeTo(project(":uxsdk").projectDir).invariantSeparatorsPath }
        .toList()
    val digest = MessageDigest.getInstance("SHA-256")
    sourceFiles.forEach { file ->
        digest.update(file.relativeTo(project(":uxsdk").projectDir).invariantSeparatorsPath.toByteArray())
        digest.update(0)
        digest.update(file.readBytes())
    }
    digest.digest().joinToString("") { "%02x".format(it) }
} else {
    ""
}

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
        versionCode = agentVersionCode
        versionName = agentVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["DJI_API_KEY"] = djiApiKey.get()
        manifestPlaceholders["MAPLIBRE_TOKEN"] = maplibreToken.get()
        manifestPlaceholders["UAVFIRE_REAL_UXSDK"] = realUxsdkBuild.toString()
        manifestPlaceholders["UAVFIRE_AGENT_HEALTH_CONTRACT"] = "agent-process-v1"
        buildConfigField("String", "AGENT_BUILD_ID", "\"$agentBuildId\"")
        buildConfigField("String", "UXSDK_SOURCE_SHA256", "\"$uxsdkSourceSha256\"")
        buildConfigField("String", "AGENT_BACKEND_BASE_URL", "\"${agentBackendBaseUrl.get()}\"")
        buildConfigField("String", "AGENT_SNAPSHOT_SERVICE_BASE_URL", "\"${agentSnapshotServiceBaseUrl.get()}\"")
        buildConfigField("boolean", "VISIBLE_FIRE_DETECTION_ENABLED", "false")
        buildConfigField("String", "VISIBLE_FIRE_NCNN_VERSION", "\"$ncnnVersion\"")
        buildConfigField("String", "VISIBLE_FIRE_NCNN_ARCHIVE_SHA256", "\"$ncnnArchiveSha256\"")
        buildConfigField("String", "VISIBLE_FIRE_NCNN_BRIDGE_SOURCE_SHA256", "\"$visibleFireNcnnBridgeSourceSha256\"")
        buildConfigField("String", "AGENT_MEDIA_HOST", "\"${agentMediaHost.get()}\"")
        buildConfigField("int", "AGENT_MEDIA_RTMP_PORT", agentMediaRtmpPort.get())
        buildConfigField("String", "AGENT_MEDIA_STREAM_APP", "\"${agentMediaStreamApp.get()}\"")
        buildConfigField("String", "AGENT_MQTT_BROKER_URL", "\"${agentMqttBrokerUrl.get()}\"")
        buildConfigField("String", "AGENT_MQTT_BROKER_USERNAME", "\"${agentMqttBrokerUsername.get()}\"")
        buildConfigField("String", "AGENT_MQTT_BROKER_PASSWORD", "\"${agentMqttBrokerPassword.get()}\"")
        buildConfigField("String", "AGENT_WAYLINE_SHARED_SECRET", "\"${agentWaylineSharedSecret.get()}\"")
        buildConfigField("String", "AGENT_AIRCRAFT_SN", "\"${agentAircraftSn.get()}\"")
        buildConfigField("String", "AGENT_GATEWAY_SN", "\"${agentGatewaySn.get()}\"")
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

    aaptOptions {
        noCompress += setOf("bin", "param")
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

val ncnnArchive = providers.gradleProperty("ncnnArchive")
    .map(::file)
    .orElse(
        provider {
            rootProject.file(
                "fire-detector-benchmark/build/toolchains/ncnn-$ncnnVersion-android-vulkan-shared.zip",
            )
        },
    )
val ncnnAndroidNdkDir = providers.gradleProperty("ncnnAndroidNdkDir")
    .orElse(providers.environmentVariable("ANDROID_NDK_HOME"))
    .map(::file)
val ncnnCmakeExecutable = providers.gradleProperty("ncnnCmakeExecutable").orElse("cmake")
val ncnnExtractOutputDir = layout.buildDirectory.dir("generated/visibleFireNcnnPackage")
val ncnnBridgeBuildOutputDir = layout.buildDirectory.dir("generated/visibleFireNcnnBridge")
val ncnnJniOutputDir = layout.buildDirectory.dir("generated/visibleFireNcnnJni")
val ncnnTrustOutput = layout.buildDirectory.file(
    "generated/visibleFireNcnnTrust/fire-detection/ncnn-runtime-trust.json",
)

val buildVisibleFireNcnnFromPinnedArchive = tasks.register("buildVisibleFireNcnnFromPinnedArchive") {
    inputs.files(visibleFireNcnnBridgeSources)
    inputs.file(ncnnArchive)
    inputs.property("ncnnVersion", ncnnVersion)
    inputs.property("ncnnArchiveSha256", ncnnArchiveSha256)
    outputs.dir(ncnnExtractOutputDir)
    outputs.dir(ncnnBridgeBuildOutputDir)
    outputs.file(ncnnTrustOutput)
    doLast {
        val extracted = ncnnExtractOutputDir.get().asFile
        val bridgeBuild = ncnnBridgeBuildOutputDir.get().asFile
        val trustFile = ncnnTrustOutput.get().asFile
        listOf(extracted, bridgeBuild).forEach { directory ->
            check(directory.deleteRecursively() || !directory.exists()) {
                "Cannot clear stale NCNN build directory $directory"
            }
        }
        check(trustFile.delete() || !trustFile.exists()) { "Cannot clear stale NCNN runtime trust" }
        check(ncnnAndroidNdkDir.isPresent) {
            "Agent NCNN packaging requires -PncnnAndroidNdkDir=<Android NDK> or ANDROID_NDK_HOME"
        }
        val archive = ncnnArchive.get()
        check(archive.isFile) {
            "Pinned Task 2 NCNN archive is missing: $archive; provision it with -PncnnArchive"
        }
        check(sha256File(archive) == ncnnArchiveSha256) {
            "NCNN archive SHA-256 does not match the committed $ncnnVersion lock"
        }
        copy {
            from(zipTree(archive))
            into(extracted)
        }
        val packageRoot = extracted.resolve("ncnn-$ncnnVersion-android-vulkan-shared/arm64-v8a")
        val ncnnConfig = packageRoot.resolve("lib/cmake/ncnn/ncnnConfig.cmake")
        val runtime = packageRoot.resolve("lib/libncnn.so")
        check(ncnnConfig.isFile && runtime.isFile) {
            "Pinned NCNN archive lacks the arm64-v8a Vulkan CMake package/runtime"
        }
        val toolchain = ncnnAndroidNdkDir.get().resolve("build/cmake/android.toolchain.cmake")
        check(toolchain.isFile) { "Android NDK CMake toolchain is missing: $toolchain" }

        val configureOutput = ByteArrayOutputStream()
        val configure = project.exec {
            commandLine(
                ncnnCmakeExecutable.get(),
                "-S", layout.projectDirectory.dir("src/main/cpp").asFile,
                "-B", bridgeBuild,
                "-DANDROID_ABI=arm64-v8a",
                "-DANDROID_PLATFORM=android-26",
                "-DANDROID_STL=c++_shared",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
                "-Dncnn_DIR=${ncnnConfig.parentFile}",
            )
            standardOutput = configureOutput
            errorOutput = configureOutput
            isIgnoreExitValue = true
        }
        check(configure.exitValue == 0) {
            "Visible NCNN bridge configure failed: ${configureOutput.toString(Charsets.UTF_8)}"
        }
        val buildOutput = ByteArrayOutputStream()
        val build = project.exec {
            commandLine(
                ncnnCmakeExecutable.get(),
                "--build", bridgeBuild,
                "--config", "Release",
            )
            standardOutput = buildOutput
            errorOutput = buildOutput
            isIgnoreExitValue = true
        }
        check(build.exitValue == 0) {
            "Visible NCNN bridge build failed: ${buildOutput.toString(Charsets.UTF_8)}"
        }
        val bridge = bridgeBuild.resolve("libvisible_fire_ncnn.so")
        check(bridge.isFile) { "Current-source visible NCNN JNI bridge was not produced" }
        trustFile.parentFile.mkdirs()
        trustFile.writeText(
            """{"schemaVersion":1,"version":"$ncnnVersion","packageArchiveSha256":"$ncnnArchiveSha256","bridgeSourceSha256":"$visibleFireNcnnBridgeSourceSha256","runtimeSha256":"${sha256File(runtime)}","bridgeSha256":"${sha256File(bridge)}"}""",
        )
    }
}

val stageVisibleFireNcnnRuntime = tasks.register("stageVisibleFireNcnnRuntime") {
    dependsOn(buildVisibleFireNcnnFromPinnedArchive)
    outputs.dir(ncnnJniOutputDir)
    doLast {
        val outputDirectory = ncnnJniOutputDir.get().asFile
        check(outputDirectory.deleteRecursively() || !outputDirectory.exists()) {
            "Cannot clear staged visible NCNN JNI libraries"
        }
        val packageRoot = ncnnExtractOutputDir.get().asFile
            .resolve("ncnn-$ncnnVersion-android-vulkan-shared/arm64-v8a")
        val runtime = packageRoot.resolve("lib/libncnn.so")
        val bridge = ncnnBridgeBuildOutputDir.get().asFile.resolve("libvisible_fire_ncnn.so")
        check(runtime.isFile && bridge.isFile && ncnnTrustOutput.get().asFile.isFile) {
            "Pinned NCNN runtime/current-source bridge identity is incomplete"
        }
        copy {
            from(runtime)
            from(bridge)
            into(outputDirectory.resolve("arm64-v8a"))
        }
    }
}

android.sourceSets.getByName("main").jniLibs.srcDir(ncnnJniOutputDir)
android.sourceSets.getByName("main").assets.srcDir(
    layout.buildDirectory.dir("generated/visibleFireNcnnTrust"),
)
tasks.matching { it.name.matches(Regex("merge(Debug|Release)JniLibFolders")) }.configureEach {
    dependsOn(stageVisibleFireNcnnRuntime)
}
tasks.matching { it.name.matches(Regex("merge(Debug|Release)Assets")) }.configureEach {
    dependsOn(buildVisibleFireNcnnFromPinnedArchive)
}
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn("packageDebug")
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
