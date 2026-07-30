import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest
import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val measurementCandidate = providers.gradleProperty("fireDetectorCandidate").orElse("benchmark").get()
val supportedMeasurementCandidates = setOf("baseline", "onnx", "tflite", "ncnn", "benchmark")
check(measurementCandidate in supportedMeasurementCandidates) { "Unknown fireDetectorCandidate: $measurementCandidate" }
val NCNN_VERSION = "20260526"
val NCNN_ARCHIVE_SHA256 = "eb205b332274974511890903828451ae7a4c19c309f21431536e0a8c9f3dd0c1"

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
        include("benchmark-set/labels/**")
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

val formalAgentApk = providers.gradleProperty("formalAgentApk").map(::file)
val formalAgentTrustOutput = layout.buildDirectory.file("generated/formalAgentTrust/formal-agent-trust.json")
val writeFormalAgentTrust by tasks.registering {
    outputs.file(formalAgentTrustOutput)
    doLast {
        check(formalAgentApk.isPresent) {
            "Formal Agent packaging requires -PformalAgentApk=/absolute/path/to/formal-real-uxsdk-agent.apk"
        }
        val apk = formalAgentApk.get()
        check(apk.isFile) { "Formal Agent APK is missing: $apk" }
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
        val apksigner = android.sdkDirectory
            .resolve("build-tools/${android.buildToolsVersion}/apksigner")
        check(apksigner.isFile) { "Android SDK apksigner is missing: $apksigner" }
        val apksignerOutput = ByteArrayOutputStream()
        val verification = project.exec {
            commandLine(apksigner, "verify", "--print-certs", apk)
            standardOutput = apksignerOutput
            errorOutput = apksignerOutput
            isIgnoreExitValue = true
        }
        check(verification.exitValue == 0) {
            "Formal Agent APK signature verification failed: ${apksignerOutput.toString(Charsets.UTF_8)}"
        }
        val certificates = Regex(
            """Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})""",
        ).findAll(apksignerOutput.toString(Charsets.UTF_8))
            .map { it.groupValues[1].lowercase() }
            .toList()
        check(certificates.size == 1) { "Formal Agent APK must have exactly one verified signer" }
        val output = formalAgentTrustOutput.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """{"packageName":"com.yinxin.uavfir","apkSha256":"${sha256(apk)}","signingCertificateSha256":"${certificates.single()}"}""",
        )
    }
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
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/formalAgentTrust"))
tasks.named("preBuild").configure { dependsOn(stageBenchmarkAssets) }
tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
    dependsOn(verifyVisibleBenchmarkInputs, writeFormalAgentTrust)
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

val ncnnArchive = providers.gradleProperty("ncnnArchive").map(::file)
val ncnnAndroidNdkDir = providers.gradleProperty("ncnnAndroidNdkDir")
    .orElse(providers.environmentVariable("ANDROID_NDK_HOME"))
    .map(::file)
val ncnnCmakeExecutable = providers.gradleProperty("ncnnCmakeExecutable").orElse("cmake")
val requiresNcnnRuntime = measurementCandidate in setOf("ncnn", "benchmark")
val ncnnExtractOutputDir = layout.buildDirectory.dir("generated/ncnnPackage")
val ncnnBridgeBuildOutputDir = layout.buildDirectory.dir("generated/ncnnBridgeBuild")
val ncnnRuntimeTrustOutput = layout.buildDirectory.file("generated/ncnnRuntimeTrust/ncnn-runtime-trust.json")
val ncnnJniOutputDir = layout.buildDirectory.dir("generated/ncnnJni")

