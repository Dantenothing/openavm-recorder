package com.dante.zeekrbridge.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompanionManifestSecurityTest {

    @Test
    fun companionManifestDeclaresWakeLock() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${manifest.absolutePath}", manifest.isFile)
        val text = manifest.readText()
        assertTrue("WAKE_LOCK permission required for background bridge", text.contains("android.permission.WAKE_LOCK"))
        assertTrue("INTERNET permission required", text.contains("android.permission.INTERNET"))
    }
}
