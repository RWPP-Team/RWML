/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

/** 宿主所见的扩展生命周期状态。 */
public enum ExtensionState {
    /** 已发现归档，但尚未解析描述文件。 */
    DISCOVERED,
    /** 已解析描述文件，正在解析依赖关系图。 */
    RESOLVING,
    /** 正在从远程仓库获取构件。 */
    DOWNLOADING,
    /** 正在加载类并实例化入口点。 */
    LOADING,
    /** 已加载且 {@code onLoad} 已成功完成。 */
    ENABLED,
    /** 已持久化为禁用状态；宿主启动时不会加载它。 */
    DISABLED,
    /** 生命周期回调抛出了异常。 */
    FAILED,
    /** 当前 Java 运行时版本不满足扩展要求。 */
    INCOMPATIBLE
}