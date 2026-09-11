package tech.origin.framework.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.serialization.json.JsonElement

suspend fun ApplicationCall.jsonOk(data: JsonElement? = null) {
    respond(HttpStatusCode.OK, RespondResult.success(data = data))
}

suspend fun ApplicationCall.jsonError(code: HttpStatusCode, msg: String) {
    respond(code, RespondResult.error(code.value, msg))
}

// 用于服务层报错，例如登录失败的用户名或密码错误。
suspend fun ApplicationCall.jsonServiceError(errCode: Int, msg: String) {
    respond(HttpStatusCode.OK, RespondResult.error(errCode, msg))
}
