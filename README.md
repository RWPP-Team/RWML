<h1 align="center">RWML</h1>

<div align="center"><img alt="Tests: Gradle" src="https://img.shields.io/badge/tests-Gradle-blue.svg"> <img alt="License: RWPP-Team Shield" src="https://img.shields.io/badge/license-RWPP--Team%20Shield-blue.svg"> <img alt="Version: 1.0.3" src="https://img.shields.io/badge/version-1.0.3-green.svg"> <img alt="Language: Java" src="https://img.shields.io/badge/language-Java-orange.svg"></div>

<p align="center">🇨🇳 简体中文 | 🇺🇸 <a href="./README.en.md">English</a></p>

## 目录

[简介](#简介) · [安装](#安装) · [使用](#使用) · [扩展开发](#扩展开发) · [构建](#构建) · [致谢](#致谢) · [许可](#许可)

## 简介

RWML 是 Rusted Warfare 桌面端与 Android 的 mixin 扩展加载器。它按依赖顺序加载扩展，在类加载时改写游戏类，并提供可替换的界面入口。

| 模块           | 职责                                       |
|----------------|--------------------------------------------|
| `core`         | 扩展 API、依赖解析、扩展仓库与引导接口     |
| `game-adapter` | 两端共用的游戏数据、设置与语言 hook        |
| `desktop`      | JVM 类变换、启动器与内置界面               |
| `android`      | 游戏 dex 准备、存储权限与内置界面          |
| `example`      | 示例扩展                                   |

## 安装

桌面端使用 `rwml-setup.exe`。安装程序定位 Steam 游戏目录，也可以手动选择；确认后把原版 `game-lib.jar` 保存为 `game-lib.jar.original`，再写入 RWML 的归档，并把内置的 Java 25 运行时解压到 `rwml-runtime`。界面提供“恢复原版”按钮。

Android 安装 `android/build/outputs/apk/debug/android-debug.apk`，要求 Android 8.0（API 26）以上。

> [!IMPORTANT]
> 安装程序会修改游戏的 `game-lib.jar`，并在游戏目录安装 Java 25 运行时。只替换 `game-lib.jar` 时，首次运行仍需联网获取运行时。

## 使用

扩展放在游戏目录下的 `extensions/`，Android 为 `/storage/emulated/0/rustedWarfare/extensions`。启动器可以导入归档、查看依赖、启用或停用扩展，移除扩展时保留其数据。

更改扩展后点击“重启”生效。桌面端 `--rwml-no-launcher` 直接启动游戏，`--rwml-manager` 单独打开扩展管理。

## 扩展开发

扩展以 `compileOnly` 引用 core 与映射游戏库，后者附带反编译源码：

```kotlin
dependencies {
    compileOnly("io.github.rwpp-team:rwml-core:1.0.3")
    compileOnly("io.github.rwpp-team:game-lib:1.0.3")
}
```

从 RWML 1.0.3 起，`game-lib` 使用对应的 RWML 版本号；游戏版本仍为 1.15。

在归档中加入 `META-INF/extension.properties`：

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

描述符的其他字段：

| 字段                           | 用途                                       |
|--------------------------------|--------------------------------------------|
| `dependsOn`                    | 硬依赖，可带版本约束                       |
| `minRwmlVersion`               | 扩展所需的最低 RWML 版本，例如 `1.0.3`     |
| `loadAfter` / `loadBefore`     | 推荐加载顺序                               |
| `ui-framework`                 | 实现 `UiFramework.start(UiHost)` 的界面类  |
| `resourcePack`                 | 资源包，可省略 `mainClass`                 |

参考[示例扩展](./example)与 [UI framework](./docs/ui-framework.md)。

如果扩展用到了某个 RWML 版本新增的映射，在描述符中填写该版本。版本过低时，加载器会跳过扩展及依赖它的扩展，并提示升级到所需的 RWML 版本。

## 构建

需要 JDK 25、Android SDK 36，以及 `work/` 中的游戏归档与 Anvil 映射。

```bash
git submodule update --init --recursive
./gradlew packageGameJar :android:assembleDebug :example:jar
```

桌面与 Android 都使用本次构建从 `work/` 生成的映射。在本机开发扩展时，可用 `./gradlew publishGameLibForIde` 额外生成含源码的本地 Maven 构件。

安装程序由 `:setup:packageExe` 生成，产物位于 `setup/build/setup/`。

## 致谢

字节码改写基于 [RELAY-CN](https://github.com/RELAY-CN) 的 [ASM-Framework](https://github.com/RELAY-CN/ASM-Framework)。感谢作者与维护者提供这套面向 JVM 与 Android 的 ASM 工具库，RWML 的类变换与 mixin 注册依赖它。

游戏类映射由 [Anvil](https://github.com/RWPP-Team/Anvil) 维护；dex 转换使用 [dalvik-dx](https://github.com/JakeWharton/dalvik-dx)（Apache 2.0）。

## 许可

[RWPP-Team Shield](./LICENSE.md)。映射后的游戏库使用 [CC0](https://creativecommons.org/publicdomain/zero/1.0/)。
