/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.asm;

import io.github.rwml.bootstrap.Relauncher;

import java.lang.instrument.Instrumentation;

/**
 * 持有桌面端转换器和 Instrumentation。转换失败时会通知恢复逻辑，并阻止 JVM 定义未修补的类。已注册且已经加载的目标类可以重新转换。
 */
public final class RwmlAgent {

    private static volatile Instrumentation instrumentation;
    private static boolean installed;

    private RwmlAgent() {
    }

    /** JVM {@code -javaagent} 入口点。 */
    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        install(inst);
        markInstalled();
    }

    /** JVM 运行时 {@code VirtualMachine.attach} 入口点。 */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        install(inst);
        markInstalled();
    }

    private static synchronized void install(Instrumentation inst) {
        if (!installed) {
            // 保留框架显式调用的 AsmCore.transform API，同时避免安装第二个转换器。
            new kim.der.asm.agent.AsmBootstrap();
            inst.addTransformer(new FailureAwareTransformer(MixinSupport.instance()::failed), true);
            installed = true;
        }
    }

    /**
     * 发布转换器已启用的状态。
     *
     * <p>此状态使用系统属性而非静态字段，因为读取方是 Java 8 启动器，它会在此类加载之前运行。{@code Bootstrap} 会据此判断是否仍需使用
     * {@code -javaagent} 重启游戏；由于 {@code premain} 会在 {@code main} 启动前运行，游戏入口开始执行时即可获知结果。</p>
     */
    private static void markInstalled() {
        System.setProperty(Relauncher.AGENT_INSTALLED_PROPERTY, "true");
    }

    /** @return 正在运行的 Instrumentation；Agent 从未启动时返回 {@code null} */
    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    /** 重新转换已加载的类，使首次加载后注册的 Mixin 也能生效。 */
    public static void retransform(Class<?>... classes) throws Exception {
        if (instrumentation != null) {
            instrumentation.retransformClasses(classes);
        }
    }
}
