package `in`.nispax.sruvik.security

import `in`.nispax.sruvik.srugen.SruGenChatStore
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SrukryptVaultInstrumentedTest {
    @Test
    fun missingWrappedKeyWithExistingCiphertextDoesNotCreateReplacementKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = SrukryptStorage.vaultDirectory(context)
        val alias = SrukryptStorage.keyAlias("master")
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrapped = File(directory, "master.wrap")
        val marker = File(directory, "00000000-0000-0000-0000-000000000001.srk")

        try {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            wrapped.delete()
            marker.writeBytes(byteArrayOf(1, 2, 3, 4))

            try {
                SrukryptVault.prepareUnlock(context)
                throw AssertionError("A vault with entries and no key envelope must not create a new key.")
            } catch (expected: SecurityException) {
                assertTrue(expected.message.orEmpty().contains("left untouched"))
            }
            assertTrue("No replacement Keystore alias may be created", !keyStore.containsAlias(alias))
            assertTrue("Existing encrypted entry must be preserved", marker.isFile)
        } finally {
            marker.delete()
            wrapped.delete()
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    @Test
    fun newVaultKeyUsesTimeBasedSystemAuthenticationForDeviceCredentialFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true)
        SrukryptVault.resetVault(context)

        var challenge: SrukryptUnlockChallenge? = null
        try {
            challenge = SrukryptVault.prepareUnlock(context)
            assertTrue("A first-time setup must create a new key challenge", challenge.isNewVault)
            assertTrue(
                "The key must have a positive authorization window for device-credential fallback",
                challenge.allowedAuthenticators and 0x8000 != 0 ||
                    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
            )
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keyStore.getKey(SrukryptStorage.keyAlias("master"), null) as SecretKey
            val keyInfo = SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            assertTrue(
                "A device-credential-compatible key must use a time-based authorization window",
                keyInfo.userAuthenticationValidityDurationSeconds > 0
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                assertTrue(
                    "Android 11+ key must allow the device credential",
                    keyInfo.userAuthenticationType and KeyProperties.AUTH_DEVICE_CREDENTIAL != 0
                )
                assertTrue(
                    "Android 11+ key must also allow strong biometrics",
                    keyInfo.userAuthenticationType and KeyProperties.AUTH_BIOMETRIC_STRONG != 0
                )
            }
        } finally {
            challenge?.let { SrukryptVault.discardChallenge(it) }
            SrukryptVault.resetVault(context)
        }
    }

    @Test
    fun resetPassphraseOnlyVerifiesDestructiveResetAndNeverUnlocksDataKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = SrukryptStorage.vaultDirectory(context)
        directory.deleteRecursively()
        val sessionKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val originalKey = sessionKey.copyOf()
        val correct = "correct reset-only passphrase".toCharArray()
        val incorrect = "incorrect reset-only passphrase".toCharArray()
        try {
            SrukryptVault.setResetPassphrase(context, sessionKey, correct)
            assertTrue(SrukryptVault.hasResetPassphrase(context))
            SrukryptVault.verifyResetPassphrase(context, correct)
            assertArrayEquals("Setting/verifying a reset phrase must not change the vault key", originalKey, sessionKey)

            try {
                SrukryptVault.verifyResetPassphrase(context, incorrect)
                throw AssertionError("An incorrect reset phrase must not authorize reset.")
            } catch (expected: SecurityException) {
                assertTrue(expected.message.orEmpty().contains("incorrect", ignoreCase = true))
            }
            assertArrayEquals(originalKey, sessionKey)
        } finally {
            sessionKey.fill(0)
            originalKey.fill(0)
            correct.fill('\u0000')
            incorrect.fill('\u0000')
            directory.deleteRecursively()
        }
    }

    @Test
    fun unreadableEncryptedEntryIsReportedAndCiphertextIsPreserved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = SrukryptStorage.vaultDirectory(context)
        directory.deleteRecursively()
        val dataKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        var preview: Bitmap? = null
        try {
            val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val output = ByteArrayOutputStream()
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
            image.recycle()

            val entryId = SrukryptVault.storeImage(
                context, dataKey, "Integrity test", "image/png", output.toByteArray()
            )
            val encryptedFile = File(directory, "$entryId.srk")
            val ciphertext = encryptedFile.readBytes()
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 0x01).toByte()
            encryptedFile.writeBytes(ciphertext)

            val load = SrukryptVault.loadPreviewsWithReport(context, dataKey)
            assertTrue(load.entries.isEmpty())
            assertEquals(1, load.unreadableEntries)
            assertTrue("Unreadable ciphertext must be preserved", encryptedFile.isFile)
        } finally {
            dataKey.fill(0)
            preview?.takeUnless { it.isRecycled }?.recycle()
            directory.deleteRecursively()
        }
    }

    @Test
    fun sruGenAttachmentCanBeExplicitlyImportedIntoSrukryptVault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vaultDirectory = SrukryptStorage.vaultDirectory(context)
        val chatDirectory = File(context.noBackupFilesDir, "srugen")
        vaultDirectory.deleteRecursively()
        chatDirectory.deleteRecursively()

        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        var sourceBytes: ByteArray? = null
        var chatBytes: ByteArray? = null
        var vaultBytes: ByteArray? = null
        var preview: Bitmap? = null
        try {
            val image = Bitmap.createBitmap(12, 10, Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream()
            check(image.compress(Bitmap.CompressFormat.PNG, 100, png))
            image.recycle()
            val bytes = png.toByteArray()
            sourceBytes = bytes

            val attachmentId = SruGenChatStore.saveAttachment(context, bytes, "image/png")
            chatBytes = SruGenChatStore.readAttachment(context, attachmentId)
            assertArrayEquals(bytes, chatBytes)

            val vaultId = SrukryptVault.storeImage(
                context, key, "SruGen image", "image/png", chatBytes
            )
            val loaded = SrukryptVault.loadPreviews(context, key)
            assertEquals(1, loaded.size)
            assertEquals("SruGen image", loaded.single().title)
            preview = loaded.single().thumbnail

            val restored = SrukryptVault.readImage(context, key, vaultId)
            assertEquals("SruGen image", restored.first)
            vaultBytes = restored.second
            assertArrayEquals(bytes, restored.second)

            val stillInChat = SruGenChatStore.readAttachment(context, attachmentId)
            try {
                assertArrayEquals(bytes, stillInChat)
            } finally {
                stillInChat.fill(0)
            }
        } finally {
            key.fill(0)
            sourceBytes?.fill(0)
            chatBytes?.fill(0)
            vaultBytes?.fill(0)
            preview?.takeUnless { it.isRecycled }?.recycle()
            vaultDirectory.deleteRecursively()
            chatDirectory.deleteRecursively()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry(SrukryptStorage.keyAlias("master"))
            }
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry("in.nispax.sruvik.srugen.chats.v1")
            }
        }
    }
}
