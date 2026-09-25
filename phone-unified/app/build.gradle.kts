import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Maintainer-supplied app protocol constants only. Never point this at an account export.
val protocolSource = providers.gradleProperty("zeekrProtocolFile").orElse(
    rootProject.layout.projectDirectory.file("config/private/zeekr-au-166.json").asFile.absolutePath)
val protocolAssets = layout.buildDirectory.dir("generated/defaultProtocolAssets")
val prepareDefaultProtocol by tasks.registering {
    inputs.file(protocolSource)
    outputs.dir(protocolAssets)
    doLast {
        val source = file(protocolSource.get())
        require(source.isFile && source.length() in 1..65_536) { "A valid local AU 1.6.6 protocol profile is required to build the phone app." }
        val fields = listOf("hmac_access_key", "hmac_secret_key", "password_public_key", "prod_secret", "vin_key", "vin_iv")
        val parsed = try { groovy.json.JsonSlurper().parse(source) as? Map<*, *> }
            catch (_: Exception) { error("Protocol profile is not valid JSON; values suppressed.") }
            ?: error("Protocol profile must be a JSON object.")
        require(parsed.keys == fields.toSet()) { "Protocol profile must contain exactly six app-level fields; account exports must not be bundled." }
        val normalized = fields.associateWith { name ->
            (parsed[name] as? String)?.takeIf { it.isNotBlank() && it.length <= 8192 && !it.startsWith("<") }
                ?: error("Protocol profile has an invalid field.")
        }
        require(normalized.getValue("hmac_access_key").all { it.code in 33..126 })
        require(normalized.getValue("vin_key").toByteArray(Charsets.UTF_8).size in setOf(16, 24, 32))
        require(normalized.getValue("vin_iv").toByteArray(Charsets.UTF_8).size == 16)
        val publicKey = normalized.getValue("password_public_key").replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "").filterNot(Char::isWhitespace)
        try {
            KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        } catch (_: Exception) { error("Protocol profile has an invalid RSA public key.") }
        val target = protocolAssets.get().file("connection/zeekr-au-166.json").asFile
        target.parentFile.mkdirs()
        target.writeText(groovy.json.JsonOutput.toJson(normalized), Charsets.UTF_8)
    }
}
android {
    namespace = "com.dante.zeekrcheck"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.dante.zeekrbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 51
        versionName = "5.0.0"
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
    sourceSets.getByName("main").assets.srcDir(protocolAssets)
}
tasks.named("preBuild").configure { dependsOn(prepareDefaultProtocol) }
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
}
