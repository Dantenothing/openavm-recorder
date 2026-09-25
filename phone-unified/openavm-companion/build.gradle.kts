plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
val companionSource = file("../../ZeekrBridgeCompanion/app")
android {
    namespace = "com.dante.zeekrbridge"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets {
        getByName("main") {
            java.srcDir(companionSource.resolve("src/main/java"))
            res.srcDir(companionSource.resolve("src/main/res"))
        }
        getByName("test") {
            java.srcDir(companionSource.resolve("src/test/java"))
            resources.srcDir(companionSource.resolve("src/test/resources"))
        }
    }
}
tasks.withType<Test>().configureEach { workingDir(companionSource) }
dependencies {
    implementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    implementation(project(":transfer-protocol"))
    implementation(project(":sound-core"))
    implementation(project(":localization"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}
