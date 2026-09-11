package tech.origin.framework

import io.ktor.server.application.*
import tech.origin.framework.api.StatusPagesOptions
import tech.origin.framework.api.installFrameworkStatusPages
import tech.origin.framework.http.HttpOptions
import tech.origin.framework.http.installFrameworkHttp

/**
 * 框架一键装配(顺序即 Ktor 插件安装顺序):
 * Serialization → StatusPages → Monitoring → HTTP。
 *
 * 数据库 / JWT / CORS / IP 封禁等需要真实配置的模块由应用按需单独安装, 例如:
 *
 * ```
 * fun Application.module() {
 *     installOriginFramework(statusPagesOptions, httpOptions)
 *     installDatabase(dbConfig) {
 *         tables += AccountsService
 *         onReady += { seedRootAccount() }
 *     }
 *     installJwtAuth(jwtKit)
 *     installIpResolver("127.0.0.1,::1")
 * }
 * ```
 */
fun Application.installOriginFramework(
    statusPagesOptions: StatusPagesOptions = StatusPagesOptions(),
    httpOptions: HttpOptions = HttpOptions()
) {
    installFrameworkSerialization()
    installFrameworkStatusPages(statusPagesOptions)
    installFrameworkMonitoring()
    installFrameworkHttp(httpOptions)
}
