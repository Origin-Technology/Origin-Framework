package tech.origin.framework

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import java.util.UUID

fun Application.installFrameworkMonitoring() {
    install(CallId) {
        // 优先透传上游 X-Request-Id, 没有则生成, 并回写到响应头方便链路追踪
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        callIdMdc("call-id")
    }
}
