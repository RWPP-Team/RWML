/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

/**
 * 当前运行的 {@link RwmlRuntime}，以及扩展代码访问它的方式。
 *
 * <p>宿主启动扩展系统时会在此安装运行时。扩展通常从 mixin 中通过 {@link #get()} 读取它：此调用与平台无关，
 * 也是注入到游戏中的代码返回宿主的唯一受支持途径。</p>
 *
 * <p>访问器特意设计为普通类的静态方法。扩展会以 class 文件形式随应用进入 Android，再由 APK 携带的 dx 转为 dex；
 * 该 dx 早于 Java 8 的接口静态方法特性，因此若在接口中声明静态方法，扩展将无法转换为 dex。</p>
 */
public final class Rwml {

    private static volatile RwmlRuntime runtime;

    private Rwml() {
    }

    /** @return 宿主安装的运行时；尚未安装时为 {@code null} */
    public static RwmlRuntime get() {
        return runtime;
    }

    /**
     * 将 {@code runtime} 设为 {@link #get()} 返回的运行时；传入 {@code null} 会移除它。
     * 此方法由宿主调用，扩展只能读取运行时。
     */
    public static void install(RwmlRuntime runtime) {
        Rwml.runtime = runtime;
    }
}
