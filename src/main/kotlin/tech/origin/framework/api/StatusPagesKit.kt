package tech.origin.framework.api

import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.origin.framework.Log

class StatusPagesOptions {
    /**
     * 参数校验失败(JSON 解析失败/RequestValidation 不通过)时的记录钩子,
     * 例如接入 IP 封禁计数。返回 true 表示风险过高需以 403 拒绝。
     */
    var onSensitiveValidationFailure: (suspend (ApplicationCall) -> Boolean)? = null

    /** 未预期异常的兜底钩子(如 Sentry 上报), 返回值作为错误追踪 id 放进 data.eId */
    var onUnexpectedError: (suspend (ApplicationCall, Throwable) -> String?)? = null
}

/**
 * 统一异常 → 统一返回:
 *  - 业务异常一律 HTTP 200, 业务码放 RespondResult.code
 *  - OAuthException 按 OAuth 协议以标准 error JSON 响应
 *  - 4xx/5xx 状态码全部包装为 RespondResult 格式, 未列出的状态码走兜底
 */
fun Application.installFrameworkStatusPages(options: StatusPagesOptions = StatusPagesOptions()) {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause.cause is JsonConvertException) {
                val banned = options.onSensitiveValidationFailure?.invoke(call) ?: false
                call.respond(
                    HttpStatusCode.OK,
                    RespondResult.error(
                        if (banned) HttpStatusCode.Forbidden.value else HttpStatusCode.NotAcceptable.value,
                        if (banned) "风险过高，当前 IP 已被临时限制" else "参数校验失败"
                    )
                )
                return@exception
            }
            when (cause) {
                is AuthenticationException -> call.respond(
                    HttpStatusCode.OK,
                    RespondResult.error(HttpStatusCode.Unauthorized.value, cause.message)
                )

                is TooManyException -> call.respond(
                    HttpStatusCode.OK,
                    RespondResult.error(HttpStatusCode.BadRequest.value, cause.message)
                )

                is NotFoundException -> call.respond(
                    HttpStatusCode.OK,
                    RespondResult.error(HttpStatusCode.NotFound.value, cause.message ?: "找不到数据")
                )

                is BadRequestException -> call.respond(
                    HttpStatusCode.OK,
                    RespondResult.error(HttpStatusCode.BadRequest.value, cause.message ?: "非法请求")
                )

                is TimeoutException, is SocketTimeoutException -> call.respond(
                    HttpStatusCode.OK,
                    RespondResult.error(HttpStatusCode.RequestTimeout.value, "服务忙")
                )

                is OAuthException -> call.respond(
                    cause.httpStatus,
                    buildJsonObject {
                        put("error", cause.errorCode)
                        put("error_description", cause.message)
                    }
                )

                is RequestValidationException -> {
                    val banned = options.onSensitiveValidationFailure?.invoke(call) ?: false
                    call.respond(
                        HttpStatusCode.OK,
                        RespondResult.error(
                            if (banned) HttpStatusCode.Forbidden.value else HttpStatusCode.NotAcceptable.value,
                            if (banned) "风险过高，当前 IP 已被临时限制" else "参数校验失败"
                        )
                    )
                }

                else -> {
                    Log.error("发生错误", cause)
                    val eId = options.onUnexpectedError?.invoke(call, cause)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RespondResult.error(HttpStatusCode.InternalServerError.value, "服务异常", eId)
                    )
                }
            }
        }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.response.header(HttpHeaders.RetryAfter, "60")
            call.respond(HttpStatusCode.TooManyRequests, RespondResult.error(HttpStatusCode.TooManyRequests.value, "服务忙"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respond(HttpStatusCode.MethodNotAllowed, RespondResult.error(HttpStatusCode.MethodNotAllowed.value, "请求方式不允许"))
        }
        status(HttpStatusCode.NotAcceptable) { call, _ ->
            call.respond(HttpStatusCode.NotAcceptable, RespondResult.error(HttpStatusCode.NotAcceptable.value, "不接受"))
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, RespondResult.error(HttpStatusCode.NotFound.value, "找不到数据"))
        }
        status(HttpStatusCode.UnsupportedMediaType) { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, RespondResult.error(HttpStatusCode.UnsupportedMediaType.value, "媒体类型不支持"))
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(HttpStatusCode.Unauthorized, RespondResult.error(HttpStatusCode.Unauthorized.value, "未验证"))
        }
        HttpStatusCode.allStatusCodes
            .asSequence()
            .filter { it.value in 400..599 }
            .filter { it != HttpStatusCode.TooManyRequests }
            .filter { it != HttpStatusCode.MethodNotAllowed }
            .filter { it != HttpStatusCode.NotFound }
            .filter { it != HttpStatusCode.NotAcceptable }
            .filter { it != HttpStatusCode.UnsupportedMediaType }
            .filter { it != HttpStatusCode.Unauthorized }
            .forEach {
                status(it) { call, _ ->
                    call.respond(it, RespondResult.error(it.value, it.description))
                }
            }
    }
}
