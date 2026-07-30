import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

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
    from(layout.projectDirectory.dir("../../ai-service/mobile-model/visible-960")) {
        include("model-candidates.json")
        include("benchmark-set/manifest.json")
        include("benchmark-set/pytorch-baseline.json")
        include("benchmark-set/images/**")
        if (measurementCandidate == "benchmark" || measurementCandidate == "onnx") include("visible-fire-wechat-best2-20260728.onnx")
        if (measurementCandidate == "benchmark" || measurementCandidate == "tflite") include("visible-fire-wechat-best2-20260728_float32.tflite")
        if (measurementCandidate == "benchmark" || measurementCandidate == "ncnn") {
            include("visible-fire-wechat-best2-20260728_ncnn_model/model.ncnn.bin")
            include("visible-fire-wechat-best2-20260728_ncnn_model/model.ncnn.param")
            include("visible-fire-wechat-best2-20260728_ncnn_model/metadata.yaml")
            include("visible-fire-wechat-best2-20260728_ncnn_model/model_ncnn.py")
        }
    }
    into(layout.buildDirectory.dir("generated/benchmarkAssets"))
}

val visibleBenchmarkRoot = layout.projectDirectory.dir("../../ai-service/mobile-model/visible-960/benchmark-set")
val verifyVisibleBenchmarkInputs by tasks.registering {
    doLast {
        val root = visibleBenchmarkRoot.asFile
        check(root.resolve("manifest.json").isFile) {
            "Missing visible-960 benchmark manifest: ${root.resolve("manifest.json")}"
        }
        check(root.resolve("pytorch-baseline.json").isFile) {
            "Missing visible-960 PyTorch baseline: ${root.resolve("pytorch-baseline.json")}"
        }
        val images = root.resolve("images").walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "bmp", "webp") }
            .count()
        check(images == 400) { "Expected exactly 400 visible benchmark images; found $images in ${root.resolve("images")}" }
    }
}

android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/benchmarkAssets"))
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/apkDeltaMetadata"))
tasks.named("preBuild").configure { dependsOn(stageBenchmarkAssets) }
tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
    dependsOn(verifyVisibleBenchmarkInputs)
}

val measurementApkDir = layout.buildDirectory.dir("apk-delta-input")
val apkDeltaFixtureDir = layout.buildDirectory.dir("apk-delta-fixture")
val repositoryModelManifest = layout.projectDirectory.file("../../ai-service/mobile-model/visible-960/model-candidates.json")
val expectedCandidateModels = mapOf(
    "onnx" to listOf("visible-fire-wechat-best2-20260728.onnx"),
    "tflite" to listOf("visible-fire-wechat-best2-20260728_float32.tflite"),
    "ncnn" to listOf("visible-fire-wechat-best2-20260728_ncnn_model/metadata.yaml", "visible-fire-wechat-best2-20260728_ncnn_model/model.ncnn.bin", "visible-fire-wechat-best2-20260728_ncnn_model/model.ncnn.param", "visible-fire-wechat-best2-20260728_ncnn_model/model_ncnn.py"),
)
val expectedCandidateRuntimes = mapOf(
    "onnx" to listOf("lib/arm64-v8a/libonnxruntime4j_jni.so"),
    "tflite" to listOf("lib/arm64-v8a/libtensorflowlite_jni.so"),
    "ncnn" to listOf("lib/arm64-v8a/libncnn.so", "lib/arm64-v8a/libfire_detector_ncnn.so"),
)
tasks.register<Copy>("captureCandidateMeasurementApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/fire-detector-benchmark-debug.apk"))
    into(measurementApkDir)
    rename { "$measurementCandidate-arm64.apk" }
}

val ncnnPackageDir = providers.gradleProperty("ncnnPackageDir")
val ncnnRuntimeLibrary = providers.gradleProperty("ncnnRuntimeLibrary")
val ncnnBridgeDir = providers.gradleProperty("ncnnBridgeDir")
val requiresNcnnRuntime = measurementCandidate in setOf("ncnn", "benchmark")
val ncnnIsProvisioned = ncnnPackageDir.isPresent && ncnnRuntimeLibrary.isPresent && ncnnBridgeDir.isPresent
val ncnnJniOutputDir = layout.buildDirectory.dir("generated/ncnnJni")
val stageNcnnRuntime = tasks.register("stageNcnnRuntime") {
    outputs.dir(ncnnJniOutputDir)
    // Always execute so a baseline/ONNX/TFLite build removes JNI files left by an NCNN build.
    outputs.upToDateWhen { false }
    doLast {
        val outputDirectory = ncnnJniOutputDir.get().asFile
        check(outputDirectory.deleteRecursively() || !outputDirectory.exists()) { "Cannot clear staged NCNN JNI libraries" }
        if (!requiresNcnnRuntime) return@doLast
        check(ncnnIsProvisioned) {
            "NCNN packaging requires -PncnnPackageDir, -PncnnRuntimeLibrary, and -PncnnBridgeDir; see fire-detector-benchmark/README.md"
        }
        val ncnnConfig = file(ncnnPackageDir.get()).resolve("ncnnConfig.cmake")
        check(ncnnConfig.isFile) { "Official ncnnConfig.cmake package is missing" }
        check(file(ncnnRuntimeLibrary.get()).isFile) { "Official NCNN arm64 runtime library is missing" }
        check(file(ncnnBridgeDir.get()).resolve("libfire_detector_ncnn.so").isFile) { "Built NCNN JNI bridge is missing" }
        copy {
            from(file(ncnnRuntimeLibrary.get()))
            from(file(ncnnBridgeDir.get()).resolve("libfire_detector_ncnn.so"))
            into(outputDirectory.resolve("arm64-v8a"))
        }
    }
}
android.sourceSets.getByName("main").jniLibs.srcDir(ncnnJniOutputDir)
tasks.named("preBuild").configure { dependsOn(stageNcnnRuntime) }

val fixtureVerificationRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') == "verifyWriteApkDeltaMetadataFixture"
}
val apkDeltaInputDirectory = providers.gradleProperty("apkDeltaInputDir")
    .map(::file)
    .orElse(if (fixtureVerificationRequested) apkDeltaFixtureDir.map { it.asFile } else measurementApkDir.map { it.asFile })

val createApkDeltaMetadataFixture = tasks.register("createApkDeltaMetadataFixture") {
    inputs.file(repositoryModelManifest)
    outputs.dir(apkDeltaFixtureDir)
    doLast {
        val fixtureDirectory = apkDeltaFixtureDir.get().asFile
        check(fixtureDirectory.deleteRecursively() || !fixtureDirectory.exists()) { "Cannot clear APK delta fixture directory" }
        check(fixtureDirectory.mkdirs()) { "Cannot create APK delta fixture directory" }
        val manifestBytes = repositoryModelManifest.asFile.readBytes()

        fun writeFixtureApk(name: String, entries: Map<String, ByteArray>) {
            ZipOutputStream(fixtureDirectory.resolve("$name-arm64.apk").outputStream().buffered()).use { zip ->
                entries.forEach { (path, contents) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(contents)
                    zip.closeEntry()
                }
            }
        }

        writeFixtureApk("baseline", mapOf("assets/model-candidates.json" to manifestBytes))
        expectedCandidateModels.keys.forEach { candidate ->
            val entries = linkedMapOf("assets/model-candidates.json" to manifestBytes)
            expectedCandidateRuntimes.getValue(candidate).forEach { path -> entries[path] = "fixture runtime $path".toByteArray() }
            expectedCandidateModels.getValue(candidate).forEach { path -> entries["assets/$path"] = "fixture model $path".toByteArray() }
            writeFixtureApk(candidate, entries)
        }
    }
}

val writeApkDeltaMetadata = tasks.register("writeApkDeltaMetadata") {
    val inputDirectory = apkDeltaInputDirectory.get()
    inputs.dir(inputDirectory)
    inputs.file(repositoryModelManifest)
    outputs.file(layout.buildDirectory.file("generated/apkDeltaMetadata/apk-delta.json"))
    doLast {
        fun sha256(file: java.io.File) = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun apk(name: String) = inputDirectory.resolve("$name-arm64.apk").also { check(it.isFile) { "Missing $name arm64 measurement APK; run captureCandidateMeasurementApk with -PfireDetectorCandidate=$name" } }
        val modelManifest = repositoryModelManifest.asFile
        check(modelManifest.isFile) { "Repository model manifest is missing: $modelManifest" }
        val modelManifestSha256 = sha256(modelManifest)
        fun archiveSha256(zip: ZipFile, entry: String): String = zip.getInputStream(zip.getEntry(entry) ?: error("Missing APK entry $entry")).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun provenance(name: String, apk: java.io.File): String = ZipFile(apk).use { zip ->
            check(archiveSha256(zip, "assets/model-candidates.json") == modelManifestSha256) { "$apk has a stale model-candidates manifest" }
            val runtimeEntries = expectedCandidateRuntimes.getValue(name)
            runtimeEntries.forEach { check(zip.getEntry(it) != null) { "$apk is mislabeled: missing $it" } }
            val modelEntries = expectedCandidateModels.getValue(name)
            modelEntries.forEach { check(zip.getEntry("assets/$it") != null) { "$apk is mislabeled: missing model $it" } }
            val models = modelEntries.joinToString(",") { path -> "{\"path\":\"$path\",\"sha256\":\"${archiveSha256(zip, "assets/$path")}\"}" }
            "{\"apkSha256\":\"${sha256(apk)}\",\"runtimeEntries\":[${runtimeEntries.joinToString(",") { "\"$it\"" }}],\"modelEntries\":[$models]}"
        }
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
            file to provenance(name, file)
        }
        check(candidates.values.all { it.first.length() >= baseline }) { "Candidate APK must not be smaller than the runtime-free baseline" }
        val json = buildString {
            append("{\n  \"abi\": \"arm64-v8a\",\n  \"modelCandidatesSha256\": \"$modelManifestSha256\",\n  \"baselineApkBytes\": $baseline,\n  \"candidates\": {\n")
            append(candidates.entries.joinToString(",\n") { (name, value) -> "    \"$name\": {\"apkBytes\": ${value.first.length()}, \"apkDeltaBytes\": ${value.first.length() - baseline}, \"provenance\":${value.second}}" })
            append("\n  }\n}\n")
        }
        val output = layout.buildDirectory.file("generated/apkDeltaMetadata/apk-delta.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(json)
    }
}

writeApkDeltaMetadata.configure { mustRunAfter(createApkDeltaMetadataFixture) }
tasks.register("verifyWriteApkDeltaMetadataFixture") {
    dependsOn(createApkDeltaMetadataFixture, writeApkDeltaMetadata)
    doLast {
        val metadata = layout.buildDirectory.file("generated/apkDeltaMetadata/apk-delta.json").get().asFile
        val expectedManifestSha256 = MessageDigest.getInstance("SHA-256")
            .digest(repositoryModelManifest.asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(metadata.isFile) { "APK delta fixture did not produce metadata" }
        check(metadata.readText().contains("\"modelCandidatesSha256\": \"$expectedManifestSha256\"")) {
            "APK delta fixture metadata is not bound to the repository model manifest"
        }
        check(metadata.delete()) {
            "Synthetic APK delta metadata must not remain available to a device gate"
        }
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
