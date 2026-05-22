plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.1.21" apply false
}


extra["ANDROID_COMPILE_SDK_VERSION"] = "34"
extra["ANDROID_MIN_SDK_VERSION"] = "26"
extra["ANDROID_TARGET_SDK_VERSION"] = "34"
extra["KOTLIN_VERSION"] = "2.1.21"

@Suppress("UNCHECKED_CAST")
val sampleDeps = mapOf(
    "aircraft" to "com.dji:dji-sdk-v5-aircraft:5.17.0",
    "aircraftProvided" to "com.dji:dji-sdk-v5-aircraft-provided:5.17.0",
    "annotation" to "androidx.annotation:annotation:1.8.2",
    "appcompat" to "androidx.appcompat:appcompat:1.7.0",
    "multidex" to "androidx.multidex:multidex:2.0.1",
    "legacySupport" to "androidx.legacy:legacy-support-v4:1.0.0",
    "recyclerview" to "androidx.recyclerview:recyclerview:1.3.2",
    "okio" to "com.squareup.okio:okio:3.9.0",
    "wire" to "com.squareup.wire:wire-runtime:2.2.0",
    "constraintLayout" to "androidx.constraintlayout:constraintlayout:2.2.0",
    "lifecycleJava8" to "androidx.lifecycle:lifecycle-common-java8:2.8.4",
    "lifecycleRuntime" to "androidx.lifecycle:lifecycle-runtime-ktx:2.8.4",
    "lifecycleProcess" to "androidx.lifecycle:lifecycle-process:2.8.4",
    "media" to "androidx.media:media:1.7.0",
    "kotlinLib" to "org.jetbrains.kotlin:kotlin-stdlib:2.1.21",
    "ktxCore" to "androidx.core:core-ktx:1.13.1",
    "rx3Android" to "io.reactivex.rxjava3:rxandroid:3.0.2",
    "rx3Kt" to "io.reactivex.rxjava3:rxkotlin:3.0.1",
    "wpmzSdk" to "com.dji:wpmzsdk:1.0.4.0",
    "lottie" to "com.airbnb.android:lottie:6.4.1",
    "cardview" to "androidx.cardview:cardview:1.0.0",
    "mikepenzCommunityMaterial" to "com.mikepenz:community-material-typeface:3.5.95.1-kotlin@aar",
    "mikepenzGoogleMaterial" to "com.mikepenz:google-material-typeface:3.0.1.4.original-kotlin@aar",
    "mikepenzIconicsViews" to "com.mikepenz:iconics-views:4.0.2@aar",
    "mikepenzIconicsCore" to "com.mikepenz:iconics-core:4.0.2@aar",
    "mikepenzIonicons" to "com.mikepenz:ionicons-typeface:2.0.1.5-kotlin@aar",
    "material" to "com.google.android.material:material:1.12.0",
    "maplibreTurf" to "org.maplibre.gl:android-sdk-turf:5.9.1",
    "maplibreSdk" to "org.maplibre.gl:android-sdk:10.3.1",
    "playservicesplaces" to "com.google.android.gms:play-services-places:17.1.0",
    "playservicesmaps" to "com.google.android.gms:play-services-maps:19.0.0",
    "playserviceslocation" to "com.google.android.gms:play-services-location:21.3.0",
    "playservicesbase" to "com.google.android.gms:play-services-base:18.5.0"
)
extra["deps"] = sampleDeps


subprojects {
    configurations.configureEach {
        resolutionStrategy.force("com.squareup.wire:wire-runtime:2.2.0")
    }
}
