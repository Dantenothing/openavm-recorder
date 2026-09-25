pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../ZeekrBridgeCompanion/gradle/libs.versions.toml")) } }
}
rootProject.name = "OpenAVMUnifiedPhone"
include(":app", ":openavm-companion", ":transfer-protocol", ":sound-core", ":localization")
project(":transfer-protocol").projectDir = file("../transfer-protocol")
project(":sound-core").projectDir = file("../sound-core")
project(":localization").projectDir = file("../localization")
