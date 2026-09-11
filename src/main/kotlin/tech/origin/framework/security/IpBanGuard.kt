package tech.origin.framework.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import tech.origin.framework.Log
import java.util.concurrent.ConcurrentHashMap

data class IpBanConfig(
    val enabled: Boolean = true,
    val failureThreshold: Int = 5,
    val failureWindowSeconds: Int = 600,
    val banSeconds: Int = 1800
)

/**
 * 敏感接口参数校验失败的 IP 封禁:
 *
 * 实现要点:
 *  - 所有 Jedis 调用经 withContext(Dispatchers.IO) 派发, 不阻塞 Netty event loop
 *  - isBanned 用一次 jedis.ttl 同时拿到“是否被封”与“剩余时长”; 命中后本地缓存(封顶 60s),
 *    未命中本地负缓存 5s, 正常流量下几乎不再每请求打 Redis
 *  - 敏感路径列表由构造参数注入
 *  - Redis 不可用时自动降级本地计数/本地封禁
 *
 * 接线: HttpOptions.shouldReject = { ipBanGuard.shouldReject(it.request.path(), it.ip) }
 *      + StatusPagesOptions.onSensitiveValidationFailure =
 *              { ipBanGuard.recordValidationFailure(it.request.path(), it.ip) }
 */
class IpBanGuard(
    private val jedisPool: JedisPool,
    private val config: IpBanConfig = IpBanConfig(),
    private val isSensitivePath: (String) -> Boolean = { false }
) {
    private val localFailures = ConcurrentHashMap<String, LocalCounter>()
    private val localBans = ConcurrentHashMap<String, Long>()
    private val banCheckCache = ConcurrentHashMap<String, Long>()

    suspend fun shouldReject(path: String, ip: String): Boolean {
        if (!config.enabled) return false
        if (!isSensitivePath(path)) return false
        return isBanned(ip)
    }

    suspend fun recordValidationFailure(path: String, ip: String): Boolean {
        if (!config.enabled) return false
        if (!isSensitivePath(path)) return false
        if (isBanned(ip)) return true

        val failures = redisCall { jedis ->
            val key = failureKey(ip)
            val count = jedis.incr(key)
            if (count == 1L) jedis.expire(key, config.failureWindowSeconds.toLong())
            count
        } ?: run {
            Log.warn("记录敏感请求体验证失败到 Redis 失败，使用本地计数")
            recordLocalFailure(ip)
        }

        if (failures >= config.failureThreshold) {
            ban(ip)
            return true
        }
        return false
    }

    private suspend fun isBanned(ip: String): Boolean {
        evictExpiredCaches()
        clearExpiredLocalBan(ip)
        if (localBans.containsKey(ip)) return true
        val negativeUntil = banCheckCache[ip]
        if (negativeUntil != null && negativeUntil > System.currentTimeMillis()) return false

        // jedis.ttl: -2 = key 不存在, >0 = 剩余秒数; 一次往返同时拿到“是否被封”与“剩余时长”
        val remainingSeconds = redisCall { jedis -> jedis.ttl(banKey(ip)) } ?: -2
        if (remainingSeconds > 0) {
            localBans[ip] = System.currentTimeMillis() + minOf(remainingSeconds * 1000L, BAN_POSITIVE_CACHE_MS)
            return true
        }
        banCheckCache[ip] = System.currentTimeMillis() + BAN_NEGATIVE_CACHE_MS
        return false
    }

    private suspend fun ban(ip: String) {
        val written = redisCall { jedis ->
            jedis.setex(banKey(ip), config.banSeconds.toLong(), "sensitive_validation_failure")
            jedis.del(failureKey(ip))
            true
        }
        if (written == null) {
            Log.warn("写入敏感请求体验证失败 IP 封禁到 Redis 失败，使用本地封禁")
            localBans[ip] = System.currentTimeMillis() + config.banSeconds * 1000L
            localFailures.remove(ip)
        }
    }

    /** Jedis 是同步阻塞客户端, 必须切到 IO 线程池执行, 避免阻塞 Netty event loop; 失败返回 null 由调用方降级 */
    private suspend fun <T> redisCall(block: (Jedis) -> T): T? =
        withContext(Dispatchers.IO) {
            runCatching {
                jedisPool.resource.use(block)
            }.onFailure {
                Log.warn("访问 Redis 失败: ${it.message}")
            }.getOrNull()
        }

    private fun recordLocalFailure(ip: String): Long {
        val now = System.currentTimeMillis()
        val windowMillis = config.failureWindowSeconds * 1000L
        val counter = localFailures.compute(ip) { _, current ->
            if (current == null || current.expiresAt <= now) {
                LocalCounter(count = 1, expiresAt = now + windowMillis)
            } else {
                current.copy(count = current.count + 1)
            }
        } ?: LocalCounter(count = 1, expiresAt = now + windowMillis)
        return counter.count.toLong()
    }

    private fun clearExpiredLocalBan(ip: String) {
        val expiresAt = localBans[ip] ?: return
        if (expiresAt <= System.currentTimeMillis()) localBans.remove(ip)
    }

    /**
     * 本地缓存按 IP 基数增长, 没有自然淘汰路径(banCheckCache 的 key 不会消失)。
     * 阈值触发一次过期清扫, 把均摊成本压回 O(1), 防止长周期运行下的慢泄漏。
     */
    private fun evictExpiredCaches() {
        val now = System.currentTimeMillis()
        if (banCheckCache.size >= CACHE_EVICTION_THRESHOLD) {
            banCheckCache.entries.removeIf { it.value <= now }
        }
        if (localBans.size >= CACHE_EVICTION_THRESHOLD) {
            localBans.entries.removeIf { it.value <= now }
        }
        if (localFailures.size >= CACHE_EVICTION_THRESHOLD) {
            localFailures.entries.removeIf { it.value.expiresAt <= now }
        }
    }

    private fun failureKey(ip: String): String = "$KEY_PREFIX:sensitive-validation-failure:$ip"

    private fun banKey(ip: String): String = "$KEY_PREFIX:ip-ban:$ip"

    private data class LocalCounter(
        val count: Int,
        val expiresAt: Long
    )

    private companion object {
        const val KEY_PREFIX = "origin:security"
        const val BAN_NEGATIVE_CACHE_MS = 5_000L
        const val BAN_POSITIVE_CACHE_MS = 60_000L
        const val CACHE_EVICTION_THRESHOLD = 10_000
    }
}
