package com.pocket.agent.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感配置的落地加密：用 Android Keystore 里的 AES-256-GCM 密钥加密 API Key。
 *
 * 为什么必须做：`config.json` 是 `filesDir` 下的普通文件，之前 API Key 以明文写在里面。
 * 应用私有的 0600 只挡住「别的应用」，挡不住 root、ADB 备份、厂商云备份、
 * 以及任何拿到 /data 镜像的人。Keystore 的密钥由 TEE/StrongBox 持有，
 * 私钥材料不出安全硬件——把文件拷走也解不开。
 *
 * 与纯软件的 EncryptedSharedPreferences 相比：本工程运行时零第三方依赖，
 * 直接调 Keystore + javax.crypto 即可，不引入 androidx.security。
 *
 * 格式：`v1:` + Base64(IV[12] || ciphertext)。无前缀的值视为明文（旧配置兼容）。
 */
object SecretsVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "pocketmind-config-v1"
    private const val PREFIX = "v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户认证：定时任务在锁屏后也要能读到 key 调用模型。
                // 代价是「拿到已解锁设备的人」能解密——但这已经超出本应用的威胁模型。
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    /**
     * Keystore 是否真的可用（能生成/取到密钥）。
     *
     * 为什么需要单独暴露：seal() 在失败时静默降级成明文，这是个正确的可用性
     * 取舍，但如果用户以为"已加密"而实际没有，那是更糟的欺骗。诊断页据此
     * 如实显示 encrypted / plaintext，由用户自己判断风险。
     */
    fun isAvailable(): Boolean = try {
        key() != null
    } catch (_: Exception) {
        false
    }

    /** 加密；Keystore 不可用时返回原文（降级而不是让用户无法保存配置）。 */
    fun seal(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        } catch (_: Exception) {
            plain
        }
    }

    /** 解密；非 v1 前缀原样返回（旧明文配置），解密失败返回空串（当作未配置）。 */
    fun open(stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return stored
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
            )
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
