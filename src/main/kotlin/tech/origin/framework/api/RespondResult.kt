package tech.origin.framework.api

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 统一返回体。data 刻意保持 JsonElement:
 * 由业务层手工 buildJsonObject 组装, 不暴露领域模型的真实结构。
 */
@Serializable
data class RespondResult(
    val isSuccess: Boolean,
    val code: Int,
    val msg: String,
    @Contextual
    val data: JsonElement? = null
) {
    companion object {
        fun success(data: JsonElement? = null, msg: String = "操作成功完成") =
            RespondResult(true, 200, msg, data)

        fun error(code: Int, msg: String, eId: String? = null) =
            RespondResult(false, code, msg, if (eId == null) null else buildJsonObject { put("eId", eId) })
    }
}
