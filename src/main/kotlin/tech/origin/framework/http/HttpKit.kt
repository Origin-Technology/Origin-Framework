package tech.origin.framework.http

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cors.CORSConfig
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import tech.origin.framework.api.RespondResult
import java.net.URI

class HttpOptions {
    var engineHeader: String = "Origin Framework"
    var extraDefaultHeaders: Map<String, String> = emptyMap()

    /** 请求级拒绝钩子(如 IP 封禁), 返回 true 时以 403 RespondResult 结束请求 */
    var shouldReject: (suspend (ApplicationCall) -> Boolean)? = null

    /** 小于该字节数的响应不压缩 —— JSON API 对小包做 gzip 纯耗 CPU */
    var compressionMinimumSizeBytes: Long = 1024
}

fun Application.installFrameworkHttp(options: HttpOptions = HttpOptions()) {
    install(DefaultHeaders) {
        header("X-Engine", options.engineHeader)
        header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("X-Frame-Options", "DENY")
        options.extraDefaultHeaders.forEach { (k, v) -> header(k, v) }
    }
    options.shouldReject?.let { reject ->
        intercept(ApplicationCallPipeline.Plugins) {
            if (reject(call)) {
                call.respond(
                    HttpStatusCode.OK,
                    RespondResult.error(HttpStatusCode.Forbidden.value, "风险过高，当前 IP 已被临时限制")
                )
                finish()
            }
        }
    }
    install(Compression) {
        minimumSize(options.compressionMinimumSizeBytes)
    }
}

/**
 * 从 CSV 配置展开 CORS 白名单; 含 "*" 时放开为 anyHost。
 */
fun CORSConfig.allowOriginsFromCsv(
    csv: String,
    extraOrigins: Collection<String> = emptyList(),
    allowCredentials: Boolean = true,
    extraAllowedHeaders: Collection<String> = emptyList()
) {
    val configured = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if ("*" in configured) {
        anyHost()
    } else {
        (configured + extraOrigins)
            .mapNotNull { it.normalizeOrigin() }
            .distinct()
            .forEach { origin ->
                val uri = runCatching { URI(origin) }.getOrNull()
                if (uri?.host != null) {
                    val host = if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
                    allowHost(host, schemes = listOf(uri.scheme ?: "https"))
                }
            }
    }
    HttpMethod.DefaultMethods
        .filter { it != HttpMethod.Get && it != HttpMethod.Post && it != HttpMethod.Head }
        .forEach { allowMethod(it) }
    allowHeader(HttpHeaders.Authorization)
    extraAllowedHeaders.forEach { allowHeader(it) }
    allowNonSimpleContentTypes = true
    this.allowCredentials = allowCredentials
    allowSameOrigin = true
}

/** 路由级 CORS 安装入口 */
fun Route.installCors(configure: CORSConfig.() -> Unit) {
    install(CORS) { configure() }
}

private fun String?.normalizeOrigin(): String? {
    if (this == null) return null
    val trimmed = trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return null
    return "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
}
