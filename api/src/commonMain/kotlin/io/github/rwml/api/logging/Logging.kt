/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.logging

import li.songe.codeorigin.CallSite
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 取得以调用点为名的日志器。
 *
 * CodeOrigin 在编译期填入调用者的包名、类型名与文件名，因此顶层 Kotlin 文件也能得到有意义的名称，
 * 调用处无需再手写类名。
 */
fun logger(
    @CallSite("{package}") packageName: String = "",
    @CallSite("{type}") typeName: String = "",
    @CallSite("{file}") fileName: String = "",
): Logger {
    val owner = typeName.ifEmpty { fileName.removeSuffix(".kt") }
    val name = listOf(packageName, owner).filter(String::isNotEmpty).joinToString(".")
    return LoggerFactory.getLogger(name.ifEmpty { "rwml" })
}
