<h1 align="center">RWML</h1>

<div align="center"><img alt="Tests: Gradle" src="https://img.shields.io/badge/tests-Gradle-blue.svg"> <img alt="License: RWPP-Team Shield" src="https://img.shields.io/badge/license-RWPP--Team%20Shield-blue.svg"> <img alt="Version: 1.0.0" src="https://img.shields.io/badge/version-1.0.0-green.svg"> <img alt="Language: Java" src="https://img.shields.io/badge/language-Java-orange.svg"></div>

<p align="center">🇺🇸 <a href="./README.md">English</a> | 🇨🇳 <a href="./README.zh.md">简体中文</a></p>

## 目录

[简介](#简介) · [安装](#安装) · [使用](#使用) · [API](#扩展-api) · [构建](#构建) · [贡献](#贡献) · [许可](#许可)

## 简介

RWML 是面向 Rusted Warfare 桌面端与 Android 的 mixin 扩展加载器，支持依赖排序、游戏类修改和自定义界面。

| 模块 | 职责 |
|---|---|
| `core` | 跨平台扩展 API、依赖解析与引导接口 |
| `desktop` | JVM 加载、类变换与桌面 builtin 界面 |
| `android` | 游戏 dex 准备、缓存、权限与 Android builtin 界面 |
| `example` | 示例扩展 |

## 安装

使用[构建命令](#构建)生成安装产物。

| 平台 | 要求 | 安装方式 |
|---|---|---|
| 桌面 | Java 8+；需要时自动下载 Java 25 | 用 `build/dist/game-lib.jar` 替换游戏的 `game-lib.jar` |
| Android | Android 8.0+（API 26） | 安装 `android/build/outputs/apk/debug/android-debug.apk` |

> [!IMPORTANT]
> 替换前备份原版 `game-lib.jar`。首次下载 Java 运行时需要联网。

## 使用

将扩展 jar 放入桌面游戏库旁的 `extensions/`，或 Android 的 `/storage/emulated/0/rustedWarfare/extensions`。Android 会先申请存储访问权限，再加载扩展。

扩展更新及启停设置在下次进入引导器加载时生效。Android 此时检查 SHA-256，重建变化的缓存。

## 扩展 API

在扩展工程中引用 core 和映射游戏库（附反编译源码包）：

```kotlin
repositories { mavenCentral() }
dependencies {
    compileOnly("io.github.rwpp-team:rwml-core:1.0.0")
    compileOnly("io.github.rwpp-team:game-lib:1.15")
}
```

在 jar 中添加 `META-INF/extension.properties`：

```properties
id=com.example.hello
mainClass=com.example.HelloExtension
```

```java
package com.example;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionContext;

public final class HelloExtension implements Extension {
    @Override
    public void onLoad(ExtensionContext context) {
        context.log("Hello RWML");
    }
}
```

| API／描述符字段 | 用途 |
|---|---|
| `Extension.onLoad(ExtensionContext)` | 扩展入口，每个已加载实例调用一次 |
| `ExtensionContext` | 私有数据目录、日志、类加载器与 mixin 桥接 |
| `dependsOn` / `loadAfter` / `loadBefore` | 硬依赖／推荐加载顺序 |
| `ui-framework` | 实现 `UiFramework.start(UiHost)` 的界面类 |
| `UiHost.gameBoot()` | 统一的游戏初始化与进度回调 |

只加载一个界面框架，外部框架优先，builtin 兜底。Android 在准备游戏 dex 前启动选中的框架；先绘制引导界面，再在工作线程启动 `GameBoot`，引导成功后使用游戏类。

使用 `compileOnly` 引用 core、游戏 API 和 ASM 注解，不要打包宿主库。Android 扩展还需同时携带可执行的 `classes.dex` 与 `.class` 元数据、mixin。参考[示例扩展](./example)、[公共 API](./core/src/main/java/io/github/rwml/api)及 [ASM-Framework](https://github.com/RELAY-CN/ASM-Framework)。

## 构建

需要 JDK 25 工具链、Android SDK 35、原版 `work/game-lib.jar` 和 `work/mappings` 映射。Windows 将 `./gradlew` 换为 `.\gradlew.bat`。

```bash
git submodule update --init --recursive
./gradlew publishGameLibForIde
./gradlew packageGameJar :android:assembleDebug :example:jar
```

`publishGameLibForIde` 通过 Vineflower 生成独立的 `build/libs/game-lib-named-sources.jar`，并将二进制与源码发布到 `build/local-maven`。在 IDEA／Android Studio 启用依赖源码下载并重载 Gradle，或手动关联源码 jar。

## 贡献

欢迎提交 issue 和 PR；较大改动请先开 issue 讨论。[QQ群 1004399208](https://qm.qq.com/q/auLINpWtr4)。

```bash
./gradlew :core:check :desktop:test :android:testDebugUnitTest verifyGameSources
```

## 许可

[RWPP-Team Shield](./LICENSE.md)；`game-lib`：[CC0](https://creativecommons.org/publicdomain/zero/1.0/)。第三方组件：[ASM-Framework](https://github.com/RELAY-CN/ASM-Framework/)、[dx](https://github.com/JakeWharton/dalvik-dx)（[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)）。
