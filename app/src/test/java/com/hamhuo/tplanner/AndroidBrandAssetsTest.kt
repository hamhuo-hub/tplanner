package com.hamhuo.tplanner

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBrandAssetsTest {
    private val repositoryRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "app").isDirectory && File(it, "wear").isDirectory }
    }

    @Test
    fun phoneAndWearLauncherForegroundsStayIdentical() {
        listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi").forEach { density ->
            val phone = file("app/src/main/res/mipmap-$density/ic_launcher_foreground.png")
            val wear = file("wear/src/main/res/mipmap-$density/ic_launcher_foreground.png")
            assertTrue("Phone foreground missing at $density", phone.isFile)
            assertTrue("Wear foreground missing at $density", wear.isFile)
            assertArrayEquals("Launcher drift at $density", sha256(wear), sha256(phone))
        }
    }

    @Test
    fun adaptiveIconsAndPickerMetadataUseDirectRasterResources() {
        listOf(
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
            "wear/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
            "wear/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
        ).forEach { relative ->
            val xml = file(relative).readText()
            assertTrue(relative, xml.contains("@mipmap/ic_launcher_foreground"))
            assertTrue(relative, xml.contains("@drawable/ic_launcher_background"))
        }

        val manifest = file("wear/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("@drawable/preview_tide_static"))
        assertTrue(manifest.contains("@drawable/preview_next"))
        assertTrue(file("wear/src/main/res/xml/watch_face.xml").readText().contains("@drawable/preview_tide_static"))
        assertTrue(file("wear/src/main/res/xml/watch_face_next.xml").readText().contains("@drawable/preview_next"))

        val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(pngSignature.toList(), file("wear/src/main/res/drawable-nodpi/preview_tide_static.png").readBytes().take(8))
        assertEquals(pngSignature.toList(), file("wear/src/main/res/drawable-nodpi/preview_next.png").readBytes().take(8))
    }

    private fun file(relative: String): File = File(repositoryRoot, relative)

    private fun sha256(file: File): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
