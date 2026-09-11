package tech.origin.framework.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-WithHmacSHA256 密码哈希(120k 迭代), 常量时间比较。
 *
 * 使用约定:
 *  - hash/verify 是 CPU 密集操作(约 50~100ms), *Async 变体跑在 Dispatchers.Default
 *  - 登录校验时先在事务内取出存量 hash, 再到事务外 verify, 避免长时间占用数据库连接
 */
object PasswordHasher {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PREFIX = "pbkdf2"
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val GENERATED_PASSWORD_BYTES = 24
    private val secureRandom = SecureRandom()

    fun generateSecurePassword(): String {
        val bytes = ByteArray(GENERATED_PASSWORD_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        val hash = derive(password, salt, ITERATIONS)
        return listOf(
            PREFIX,
            ITERATIONS.toString(),
            Base64.getEncoder().withoutPadding().encodeToString(salt),
            Base64.getEncoder().withoutPadding().encodeToString(hash)
        ).joinToString("$")
    }

    suspend fun hashAsync(password: String): String =
        withContext(Dispatchers.Default) { hash(password) }

    fun verify(password: String, stored: String): Boolean {
        val normalized = stored.trimEnd()
        if (!isHash(normalized)) return password == normalized

        val parts = normalized.split("$")
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val actual = derive(password, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    suspend fun verifyAsync(password: String, stored: String): Boolean =
        withContext(Dispatchers.Default) { verify(password, stored) }

    fun isHash(stored: String): Boolean = stored.trimEnd().startsWith("$PREFIX$")

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }
}