val buildNcnnBridgeFromSource = tasks.register("buildNcnnBridgeFromSource") {
    inputs.files(
        layout.projectDirectory.file("src/main/cpp/CMakeLists.txt"),
        layout.projectDirectory.file("src/main/cpp/ncnn_bridge.cpp"),
    )
    outputs.dir(ncnnExtractOutputDir)
    outputs.dir(ncnnBridgeBuildOutputDir)
    outputs.file(ncnnRuntimeTrustOutput)
    outputs.upToDateWhen { false }
    doLast {
        val extracted = ncnnExtractOutputDir.get().asFile
        val bridgeBuild = ncnnBridgeBuildOutputDir.get().asFile
        val trustFile = ncnnRuntimeTrustOutput.get().asFile
        for (directory in listOf(extracted, bridgeBuild)) {
            check(directory.deleteRecursively() || !directory.exists()) { "Cannot clear $directory" }
        }
        check(trustFile.delete() || !trustFile.exists()) { "Cannot clear stale NCNN runtime trust" }
        if (!requiresNcnnRuntime) return@doLast
        check(ncnnArchive.isPresent && ncnnAndroidNdkDir.isPresent) {
            "NCNN packaging requires -PncnnArchive=<ncnn-$NCNN_VERSION-android-vulkan-shared.zip> and -PncnnAndroidNdkDir=<Android NDK>; see fire-detector-benchmark/README.md"
        }
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
        val archive = ncnnArchive.get()
        check(archive.isFile) { "Pinned NCNN archive is missing: $archive" }
        check(sha256(archive) == NCNN_ARCHIVE_SHA256) {
            "NCNN archive SHA-256 does not match the committed $NCNN_VERSION lock"
        }
        copy {
            from(zipTree(archive))
            into(extracted)
        }
        val packageRoot = extracted.resolve("ncnn-$NCNN_VERSION-android-vulkan-shared/arm64-v8a")
        val ncnnConfig = packageRoot.resolve("lib/cmake/ncnn/ncnnConfig.cmake")
        val runtime = packageRoot.resolve("lib/libncnn.so")
        check(ncnnConfig.isFile && runtime.isFile) {
            "Pinned NCNN archive lacks the arm64-v8a CMake package/runtime"
        }
        val ndk = ncnnAndroidNdkDir.get()
        val toolchain = ndk.resolve("build/cmake/android.toolchain.cmake")
        check(toolchain.isFile) { "Android NDK CMake toolchain is missing: $toolchain" }
        val cmake = ncnnCmakeExecutable.get()
        val configureOutput = ByteArrayOutputStream()
        val configure = project.exec {
            commandLine(
                cmake,
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
            "NCNN bridge configure failed: ${configureOutput.toString(Charsets.UTF_8)}"
        }
        val buildOutput = ByteArrayOutputStream()
        val build = project.exec {
            commandLine(cmake, "--build", bridgeBuild, "--config", "Release")
            standardOutput = buildOutput
            errorOutput = buildOutput
            isIgnoreExitValue = true
        }
        check(build.exitValue == 0) {
            "NCNN bridge build failed: ${buildOutput.toString(Charsets.UTF_8)}"
        }
        val bridge = bridgeBuild.resolve("libfire_detector_ncnn.so")
        check(bridge.isFile) { "Current-source NCNN JNI bridge was not produced" }
        val bridgeSources = listOf(
            layout.projectDirectory.file("src/main/cpp/CMakeLists.txt").asFile,
            layout.projectDirectory.file("src/main/cpp/ncnn_bridge.cpp").asFile,
        )
        val sourceIdentity = bridgeSources.sortedBy { it.name }.joinToString("\n") {
            "${it.name}:${sha256(it)}"
        }
        val sourceSha256 = MessageDigest.getInstance("SHA-256")
            .digest(sourceIdentity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        trustFile.parentFile.mkdirs()
        trustFile.writeText(
            """{"version":"$NCNN_VERSION","packageArchiveSha256":"$NCNN_ARCHIVE_SHA256","bridgeSourceSha256":"$sourceSha256","runtimeSha256":"${sha256(runtime)}","bridgeSha256":"${sha256(bridge)}"}""",
        )
    }
}

val stageNcnnRuntime = tasks.register("stageNcnnRuntime") {
    dependsOn(buildNcnnBridgeFromSource)
    outputs.dir(ncnnJniOutputDir)
    // Always execute so a baseline/ONNX/TFLite build removes JNI files left by an NCNN build.
    outputs.upToDateWhen { false }
    doLast {
        val outputDirectory = ncnnJniOutputDir.get().asFile
        check(outputDirectory.deleteRecursively() || !outputDirectory.exists()) { "Cannot clear staged NCNN JNI libraries" }
        if (!requiresNcnnRuntime) return@doLast
        val packageRoot = ncnnExtractOutputDir.get().asFile
            .resolve("ncnn-$NCNN_VERSION-android-vulkan-shared/arm64-v8a")
        val runtime = packageRoot.resolve("lib/libncnn.so")
        val bridge = ncnnBridgeBuildOutputDir.get().asFile.resolve("libfire_detector_ncnn.so")
        check(runtime.isFile && bridge.isFile && ncnnRuntimeTrustOutput.get().asFile.isFile) {
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
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/ncnnRuntimeTrust"))
tasks.matching { it.name == "mergeDebugJniLibFolders" }.configureEach { dependsOn(stageNcnnRuntime) }
tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
    if (requiresNcnnRuntime) dependsOn(buildNcnnBridgeFromSource)
}

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
            if (candidate == "ncnn") {
                fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                entries["assets/ncnn-runtime-trust.json"] =
                    """{"version":"$NCNN_VERSION","packageArchiveSha256":"$NCNN_ARCHIVE_SHA256","bridgeSourceSha256":"${"8".repeat(64)}","runtimeSha256":"${sha256(entries.getValue("lib/arm64-v8a/libncnn.so"))}","bridgeSha256":"${sha256(entries.getValue("lib/arm64-v8a/libfire_detector_ncnn.so"))}"}"""
                        .toByteArray()
            }
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
            val runtimes = runtimeEntries.joinToString(",") { path ->
                "{\"path\":\"$path\",\"sha256\":\"${archiveSha256(zip, path)}\"}"
            }
            val ncnnBuild = if (name == "ncnn") {
                val trustEntry = zip.getEntry("assets/ncnn-runtime-trust.json")
                    ?: error("NCNN candidate APK lacks build-generated runtime trust")
                val trust = zip.getInputStream(trustEntry).bufferedReader().use { it.readText() }
                ",\"ncnnBuild\":$trust"
            } else {
                ""
            }
            "{\"apkSha256\":\"${sha256(apk)}\",\"runtimeEntries\":[$runtimes],\"modelEntries\":[$models]$ncnnBuild}"
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
