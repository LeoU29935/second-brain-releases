package com.leo.secondbrain.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CredentialStore(context: Context) {
    private val storage = ProtectedValueStore(context, "openai.credential", "com.leo.secondbrain.openai.v1")

    fun exists(): Boolean = storage.exists()

    fun save(value: String) {
        require(value.trim().matches(Regex("sk-[A-Za-z0-9_-]{15,480}")))
        storage.save(value.trim())
    }

    fun read(): String? = storage.read()
    fun remove() = storage.remove()
}

internal class ProtectedValueStore(context: Context, name: String, private val alias: String) {
    private val storage = AtomicFile(File(context.noBackupFilesDir, name))

    @Synchronized fun exists(): Boolean = storage.baseFile.isFile

    @Synchronized fun save(value: String) {
        val key = keyStore().getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val payload = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("payload", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        val output = storage.startWrite()
        try {
            output.write(payload.toString().toByteArray())
            storage.finishWrite(output)
        } catch (error: Exception) {
            storage.failWrite(output)
            throw error
        }
    }

    @Synchronized fun read(): String? {
        if (!exists()) return null
        val stored = JSONObject(storage.readFully().toString(Charsets.UTF_8))
        val key = keyStore().getKey(alias, null) as? SecretKey ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(stored.getString("iv"), Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(stored.getString("payload"), Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    @Synchronized fun remove() {
        storage.delete()
        keyStore().deleteEntry(alias)
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
