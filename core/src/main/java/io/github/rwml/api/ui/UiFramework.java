/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

/**
 * 扩展用于启动用户界面的唯一入口。
 *
 * <p>用户界面始终与平台相关：窗口、绘制表面、GL 上下文和事件源都由平台提供，桌面和 Android 宿主提供的内容不同。
 * 扩展通过 {@code ui-framework} 属性声明实现此接口的类；选定的界面框架及其依赖加载后，宿主会调用一次
 * {@link #start(UiHost)}。Android 会将其余扩展的加载延迟到游戏准备完成后。</p>
 *
 * <p>没有已加载的扩展声明界面框架时，不会启动任何框架。宿主仍可提供管理或恢复界面；桌面引导程序会回退到游戏原始启动器。</p>
 *
 * <p>该类必须能从声明它的扩展归档中访问；宿主自身提供的类也可使用，因此简单扩展可以请求平台界面，而无需携带平台代码。
 * 实现类通过无参构造函数实例化，且相对于宿主启动线程必须保持线程安全。</p>
 */
public interface UiFramework {

    /**
     * 启动用户界面。
     *
     * <p>Android 上会在加载线程调用此方法，桌面端则在启动器线程调用。实现类负责自身的启动和加载界面；Android 视图操作
     * 必须提交到主线程。通过 {@link UiHost#gameBoot()} 启动游戏时可传入进度监听器。</p>
     *
     * <p>宿主会报告此方法抛出的异常。Android 会显示内置诊断界面，桌面端则回退到游戏原始启动器。</p>
     *
     * @param host 加载声明此界面框架的扩展的宿主
     */
    void start(UiHost host) throws Exception;
}
