package ai.rever.boss.plugin

import ai.rever.boss.plugin.loader.FileHashing
import ai.rever.boss.plugin.loader.PluginBundledTrust
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.loader.PluginSignatureVerifier
import ai.rever.boss.plugin.loader.PluginStoreTrust
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the reconciler's trust ordering: a JAR whose store signature verifies
 * (or whose bundled-trust marker matches its bytes) must outrank ANY unsigned
 * drop-in claiming a higher version. Before this, winner selection was purely
 * version/mtime/filename, so a hand-dropped unsigned jar with a bigger version
 * won the group, the signed jar was deleted with its `.sig`, and installed.json
 * was repointed at the unsigned artifact.
 */
class PluginJarReconcilerTrustTest {
    private val temps = mutableListOf<File>()

    private val keyPair =
        KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()

    private val testVerifier =
        PluginSignatureVerifier(
            mapOf(
                "test-store" to (
                    "-----BEGIN PUBLIC KEY-----\n" +
                        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded) +
                        "\n-----END PUBLIC KEY-----"
                ),
            ),
        )

    @BeforeTest
    fun injectVerifier() {
        PluginJarReconciler.signatureVerifier = testVerifier
    }

    @AfterTest
    fun cleanup() {
        PluginJarReconciler.signatureVerifier = PluginSignatureVerifier(PluginStoreTrust.TRUSTED_KEYS)
        temps.forEach { it.deleteRecursively() }
    }

    private fun tempPluginDir(): File =
        File.createTempFile("reconcile-trust", "").let {
            it.delete()
            it.mkdirs()
            temps.add(it)
            it
        }

    private fun manifestJar(
        dir: File,
        fileName: String,
        pluginId: String,
        version: String,
    ): File {
        val jar = File(dir, fileName)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$pluginId",
                  "displayName": "Reconciler Trust Test",
                  "version": "$version",
                  "apiVersion": "1.0.0",
                  "mainClass": "com.example.Missing"
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return jar
    }

    private fun signWithTestKey(
        jar: File,
        pluginId: String,
        version: String,
    ) {
        val anchor = PluginStoreTrust.versionAnchor(pluginId, version, FileHashing.sha256(jar))
        val sig =
            Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(anchor.toByteArray(Charsets.UTF_8))
            }
        PluginSignatureSidecar.write(jar.absolutePath, Base64.getEncoder().encodeToString(sig.sign()))
    }

    @Test
    fun `a signed jar wins over an unsigned higher version and keeps the persisted path`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.trust"
        val signed = manifestJar(dir, "test-plugin-1.0.0.jar", pluginId, "1.0.0")
        val unsigned = manifestJar(dir, "test-plugin-2.0.0.jar", pluginId, "2.0.0")
        signWithTestKey(signed, pluginId, "1.0.0")

        // The signed jar is what installed.json knows about; the unsigned
        // drop-in is the attacker artifact.
        PluginPersistence.addInstalledPlugin(pluginId, signed.absolutePath, enabled = true)

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(signed), result.winners, "the signed candidate must win regardless of version")
        assertTrue(signed.exists(), "the signed jar must survive")
        assertTrue(
            File(PluginSignatureSidecar.pathFor(signed.absolutePath)).exists(),
            "the signed jar's sidecar must survive",
        )
        assertFalse(unsigned.exists(), "the unsigned drop-in is the loser and is removed")
        assertEquals(
            signed.absolutePath,
            PluginPersistence.getInstalledPlugin(pluginId)?.jarPath,
            "installed.json must not be repointed at the unsigned jar",
        )
    }

    @Test
    fun `a forged sidecar does not make an unsigned jar trusted`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.forged"
        val signed = manifestJar(dir, "forged-1.0.0.jar", pluginId, "1.0.0")
        val forged = manifestJar(dir, "forged-2.0.0.jar", pluginId, "2.0.0")
        signWithTestKey(signed, pluginId, "1.0.0")
        // A present-but-unverifiable signature is evidence, not trust.
        PluginSignatureSidecar.write(forged.absolutePath, "bm90LWEtc2lnbmF0dXJl")

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(signed), result.winners)
        assertTrue(signed.exists())
        assertFalse(forged.exists(), "a jar with an invalid sidecar is unsigned and loses")
    }

    @Test
    fun `bundled trust also outranks an unsigned higher version`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.bundled"
        val bundled = manifestJar(dir, "bundled-1.0.0.jar", pluginId, "1.0.0")
        val unsigned = manifestJar(dir, "bundled-2.0.0.jar", pluginId, "2.0.0")
        PluginBundledTrust.bindToBundle(bundled.absolutePath, bundled)

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(bundled), result.winners)
        assertTrue(bundled.exists())
        assertTrue(PluginBundledTrust.isTrusted(bundled.absolutePath))
        assertFalse(unsigned.exists())
    }

    @Test
    fun `two unsigned jars still reconcile by version`() {
        val dir = tempPluginDir()
        val pluginId = "ai.rever.boss.plugin.test.plain"
        manifestJar(dir, "plain-1.0.0.jar", pluginId, "1.0.0")
        val newer = manifestJar(dir, "plain-2.0.0.jar", pluginId, "2.0.0")

        val result = PluginJarReconciler.reconcilePluginDir(dir, pluginIds = null)

        assertEquals(listOf(newer), result.winners)
        assertFalse(File(dir, "plain-1.0.0.jar").exists())
    }
}
