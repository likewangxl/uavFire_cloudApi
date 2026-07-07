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
val uxSdkDir = file("../Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk")
project(":uxsdk").projectDir = if (uxSdkDir.exists()) uxSdkDir else file("uxsdk-stub")
