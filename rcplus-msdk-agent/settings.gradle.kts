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
project(":uxsdk").projectDir = file("../Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk")
