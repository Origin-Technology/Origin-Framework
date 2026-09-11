# origin-framework

Ktor + Exposed 后端框架：事务层、JWT 双 token 认证、参数校验、统一异常、统一返回、配置系统、IP 封禁。

```
tech.origin.framework
├── installOriginFramework()      # 一键装配: Serialization → StatusPages → Monitoring → HTTP
├── api/                          # 统一返回 / 统一异常 / 校验助手
│   ├── RespondResult + jsonOk/jsonError/jsonServiceError
│   ├── BusinessException / AuthenticationException / TooManyException / OAuthException
│   ├── installFrameworkStatusPages(options)   # 业务异常→HTTP 200+业务码, 4xx/5xx 全兜底
│   └── validateBody / checkHttpUrl / checkRedirectOrigins / RegexKit
├── db/                           # Exposed 事务层
│   ├── OriginDb (dbQuery = newSuspendedTransaction(Dispatchers.IO))
│   └── installDatabase(config) { tables += ...; onReady += ... }   # Hikari + 增量建表 + fail-fast
├── auth/
│   ├── JwtKit (双 token, Algorithm/Verifier 缓存, token_type 严格判等)
│   ├── installJwtAuth(kit)                    # access("auth-jwt") + refresh("refreshToken") 双 Provider
│   ├── signedCookie<MySession>(name, secret)  # HMAC 签名 Cookie Session
│   └── PasswordHasher (PBKDF2-120k + verifyAsync/hashAsync 跑 Dispatchers.Default)
├── config/                       # 声明式配置: object XxxConfig : AbstractConfigurable("节") + setting 委托
├── http/
│   ├── installFrameworkHttp(options)          # DefaultHeaders/Compression(minSize)/请求拒绝钩子
│   ├── installIpResolver(csv) + call.ip       # 可信代理列表解析一次缓存
│   ├── call.getTokenOrNull()/requireToken()   # null 安全, 缺令牌→401 而非 500
│   └── corsConfig.allowOriginsFromCsv(csv)
├── security/
│   └── IpBanGuard (Jedis 全部 IO 派发 + 本地 TTL 缓存 + 降级本地计数)
└── launchPeriodic(period) { ... }             # 周期任务, 随应用生命周期取消
```

## 接入方式

**方式 A：本地发布**

```bash
cd origin-framework && ./gradlew publishToMavenLocal
```

应用 `build.gradle.kts`：

```kotlin
repositories { mavenLocal() }
dependencies { implementation("tech.origin:origin-framework:0.1.0") }
```

**方式 B：复合构建（不发布，源码联动）**

```kotlin
// 应用 settings.gradle.kts
includeBuild("../origin-framework")
```

## 快速开始

```kotlin
// 1. 声明配置(缺省值自动写入 config.json)
object DbConfig : AbstractConfigurable("Database") {
    val url by setting("URL", "jdbc:postgresql://localhost:5432/app")
    val user by setting("User", "postgres")
    val password by setting("Password", "")
}
object JwtSettings : AbstractConfigurable("Jwt") {
    val secret by setting("Secret", System.getenv("JWT_SECRET") ?: "x".repeat(64))
    val issuer by setting("Issuer", "https://example.com")
    val audience by setting("Audience", "https://api.example.com")
    val keyId by setting("Key ID", "app-hs512-v1")
}

fun main(args: Array<String>) {
    ConfigManager.configurables += listOf(DbConfig, JwtSettings)
    ConfigManager.load()
    embeddedServer(Netty, port = 8080) { module() }.start(wait = true)
}

fun Application.module() {
    val jwtKit = JwtKit(JwtConfig(JwtSettings.secret, JwtSettings.issuer, JwtSettings.audience, JwtSettings.keyId))
    val ipBanGuard = IpBanGuard(jedisPool, IpBanConfig()) { it.startsWith("/account/") }

    installOriginFramework(
        statusPagesOptions = StatusPagesOptions().apply {
            onSensitiveValidationFailure = {
                ipBanGuard.recordValidationFailure(it.request.path(), it.ip)
            }
        },
        httpOptions = HttpOptions().apply {
            shouldReject = { ipBanGuard.shouldReject(it.request.path(), it.ip) }
        }
    )
    installIpResolver("127.0.0.1,::1")
    installJwtAuth(jwtKit)
    installDatabase(
        DatabaseConfig(DbConfig.url, DbConfig.user, DbConfig.password)
    ) {
        tables += AccountsService          // 业务表(继承 OriginDb)
        onReady += { seedRootAccount() }   // 种子/迁移钩子
    }

    // 周期任务: 代替手写 while(true)+delay
    launchPeriodic(1.hours) { AccountsService.deleteExpired() }

    routing {
        route("/account") {
            post("/login") {
                val body = call.receive<LoginBody>()   // 校验规则在 RequestValidation 注册
                val account = AccountsService.findAccount(body.user, body.password)
                    ?: throw AuthenticationException("用户不存在")
                call.jsonOk(buildJsonObject {
                    put("tokens", jwtKit.signPair(account.uuid))
                })
            }
            authenticate("auth-jwt") {
                get("/me") {
                    val uuid = call.principal<JWTPrincipal>()!!.payload.subject
                    call.jsonOk(buildJsonObject { put("uuid", uuid) })
                }
            }
        }
    }
}

// 参数校验: 集中或分散注册均可
install(RequestValidation) {
    validate<LoginBody> { validateBody {
        check(RegexKit.isValidEmail(it.user))
        check(it.password.length in 8..64)
    } }
}

// 表定义
object AccountsService : OriginDb("accounts") {
    override val id = ulong("id").autoIncrement()
    private val uuid = uuid("uuid").clientDefault { UUID.randomUUID() }.uniqueIndex()

    suspend fun findAccount(user: String, password: String): Account? = dbQuery { /*...*/ }
}
```

