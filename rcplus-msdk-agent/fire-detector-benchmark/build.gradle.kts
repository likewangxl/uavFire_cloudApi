import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val measurementCandidate = providers.gradleProperty("fireDetectorCandidate").orElse("benchmark").get()
val supportedMeasurementCandidates = setOf("baseline", "onnx", "tflite", "ncnn", "benchmark")
check(measurementCandidate in supportedMeasurementCandidates) { "Unknown fireDetectorCandidate: $measurementCandidate" }

android {
    namespace = "com.yinxin.uavfir.benchmark"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yinxin.uavfir.benchmark"
        minSdk = 26
        targetSdk = 34
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    aaptOptions {
        noCompress += setOf("onnx", "tflite", "bin", "param")
    }
}

val stageBenchmarkAssets by tasks.registering(Sync::class) {
    from(layout.projectDirectory.dir("../../ai-service/mobile-model")) {
        include("model-candidates.json")
        include("benchmark-set/manifest.json")
        include("benchmark-set/pytorch-baseline.json")
        include("benchmark-set/images/**")
        if (measurementCandidate == "benchmark" || measurementCandidate == "onnx") include("thermal-fire-yolov8n-640-gt-20260709.onnx")
        if (measurementCandidate == "benchmark" || measurementCandidate == "tflite") include("thermal-fire-yolov8n-640-gt-20260709_float32.tflite")
        if (measurementCandidate == "benchmark" || measurementCandidate == "ncnn") {
            include("thermal-fire-yolov8n-640-gt-20260709_ncnn_model/model.ncnn.bin")
            include("thermal-fire-yolov8n-640-gt-20260709_ncnn_model/model.ncnn.param")
            include("thermal-fire-yolov8n-640-gt-20260709_ncnn_model/metadata.yaml")
            include("thermal-fire-yolov8n-640-gt-20260709_ncnn_model/model_ncnn.py")
        }
    }
    into(layout.buildDirectory.dir("generated/benchmarkAssets"))
}

android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/benchmarkAssets"))
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/apkDeltaMetadata"))
tasks.named("preBuild").configure { dependsOn(stageBenchmarkAssets) }

val measurementApkDir = layout.buildDirectory.dir("apk-delta-input")
tasks.register<Copy>("captureCandidateMeasurementApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/fire-detector-benchmark-debug.apk"))
    into(measurementApkDir)
    rename { "$measurementCandidate-arm64.apk" }
}

val ncnnSdkDir = providers.gradleProperty("ncnnSdkDir")
val ncnnBridgeDir = providers.gradleProperty("ncnnBridgeDir")
val stageNcnnRuntime = tasks.register<Sync>("stageNcnnRuntime") {
    onlyIf { measurementCandidate == "ncnn" }
    doFirst {
        check(ncnnSdkDir.isPresent && ncnnBridgeDir.isPresent) {
            "NCNN measurement requires -PncnnSdkDir and -PncnnBridgeDir; see fire-detector-benchmark/README.md"
        }
        check(file(ncnnSdkDir.get()).resolve("include/net.h").isFile) { "NCNN SDK include/net.h is missing" }
        check(file(ncnnSdkDir.get()).resolve("lib/arm64-v8a/libncnn.so").isFile) { "NCNN SDK arm64 library is missing" }
        check(file(ncnnBridgeDir.get()).resolve("libfire_detector_ncnn.so").isFile) { "Built NCNN JNI bridge is missing" }
    }
    from(ncnnSdkDir.map { file(it).resolve("lib/arm64-v8a/libncnn.so") })
    from(ncnnBridgeDir.map { file(it).resolve("libfire_detector_ncnn.so") })
    into(layout.buildDirectory.dir("generated/ncnnJni/arm64-v8a"))
}
android.sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/ncnnJni"))
tasks.named("preBuild").configure { dependsOn(stageNcnnRuntime) }

val writeApkDeltaMetadata = tasks.register("writeApkDeltaMetadata") {
    val inputDirectory = measurementApkDir.get().asFile
    inputs.dir(inputDirectory)
    outputs.file(layout.buildDirectory.file("generated/apkDeltaMetadata/apk-delta.json"))
    doLast {
        fun apk(name: String) = inputDirectory.resolve("$name-arm64.apk").also { check(it.isFile) { "Missing $name arm64 measurement APK; run captureCandidateMeasurementApk with -PfireDetectorCandidate=$name" } }
        fun verifyArm64(apk: java.io.File, requiresRuntime: Boolean) {
            ZipFile(apk).use { zip ->
                val nativeEntries = zip.entries().asSequence().filter { it.name.startsWith("lib/") }.toList()
                check(nativeEntries.none { !it.name.startsWith("lib/arm64-v8a/") }) { "$apk is not arm64-only" }
                if (requiresRuntime) check(nativeEntries.isNotEmpty()) { "$apk contains no candidate runtime native code" }
            }
        }
        val baseline = apk("baseline").length()
        verifyArm64(apk("baseline"), requiresRuntime = false)
        val candidates = listOf("onnx", "tflite", "ncnn").associateWith { name ->
            val file = apk(name)
            verifyArm64(file, requiresRuntime = true)
            file.length()
        }
        check(candidates.values.all { it >= baseline }) { "Candidate APK must not be smaller than the runtime-free baseline" }
        val json = buildString {
            append("{\n  \"abi\": \"arm64-v8a\",\n  \"baselineApkBytes\": $baseline,\n  \"candidates\": {\n")
            append(candidates.entries.joinToString(",\n") { (name, size) -> "    \"$name\": {\"apkBytes\": $size, \"apkDeltaBytes\": ${size - baseline}}" })
            append("\n  }\n}\n")
        }
        val output = layout.buildDirectory.file("generated/apkDeltaMetadata/apk-delta.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(json)
    }
}

dependencies {
    // These native runtimes are intentionally isolated to this non-production APK.
    compileOnly("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    compileOnly("org.tensorflow:tensorflow-lite:2.16.1")
    if (measurementCandidate == "benchmark" || measurementCandidate == "onnx") implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    if (measurementCandidate == "benchmark" || measurementCandidate == "tflite") implementation("org.tensorflow:tensorflow-lite:2.16.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
