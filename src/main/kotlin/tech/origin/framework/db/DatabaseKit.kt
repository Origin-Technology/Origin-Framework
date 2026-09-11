package tech.origin.framework.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.transactions.transaction
import tech.origin.framework.Log

data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val poolName: String = "origin-db",
    val maximumPoolSize: Int = 20,
    val minimumIdle: Int = 5,
    val connectionTimeoutMs: Long = 5_000
)

class DatabaseInstaller {
    /** 业务表统一注册, 启动时一次批量建表/补列 */
    val tables = mutableListOf<Table>()

    /** 建表完成后的种子/迁移钩子, 任何钩子抛异常都会终止启动 */
    val onReady = mutableListOf<(Database) -> Unit>()
}

private val DatabaseKey = AttributeKey<Database>("origin-framework-database")

/**
 * 数据库接入: Hikari 连接池 + 增量建表 + 种子钩子。
 *
 * 实现要点:
 *  - 经 DataSource 连接池取连接, 事务复用连接
 *  - 建表为"只增不改"的安全增量: 不存在的表生成 CREATE, 已存在的表只补缺失列;
 *    语句先整体落日志再执行, 便于审计/排障
 *  - 全程 fail-fast: 任何一步失败直接抛出终止启动, 不带着不可用的数据层对外服务
 */
fun Application.installDatabase(config: DatabaseConfig, configure: DatabaseInstaller.() -> Unit = {}): Database {
    val installer = DatabaseInstaller().apply(configure)

    val dataSource = HikariDataSource(
        HikariConfig().apply {
            poolName = config.poolName
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            maximumPoolSize = config.maximumPoolSize
            minimumIdle = config.minimumIdle
            connectionTimeout = config.connectionTimeoutMs
        }
    )
    val database = Database.connect(dataSource)
    attributes.put(DatabaseKey, database)

    Log.info("批量初始化数据库表 ${installer.tables.size} 张")
    // 增量建表两步(Exposed 已弃用 createMissingTablesAndColumns, 此为其内部同款组合):
    //   1) 不存在的表 → CREATE 语句
    //   2) 已存在的表 → 只算缺失列的 ALTER 语句(安全增量, 不改不删已有结构)
    // 语句先拿到手再执行 —— 可以日志留痕、审计、按环境过滤
    transaction(database) {
        val (existing, missing) = installer.tables.partition { it.exists() }
        val statements = SchemaUtils.createStatements(*missing.toTypedArray()) +
            SchemaUtils.addMissingColumnsStatements(*existing.toTypedArray())
        if (statements.isNotEmpty()) {
            Log.info("执行 ${statements.size} 条增量建表语句:\n" + statements.joinToString("\n"))
            statements.forEach { exec(it) }
        }
    }
    installer.onReady.forEach { hook -> hook(database) }
    Log.info("数据库初始化完成")
    return database
}

/** 取出 installDatabase 注册的 Database 实例(不依赖任何 DI 容器) */
val Application.database: Database get() = attributes[DatabaseKey]
