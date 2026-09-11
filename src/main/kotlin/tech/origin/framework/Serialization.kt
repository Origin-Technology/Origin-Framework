package tech.origin.framework

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
fun Application.installFrameworkSerialization() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = false
            coerceInputValues = false
            isLenient = true
            allowTrailingComma = false
            explicitNulls = true
        })
    }
}
