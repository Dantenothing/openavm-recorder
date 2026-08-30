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
    }
}

rootProject.name = "ZeekrBridgeCompanion"
include(":app")
include(":transfer-protocol")
project(":transfer-protocol").projectDir = file("../transfer-protocol")
