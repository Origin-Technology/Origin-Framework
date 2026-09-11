package tech.origin.framework.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.origin.framework.Log
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 声明式配置系统(kotlinx.serialization 实现):
 *
 * ```
 * object DbConfig : AbstractConfigurable("Database") {
 *     val url by setting("URL", "jdbc:postgresql://localhost:5432/app")
 *     val poolMaxSize by setting("Pool Max Size", 20)
 * }
 *
 * fun main() {
 *     ConfigManager.configurables += DbConfig
 *     ConfigManager.load()   // 读取 config.json, 缺省项用默认值并回写
 * }
 * ```
 */
open class Configurable(val name: String) {
    internal val settings = mutableListOf<Setting<*>>()
}

abstract class AbstractConfigurable(name: String) : Configurable(name)

sealed class Setting<T>(val name: String, default: T) {
    var value: T = default
}

internal class IntSetting(name: String, default: Int) : Setting<Int>(name, default)
internal class StringSetting(name: String, default: String) : Setting<String>(name, default)
internal class BooleanSetting(name: String, default: Boolean) : Setting<Boolean>(name, default)
internal class JsonSetting(name: String, default: JsonElement) : Setting<JsonElement>(name, default)

fun Configurable.setting(name: String, default: Int): ReadWriteProperty<Configurable, Int> =
    register(IntSetting(name, default))

fun Configurable.setting(name: String, default: String): ReadWriteProperty<Configurable, String> =
    register(StringSetting(name, default))

fun Configurable.setting(name: String, default: Boolean): ReadWriteProperty<Configurable, Boolean> =
    register(BooleanSetting(name, default))

fun Configurable.setting(name: String, default: JsonElement): ReadWriteProperty<Configurable, JsonElement> =
    register(JsonSetting(name, default))

private fun <T> Configurable.register(s: Setting<T>): ReadWriteProperty<Configurable, T> {
    settings.add(s)
    return object : ReadWriteProperty<Configurable, T> {
        override fun getValue(thisRef: Configurable, property: KProperty<*>): T = s.value
        override fun setValue(thisRef: Configurable, property: KProperty<*>, value: T) {
            s.value = value
        }
    }
}

object ConfigManager {
    val configurables = mutableListOf<Configurable>()
    private val configFile = File("config.json")
    private val prettyJson = Json { prettyPrint = true }

    fun load() {
        Log.info("加载位于 ${configFile.absolutePath} 的配置文件")
        if (!configFile.isFile) {
            if (configFile.exists()) configFile.deleteRecursively()
            configFile.createNewFile()
            save()
        }

        val root = Json.parseToJsonElement(configFile.readText(Charsets.UTF_8)).let { it as? JsonObject }
        root?.forEach { (groupName, groupElement) ->
            val configurable = configurables.find { it.name == groupName } ?: run {
                Log.warn("忽略未知配置组: $groupName")
                return@forEach
            }
            (groupElement as? JsonObject)?.forEach { (key, value) ->
                val setting = configurable.settings.find { it.name == key } ?: run {
                    Log.warn("忽略未知配置项: $groupName.$key")
                    return@forEach
                }
                runCatching {
                    when (setting) {
                        is IntSetting -> setting.value = value.jsonPrimitive.content.toInt()
                        is StringSetting -> setting.value = value.jsonPrimitive.content
                        is BooleanSetting -> setting.value = value.jsonPrimitive.booleanOrNull ?: return@runCatching
                        is JsonSetting -> setting.value = value
                    }
                }.onFailure {
                    Log.warn("配置项 $groupName.$key 解析失败(${it.message}), 使用默认值")
                }
            }
        }
        save()
    }

    fun save() {
        val root = buildJsonObject {
            configurables.forEach { configurable ->
                put(configurable.name, buildJsonObject {
                    configurable.settings.forEach { s ->
                        when (s) {
                            is IntSetting -> put(s.name, s.value)
                            is StringSetting -> put(s.name, s.value)
                            is BooleanSetting -> put(s.name, s.value)
                            is JsonSetting -> put(s.name, s.value)
                        }
                    }
                })
            }
        }
        configFile.writeText(prettyJson.encodeToString(JsonObject.serializer(), root))
    }
}