## 事务约定（重要）

- `dbQuery`：顶层挂起入口，一个业务动作一个事务，内部协程切 `Dispatchers.IO` 执行阻塞 JDBC
- `xxxInTransaction`：普通函数，仅供已在事务内的代码组合调用（不新开连接）
- **禁止在 `dbQuery` 内再调用另一个挂起 `dbQuery`** —— `newSuspendedTransaction` 会另开一条连接，PostgreSQL 下两个连接互相等待可能自锁

## 设计与实现要点

| 模块 | 做法 |
|---|---|
| 连接层 | JDBC 经 Hikari 连接池取连接，事务复用连接；池大小/超时可配 |
| 建表 | 启动时批量计算"只增不改"的安全增量：不存在的表生成 CREATE，已存在的表只补缺失列；语句先整体落日志再执行，便于审计排障；任何失败直接终止启动（fail-fast） |
| Redis | Jedis 是同步客户端，所有调用经 `withContext(Dispatchers.IO)` 派发，不阻塞 Netty event loop；ban 判断用单次 `jedis.ttl` 同时拿到“是否被封”与“剩余时长”，命中正缓存 60s / 未命中负缓存 5s，正常流量几乎不逐请求打 Redis；Redis 不可用自动降级本地计数/封禁 |
| JWT | Algorithm 与 Verifier 懒加载单例；`token_type` 严格判等，access 与 refresh 不可互用（缺失 claim 一律拒绝）；secret 强制 ≥ 64 字节 |
| 密码 | PBKDF2-WithHmacSHA256 / 120k 迭代 / 常量时间比较；`verifyAsync/hashAsync` 跑 `Dispatchers.Default`；建议事务内只取 hash、事务外校验，不长时间占用连接 |
| HTTP | 请求级拒绝钩子（IP 封禁等）；压缩设最小阈值，小响应不 gzip；默认安全响应头（HSTS/nosniff/Referrer-Policy/X-Frame-Options） |
| IP 解析 | 仅当直连来自可信代理才信任 `X-Real-IP`/`X-Forwarded-For`，代理列表构造时解析一次缓存 |
| Token 提取 | `getTokenOrNull()` null 安全；`requireToken()` 缺令牌抛 `AuthenticationException` → 401 而非 NPE → 500 |
| 可观测 | CallId 透传上游 `X-Request-Id`/自动生成/回写响应头，MDC 注入 `call-id` 便于链路追踪 |
| 周期任务 | `launchPeriodic` 挂在 Application 协程上下文，应用停止自动取消，异常只记录不中断循环 |
| DI | 零依赖：`Application.attributes` 直接取 `database`，`call.ip` 取解析器 |

## 版本

Kotlin 2.2.21 / Ktor 3.3.2 / Exposed 0.61.0 / coroutines 1.10.2 / serialization 1.9.0 / HikariCP 7.0.2 / Jedis 5.1.0 / JVM 21。

> 注：建表使用 `SchemaUtils.createStatements` + `addMissingColumnsStatements` 组合（Exposed 0.61 已弃用 `createMissingTablesAndColumns`）；需要版本化迁移历史的项目建议经 `onReady` 钩子接入 Flyway/Liquibase。

## 鸣谢

早期学习与参考仓库：[ktor-server-sample](https://github.com/MicIsHere/ktor-server-sample)
