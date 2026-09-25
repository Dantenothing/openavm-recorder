import groovy.json.JsonOutput
import java.security.MessageDigest
import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// V5 includes the owner-tested return behaviour. Recording still requires an explicit user choice.
val publicUiCheck = providers.gradleProperty("publicUiCheck").orNull == "true"
val preflightAssets = layout.buildDirectory.dir("generated/preflightAssets")
val generatePreflightEvidence by tasks.registering {
    outputs.dir(preflightAssets)
    outputs.upToDateWhen { false }
    doLast {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val files = (listOf("app", "transfer-protocol", "sound-core", "localization").flatMap { module ->
            rootProject.fileTree(module) {
                include("src/**/*.kt", "src/**/*.xml", "src/androidTest/assets/playback/*", "build.gradle.kts", "proguard-rules.pro")
            }.files
        } + listOf("build.gradle.kts", "settings.gradle.kts", "gradle/libs.versions.toml", "gradle.properties").map { rootProject.file(it) }.filter { it.isFile })
            .sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }
        val manifest = files.associate { it.relativeTo(rootDir).invariantSeparatorsPath to digest(it.readBytes()) }
        val manifestBytes = JsonOutput.toJson(manifest).toByteArray(Charsets.UTF_8)
        val manifestDigest = digest(manifestBytes)
        val directory = preflightAssets.get().dir("preflight").asFile.apply { mkdirs() }
        directory.resolve("source-manifest.json").writeBytes(manifestBytes)
        val gitStatus = runCatching { providers.exec { commandLine("git", "status", "--porcelain"); isIgnoreExitValue=true }.standardOutput.asText.get() }.getOrNull()
        val adapters = manifest.filterKeys { path -> path.endsWith("RecorderSession.kt") || path.endsWith("MirrorGlPreview.kt") ||
            path.endsWith("MirrorPreviewController.kt") || path.endsWith("UsbMediaStoreRecordingOutput.kt") ||
            path.endsWith("UsbMediaStoreBackend.kt") || path.contains("/preflight/") }
        val evidence = mapOf("schemaVersion" to 1, "applicationId" to android.defaultConfig.applicationId,
            "versionName" to android.defaultConfig.versionName, "versionCode" to android.defaultConfig.versionCode,
            "buildId" to "preflight-${manifestDigest.take(20)}", "gitCommit" to buildGitSha,
            "dirty" to gitStatus?.isNotBlank(), "sourceManifestSha256" to manifestDigest, "sourceCount" to manifest.size,
            "sourceManifestAsset" to "preflight/source-manifest.json", "adapterFingerprints" to adapters,
            "generatedAtUtc" to Instant.now().toString(),
            "flags" to mapOf("MIRROR_RETURN_ENABLED" to true,
                "NATIVE_FILE_ROTATION_ENABLED" to false,"PREFLIGHT_FORCE_BASELINE_MEDIA_RECORDER" to true,
                "PREFLIGHT_INTERNAL_FALLBACK" to false,"PREFLIGHT_AUTOMATIC_CAMERA_RECOVERY" to false,
                "P1_BASIC_SYNTHETIC_GL_CODEC" to true,"P1_INPUT_SYNTHETIC_OES_SHARED_READERS" to true,
                "P1_SEPARATE_STAGE_WATCHDOGS" to true,"P1_TARGET_AND_HIGH_LOAD" to true,"PRODUCTION_USES_P1_CORE" to true,
                "PRODUCT_CONTINUOUS_TRIAL_AVAILABLE" to true,"PRODUCT_CONTINUOUS_DEFAULT" to true,
                "EXPERIMENTAL_TOOLS_ENABLED_IN_PUBLIC_RELEASE" to false,
                "DIAGNOSTIC_CLOUD_UPLOAD" to false,"DIAGNOSTIC_REMOTE_COMMANDS" to false,
                "DIAGNOSTIC_REMOTE_RECIPES_V1" to false,"DIAGNOSTIC_EXACT_EVIDENCE_REVIEW" to true,
                "AWAY_SINGLE_EXPORT_RECORDER_FAULT" to true,"RECORDER_TERMINAL_STATE_FENCE" to true,
                "AWAY_FIRST_CAUSE_AND_CLOSE_EVIDENCE" to true,"AWAY_STRUCTURED_CONTINUOUS_FIRST_FAILURE" to true,
                "C0_PARKING_EXPERIMENT_ENABLED" to false,
                "DIAGNOSTIC_RATE_CONTROL_TRIAL_V1" to true,"DIAGNOSTIC_RUNNER_VERSION" to 3,
                "P2_REAL_CAMERA_SHARED_INPUT" to true,"P2_REAL_PAGE_OVERLAY_WINDOWS" to true,
                "P2_BOUNDED_FILE_FINALIZER" to true,"P2_FULL_PTS_LEDGER" to true),
            "implementedTestCases" to listOf("public_capabilities", "egl_small", "usb_fd", "closed_file", "baseline", "p1_basic", "p1_input", "oes_input", "shared_pool_pressure", "encoded_load", "p2_camera"),
            "coverageNotes" to "5.0.0 / 102 promotes the owner-tested sleep4 return feature into the default public build: retained Logo, preview or explicit opt-in recording, fresh installation confirmation, bounded metadata preparation and preview recovery. It retains the recorder, segmentation, USB fallback, camera cleanup interlocks and structured first-failure evidence. Public UI excludes laboratory entry points; internal debug builds may enable them. The owner accepted the latest sleep4 on 2026-09-25, separately from the earlier RC6 short checks. This is owner-reported predecessor acceptance, not an on-vehicle run of this final binary. The earlier FILE_WRITER exception, general departure-stop coverage and whole-vehicle power remain unverified. No GPS, fog map, remote parking, process resurrection or cold-wake is claimed.",
            "vehicleAcceptanceBaseline" to mapOf("version" to "4.1.0-rc6", "versionCode" to 99,
                "reportedOn" to "2026-09-25", "evidenceType" to "OWNER_CONFIRMATION",
                "checks" to listOf("LAST_USB_SEGMENT_PLAYBACK", "MANUAL_RECORDING_AND_PLAYBACK", "NATIVE_CABIN_RECOVERY_AND_PLAYBACK")),
            "returnFeatureAcceptance" to mapOf("version" to "5.0.0-sleep4", "versionCode" to 101,
                "reportedOn" to "2026-09-25", "evidenceType" to "OWNER_CONFIRMATION",
                "scope" to "Latest delivered sleep4 reported working; no independent final-binary vehicle recording claimed"),
            "notImplemented" to listOf("final_binary_vehicle_run", "broader_camera_contention_acceptance", "phone_android_raster_acceptance", "real_source_overload"),
            "localTestResults" to null, "localTestResultsReason" to "See separately hashed delivery validation; this asset does not self-certify tests.",
            "r8MappingSha256" to null, "r8MappingReason" to "Generated after this asset; linked in external delivery manifest to avoid circular hashes.")
        directory.resolve("build-evidence.json").writeText(JsonOutput.toJson(evidence),Charsets.UTF_8)
    }
}
android.sourceSets.getByName("main").assets.srcDir(preflightAssets)
tasks.named("preBuild").configure { dependsOn(generatePreflightEvidence) }

