package tech.origin.framework

import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * 周期后台任务(清理/同步等)。
 *
 * 挂在 Application 协程上下文上, 应用停止时自动取消;
 * 异常只记录不中断循环, 不需要调用方再写 while(true) + try/catch。
 */
fun Application.launchPeriodic(
    period: Duration,
    firstDelay: Duration = Duration.ZERO,
    block: suspend () -> Unit,
): Job = CoroutineScope(coroutineContext).launch {
    delay(firstDelay)
    while (isActive) {
        runCatching { block() }.onFailure {
            Log.error("周期任务执行失败", it)
        }
        delay(period)
    }
}
