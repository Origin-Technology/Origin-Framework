package tech.origin.framework.api

import io.ktor.server.plugins.requestvalidation.*
import java.net.URI

/**
 * 校验助手: 在 RequestValidation 的 validate<T> 块里用 check 表达规则,
 * check 抛出的 IllegalStateException.message 会成为 Invalid 的提示文案。
 *
 * 用法:
 * ```
 * validate<LoginBody> { validateBody {
 *     check(RegexKit.isValidEmail(it.user)) { "邮箱格式不正确" }
 *     check(it.password.length in 8..64)
 * } }
 * ```
 */
fun validateBody(block: () -> Unit): ValidationResult =
    runCatching {
        block()
    }.fold(
        onSuccess = { ValidationResult.Valid },
        onFailure = { cause -> ValidationResult.Invalid(cause.message ?: "参数校验失败") }
    )

fun checkCaptchaToken(token: String) {
    check(token.length in 1..4096)
}

fun checkHttpUrl(value: String?) {
    if (value.isNullOrBlank()) return
    check(value.length <= 512)
    val uri = runCatching { URI(value) }.getOrNull()
    check(uri?.scheme == "http" || uri?.scheme == "https")
    check(!uri.host.isNullOrBlank())
}

fun checkRedirectOrigins(uris: List<String>?, max: Int = 20) {
    if (uris == null) return
    check(uris.size <= max)
    uris.forEach {
        check(it.length in 1..512)
        check(it.normalizeOrigin() != null) { "redirect uri 必须是 http(s)://host 形式" }
    }
}

/** 归一化为 scheme://host[:port], 非法输入返回 null */
fun String?.normalizeOrigin(): String? {
    if (this == null) return null
    val trimmed = trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return null
    return "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
}

/** 常用格式校验(密码强度等业务策略由接入方自行追加) */
object RegexKit {
    private val UUID_PATTERN =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val EMAIL_PATTERN = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun isValidUUID(value: String): Boolean = UUID_PATTERN.matches(value)

    fun isValidEmail(value: String): Boolean = value.length <= 254 && EMAIL_PATTERN.matches(value)

    fun isValidLength(value: String, min: Int, max: Int): Boolean = value.length in min..max
}