val buildGitSha = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "unknown" }
}.getOrDefault("unknown")

android {
    namespace = "com.dante.zeekrcapabilitylab"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.dantenothing.openavmrecorder"
        minSdk = 26
        targetSdk = 36
        versionCode = 102
        versionName = "5.0.0"
        buildConfigField("boolean", "MIRROR_RETURN_ENABLED", "true")
        buildConfigField("boolean", "EXPERIMENTAL_TOOLS_ENABLED", "false")
        buildConfigField("String", "DIAGNOSTIC_ENDPOINT", "\"https://openavm-diagnostics.dantenothing.workers.dev\"")
        buildConfigField("boolean", "C0_PARKING_EXPERIMENT_ENABLED", "false")
        // Beta12 exited at its first native handoff in the vehicle. Preserve recovery, disable new runs.
        buildConfigField("boolean", "NATIVE_FILE_ROTATION_ENABLED", "false")
        buildConfigField("boolean", "BROWSER_DOWNLOAD_ENABLED", "false")
        buildConfigField("boolean", "CONTINUOUS_MIRROR_RECORDING_ENABLED", "true")
        buildConfigField("String", "GIT_SHA", "\"$buildGitSha\"")
        buildConfigField("boolean", "CAMERA_INTERRUPTION_RECOVERY_ENABLED", "true")
        // Hardware gates have not been accepted. Production routing stays on RecorderSession.
        buildConfigField("boolean", "SENTRY_AUTO_ENABLED", "false")
        buildConfigField("boolean", "SENTRY_CANARY_ENABLED", "false")
        buildConfigField("boolean", "SENTRY_INTEGRATED_ENABLED", "false")
        buildConfigField("boolean", "SENTRY_CAPTURE_ENABLED", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Emulator QA can exercise the public UI while retaining its internal test host.
            buildConfigField("boolean", "EXPERIMENTAL_TOOLS_ENABLED", (!publicUiCheck).toString())
        }
        release {
            ndk { abiFilters += "arm64-v8a" }
            // The product entry points no longer reach the retired capability
            // lab and phone-transfer experiments. R8 keeps those classes out
            // of the public release APK while the private research work stays
            // available in this local workspace.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testBuildType = "debug"
}

dependencies {
    implementation(project(":transfer-protocol"))
    implementation(project(":sound-core"))
    implementation(project(":localization"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.androidx.media3.exoplayer)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
