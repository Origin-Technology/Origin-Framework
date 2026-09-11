package tech.origin.framework.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Date
import java.util.UUID

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val keyId: String,
    val validitySeconds: Long = 36_000,
    val refreshValiditySeconds: Long = 604_800
) {
    init {
        require(keyId.isNotBlank()) { "JwtConfig.keyId must not be blank" }
        require(secret.toByteArray(Charsets.UTF_8).size >= 64) {
            "JwtConfig.secret must contain at least 64 bytes"
        }
    }
}

/**
 * 双 token JWT 工具: access/refresh 用 token_type claim 区分。
 *
 * 实现要点:
 *  - Algorithm 与 Verifier 懒加载缓存为单例, 避免每次签发/校验重建
 *  - token_type 严格判等: access 与 refresh 不可互用, 缺失该 claim 的 token 一律拒绝
 */
class JwtKit(val config: JwtConfig) {
    companion object {
        const val TOKEN_TYPE_ACCESS = "access_token"
        const val TOKEN_TYPE_REFRESH = "refresh_token"
    }

    private val algorithm: Algorithm by lazy { Algorithm.HMAC512(config.secret) }
    private val cachedVerifier: JWTVerifier by lazy {
        JWT.require(algorithm)
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .build()
    }

    /** 暴露缓存的 Verifier 供 ktor-server-auth-jwt 的 verifier() 安装使用 */
    fun verifier(): JWTVerifier = cachedVerifier

    /** 签发 access + refresh 双 token, 形如 {"accessToken":..., "refreshToken":...} */
    fun signPair(subject: String) = buildJsonObject {
        put("accessToken", JsonPrimitive(sign(subject, TOKEN_TYPE_ACCESS, config.validitySeconds)))
        put("refreshToken", JsonPrimitive(sign(subject, TOKEN_TYPE_REFRESH, config.refreshValiditySeconds)))
    }

    fun sign(
        subject: String,
        tokenType: String = TOKEN_TYPE_ACCESS,
        validitySeconds: Long = config.validitySeconds
    ): String = JWT.create()
        .withKeyId(config.keyId)
        .withSubject(subject)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .withClaim("token_type", tokenType)
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + validitySeconds * 1000))
        .withNotBefore(Date())
        .withJWTId(UUID.randomUUID().toString())
        .sign(algorithm)

    /** 校验签名/时效并核对 token_type, 失败抛 JWTVerificationException / IllegalArgumentException */
    fun verify(token: String, expectedType: String): DecodedJWT {
        val jwt = cachedVerifier.verify(token)
        require(jwt.getClaim("token_type").asString() == expectedType) { "token type mismatch" }
        return jwt
    }

    fun verifyAccessToken(token: String): DecodedJWT = verify(token, TOKEN_TYPE_ACCESS)

    fun verifyRefreshToken(token: String): DecodedJWT = verify(token, TOKEN_TYPE_REFRESH)

    fun decode(token: String): DecodedJWT = JWT.decode(token)
}
