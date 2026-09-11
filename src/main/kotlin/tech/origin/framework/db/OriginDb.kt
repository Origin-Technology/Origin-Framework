package tech.origin.framework.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * 框架表基类: 统一 id 主键约定 + 挂起事务入口。
 *
 * 事务约定(重要):
 *  - dbQuery          : 顶层挂起入口, 一个业务动作一个事务, 协程切到 Dispatchers.IO 执行阻塞 JDBC
 *  - xxxInTransaction : 普通函数, 仅供已在事务内的代码组合调用(如"更新账户 + 写登录记录"两步同事务)
 *  - 禁止在 dbQuery 内再调用另一个挂起 dbQuery —— newSuspendedTransaction 会另开一条连接,
 *    PostgreSQL 下两个连接互相等待可能造成自锁
 */
abstract class OriginDb(name: String) : Table(name) {
    abstract val id: Column<*>
    final override val primaryKey: PrimaryKey by lazy { PrimaryKey(id) }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
