package tech.origin.framework.http

import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.util.AttributeKey
import tech.origin.framework.api.AuthenticationException

/**
 * 客户端 IP 解析: 仅当直连来自可信代理时才信任 X-Real-IP / X-Forwarded-For。
 * 可信代理列表在构造时解析一次缓存。
 */
class IpResolver(trustedProxyCsv: String) {
    private val trustedProxies: Set<String> = trustedProxyCsv
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun resolve(call: ApplicationCall): String {
        val remoteHost = call.request.origin.remoteHost
        if (remoteHost !in trustedProxies) return remoteHost

        return call.request.headers["X-Real-IP"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: call.request.headers["X-Forwarded-For"]
                ?.substringBefore(',')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: remoteHost
    }
}

private val IpResolverKey = AttributeKey<IpResolver>("origin-framework-ip-resolver")

fun Application.installIpResolver(trustedProxyCsv: String) {
    attributes.put(IpResolverKey, IpResolver(trustedProxyCsv))
}

val ApplicationCall.ip: String
    get() = application.attributes[IpResolverKey].resolve(this)

/** null 安全的 Bearer token 提取; requireToken 缺令牌时抛 AuthenticationException → 401 */
fun ApplicationCall.getTokenOrNull(): String? =
    request.headers["Authorization"]
        ?.takeIf { it.startsWith("Bearer ") }
        ?.substringAfter("Bearer ")

fun ApplicationCall.requireToken(): String =
    getTokenOrNull() ?: throw AuthenticationException("缺少令牌")

fun ApplicationCall.getUA(): String = request.headers["User-Agent"] ?: ""

fun ApplicationCall.getReferer(): String = request.headers["Referer"] ?: ""
