package tech.origin.framework

import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val lazyLogger by lazy {
    LoggerFactory.getLogger("OriginFramework")
}

/** 框架统一日志门面, 具体实现由接入方选择(log4j2/logback 等) */
object Log : Logger by lazyLogger
