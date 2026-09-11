package tech.origin.framework.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import tech.origin.framework.api.RespondResult

/**
 * 安装 JWT 双 Provider 认证:
 *  - access  : 默认 Provider, 路由端 authenticate { } 或 authenticate("auth-jwt")
 *  - refresh : 固定名 "refreshToken"(可改), 用于刷新令牌接口
 */
fun Application.installJwtAuth(
    kit: JwtKit,
    accessProviderName: String? = "auth-jwt",
    refreshProviderName: String = "refreshToken"
) {
    install(Authentication) {
        jwt(accessProviderName) {
            verifier(kit.verifier())
            validate { credential ->
                val type = credential.payload.getClaim("token_type").asString()
                if (type == JwtKit.TOKEN_TYPE_ACCESS) {
                    JWTPrincipal(credential.payload)
                } else {
                    respond(
                        HttpStatusCode.OK,
                        RespondResult.error(HttpStatusCode.Unauthorized.value, "令牌错误或已过期")
                    )
                    null
                }
            }
        }
        jwt(refreshProviderName) {
            verifier(kit.verifier())
            validate { credential ->
                val type = credential.payload.getClaim("token_type").asString()
                if (type == JwtKit.TOKEN_TYPE_REFRESH) {
                    JWTPrincipal(credential.payload)
                } else {
                    respond(
                        HttpStatusCode.OK,
                        RespondResult.error(HttpStatusCode.Unauthorized.value, "令牌错误或已过期")
                    )
                    null
                }
            }
        }
    }
}

/**
 * HMAC 签名 Cookie Session: 默认 Secure/HttpOnly/SameSite=Lax。
 * 用法: install(Sessions) { signedCookie<MySession>("MY_SESSION", secret) }
 */
inline fun <reified T : Any> SessionsConfig.signedCookie(
    name: String,
    secret: String,
    secure: Boolean = true,
    httpOnly: Boolean = true
) {
    cookie<T>(name) {
        cookie.path = "/"
        cookie.secure = secure
        cookie.httpOnly = httpOnly
        cookie.extensions["SameSite"] = "lax"
        transform(SessionTransportTransformerMessageAuthentication(secret.toByteArray()))
    }
}
