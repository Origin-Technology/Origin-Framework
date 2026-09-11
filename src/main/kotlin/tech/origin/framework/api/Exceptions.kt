package tech.origin.framework.api

import io.ktor.http.HttpStatusCode

/** 业务异常基类: HTTP 恒 200, 业务码放 RespondResult.code */
open class BusinessException(
    val code: Int,
    override val message: String,
) : RuntimeException(message)

/** 认证/授权失败 → body code=401 */
class AuthenticationException(override val message: String = "验证失败") : RuntimeException(message)

/** 数据量超限 → body code=400 */
class TooManyException(override val message: String = "数据过多") : RuntimeException(message)

/** OAuth 协议错误: 以标准 error/error_description JSON + 指定 HTTP 状态响应 */
class OAuthException(
    val httpStatus: HttpStatusCode,
    val errorCode: String,
    override val message: String,
) : RuntimeException(message)
