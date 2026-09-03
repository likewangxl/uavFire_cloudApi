package com.yinxin.uavfir

import java.io.DataInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourcePolicyTest {
    private val main = File("src/main")

    @Test
    fun launcherIcon_hasManifestAdaptiveAndLegacyResources() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:icon="@mipmap/ic_launcher""""))
        assertTrue(manifest.contains("""android:roundIcon="@mipmap/ic_launcher_round""""))
        assertTrue(manifest.contains("""android:label="@string/agent_app_name""""))

        val strings = File(main, "res/values/strings.xml").readText()
        assertTrue(strings.contains("""<string name="app_name">无人机火情智巡平台</string>"""))
        assertTrue(strings.contains("""<string name="agent_app_name">无人机火情智巡平台</string>"""))

        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { name ->
            val xml = File(main, "res/mipmap-anydpi-v26/$name").readText()
            assertTrue(xml.contains("""android:drawable="@color/ic_launcher_background""""))
            assertTrue(xml.contains("""android:drawable="@mipmap/ic_launcher_foreground""""))
        }

        val sizes = linkedMapOf(
            "mdpi" to 48,
            "hdpi" to 72,
            "xhdpi" to 96,
            "xxhdpi" to 144,
            "xxxhdpi" to 192,
        )
        sizes.forEach { (density, size) ->
            assertSquarePng("res/mipmap-$density/ic_launcher.png", size)
            assertSquarePng("res/mipmap-$density/ic_launcher_round.png", size)
            assertSquarePng("res/mipmap-$density/ic_launcher_foreground.png", size * 9 / 4)
        }
    }

    private fun assertSquarePng(relativePath: String, expectedSize: Int) {
        DataInputStream(File(main, relativePath).inputStream().buffered()).use { input ->
            val signature = ByteArray(8)
            input.readFully(signature)
            assertTrue(relativePath, signature.contentEquals(PNG_SIGNATURE))
            assertEquals(relativePath, 13, input.readInt())

            val chunkType = ByteArray(4)
            input.readFully(chunkType)
            assertEquals(relativePath, "IHDR", chunkType.toString(Charsets.US_ASCII))
            assertEquals(relativePath, expectedSize, input.readInt())
            assertEquals(relativePath, expectedSize, input.readInt())
        }
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
