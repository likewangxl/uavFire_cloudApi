plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yinxin.uavfir.benchmark"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yinxin.uavfir.benchmark"
        minSdk = 26
        targetSdk = 34
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
        include("thermal-fire-yolov8n-640-gt-20260709.onnx")
        include("thermal-fire-yolov8n-640-gt-20260709_float32.tflite")
        include("thermal-fire-yolov8n-640-gt-20260709_ncnn_model/model.ncnn.bin")
        include("thermal-fire-yolov8n-640-gt-20260709_ncnn_model/model.ncnn.param")
        include("thermal-fire-yolov8n-640-gt-20260709_ncnn_model/metadata.yaml")
        include("thermal-fire-yolov8n-640-gt-20260709_ncnn_model/model_ncnn.py")
    }
    into(layout.buildDirectory.dir("generated/benchmarkAssets"))
}

android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/benchmarkAssets"))
tasks.named("preBuild").configure { dependsOn(stageBenchmarkAssets) }

dependencies {
    // These native runtimes are intentionally isolated to this non-production APK.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
