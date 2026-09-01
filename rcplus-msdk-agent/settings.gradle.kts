pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "rcplus-msdk-agent"
include(":app")

include(":uxsdk")
val uxSdkOverride = providers.gradleProperty("djiUxSdkDir").orNull
    ?: System.getenv("DJI_UXSDK_DIR")
val uxSdkDir = uxSdkOverride
    ?.let(::file)
    ?: file("../Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk")
val useUxSdkStub = providers.gradleProperty("useUxSdkStub").orNull?.toBoolean() == true

project(":uxsdk").projectDir = when {
    uxSdkDir.isDirectory -> uxSdkDir
    useUxSdkStub -> file("uxsdk-stub")
    else -> throw GradleException(
        "DJI UXSDK module not found at ${uxSdkDir.absolutePath}. " +
            "Set -PdjiUxSdkDir=/path/to/android-sdk-v5-uxsdk (or DJI_UXSDK_DIR). " +
            "Use -PuseUxSdkStub=true only for explicit non-device test builds."
    )
}
