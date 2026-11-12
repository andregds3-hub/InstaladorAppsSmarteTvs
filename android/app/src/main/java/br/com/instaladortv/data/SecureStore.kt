package br.com.instaladortv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Pequenos blobs secretos (chaves privadas, certificados, tokens) guardados criptografados. */
interface BlobStore {
    fun read(name: String): ByteArray?
    fun write(name: String, data: ByteArray)
    fun delete(name: String)
}

/**
 * Arquivos em `filesDir/secure`, cada um cifrado com AES-GCM por uma chave que nunca sai do
 * Android Keystore. Desinstalar o app apaga tudo.
 */
class KeystoreBlobStore(context: Context) : BlobStore {
    private val dir = File(context.filesDir, "secure").apply { mkdirs() }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+"))) { "nome inválido: $name" }
        return File(dir, name)
    }

    @Synchronized
    override fun read(name: String): ByteArray? {
        val f = file(name)
        if (!f.exists()) return null
        val raw = f.readBytes()
        if (raw.size < 13) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return cipher.doFinal(raw, 12, raw.size - 12)
    }

    @Synchronized
    override fun write(name: String, data: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(cipher.iv + cipher.doFinal(data))
        if (!tmp.renameTo(file(name))) {
            file(name).delete()
            tmp.renameTo(file(name))
        }
    }

    @Synchronized
    override fun delete(name: String) {
        file(name).delete()
    }

    private companion object {
        const val ALIAS = "instaladortv-store"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Para testes JVM. */
class MemoryBlobStore : BlobStore {
    private val data = mutableMapOf<String, ByteArray>()
    override fun read(name: String) = data[name]
    override fun write(name: String, data: ByteArray) { this.data[name] = data }
    override fun delete(name: String) { data.remove(name) }
}
