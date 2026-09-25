plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Public builds never consume a private protocol file, including leftover generated assets.
android {
    namespace = "com.dante.zeekrcheck"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.dante.zeekrbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 52
        versionName = "5.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testBuildType = if (providers.gradleProperty("securityQa").orNull == "true") "freshQa" else "debug"
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            // Retain the signing identity used by existing OpenAVM installations.
            signingConfig = signingConfigs.getByName("debug")
        }
        // Separate UID and data directory for first-install QA; never clears the configured app.
        create("freshQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".freshqa"
            versionNameSuffix = "-freshqa"
            matchingFallbacks += listOf("debug")
        }
    }
    flavorDimensions += "installation"
    productFlavors {
        create("openavm") { dimension = "installation" }
        // Upgrade path for the early assistant trial; never the default OpenAVM delivery.
        create("assistantUpgrade") {
            dimension = "installation"
            applicationId = "com.dante.zeekrcheck"
        }
    }
}
dependencies {
    implementation(project(":openavm-companion"))
    implementation(project(":localization"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(project(":transfer-protocol"))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "freshQaImplementation"("androidx.compose.ui:ui-test-manifest")
}
