<h1 align="center">RWML</h1>

<div align="center"><img alt="Tests: Gradle" src="https://img.shields.io/badge/tests-Gradle-blue.svg"> <img alt="License: RWPP-Team Shield" src="https://img.shields.io/badge/license-RWPP--Team%20Shield-blue.svg"> <img alt="Version: 1.0.4" src="https://img.shields.io/badge/version-1.0.4-green.svg"> <img alt="Language: Java" src="https://img.shields.io/badge/language-Java-orange.svg"></div>

<p align="center">🇺🇸 English | 🇨🇳 <a href="./README.md">简体中文</a></p>

## Contents

[Introduction](#introduction) · [Installation](#installation) · [Usage](#usage) · [Extensions](#extensions) · [Building](#building) · [Credits](#credits) · [License](#license)

## Introduction

RWML is a mixin extension loader for Rusted Warfare on desktop and Android. It loads extensions in dependency order, patches game classes as they load, and provides a replaceable interface layer.

| Module         | Purpose                                                     |
|----------------|-------------------------------------------------------------|
| `core`         | Extension API, dependency resolution, repository and boot    |
| `game-adapter` | Game data, settings and language hooks shared by hosts       |
| `desktop`      | JVM class transformation, launcher and built-in UI           |
| `android`      | Game dex preparation, storage access and built-in UI         |
| `example`      | Sample extension                                             |

## Installation

Desktop builds use `rwml-setup.exe`. The installer locates the Steam game directory or accepts a manual path, keeps the original `game-lib.jar` as `game-lib.jar.original`, writes the RWML archive, and unpacks the bundled Java 25 runtime into `rwml-runtime`. The window offers a restore action for the backup.

Android installs `android/build/outputs/apk/debug/android-debug.apk` and requires Android 8.0 (API 26) or newer.

> [!IMPORTANT]
> The installer modifies the game's `game-lib.jar` and installs a Java 25 runtime beside it. Replacing only `game-lib.jar` still requires network access on the first run.

## Usage

Extensions live in `extensions/` beside the game directory, or in `/storage/emulated/0/rustedWarfare/extensions` on Android. The launcher imports archives, shows dependencies, and enables or disables extensions. Removing an extension keeps its data.

Restart from the launcher after changing extensions. On desktop, `--rwml-no-launcher` starts the game directly and `--rwml-manager` opens extension management on its own.

## Extensions

Extensions reference core and the mapped game library with `compileOnly`. The game library ships decompiled sources:

```kotlin
dependencies {
    compileOnly("io.github.rwpp-team:rwml-core:1.0.3")
    compileOnly("io.github.rwpp-team:game-lib:1.0.3")
}
```

Starting with RWML 1.0.3, `game-lib` uses the corresponding RWML release version. The game itself remains at version 1.15.

Add `META-INF/extension.properties` to the archive:

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

Other descriptor keys:

| Key                        | Purpose                                          |
|----------------------------|--------------------------------------------------|
| `dependsOn`                | Hard dependency, optionally with a version range |
| `minRwmlVersion`           | Minimum RWML release required, such as `1.0.3`   |
| `loadAfter` / `loadBefore` | Preferred load order                             |
| `ui-framework`             | Class implementing `UiFramework.start(UiHost)`   |
| `resourcePack`             | Resource pack, may omit `mainClass`              |

See the [sample extension](./example) and the [UI framework guide](./docs/ui-framework.md).

If an extension uses mappings introduced in a particular RWML release, declare that release in its descriptor. An older host skips the extension and its dependents and reports the required RWML upgrade.

## Building

Requires JDK 25, Android SDK 36, and the game archive plus Anvil mappings under `work/`.

```bash
git submodule update --init --recursive
./gradlew packageGameJar :android:assembleDebug :example:jar
```

Both hosts compile and package the mappings generated from `work/` in that build. Extension developers can also run `./gradlew publishGameLibForIde` to publish the mapped library and sources to the local Maven repository.

The installer is built by `:setup:packageExe` and written to `setup/build/setup/`.

## Credits

Bytecode transformation builds on [ASM-Framework](https://github.com/RELAY-CN/ASM-Framework) by [RELAY-CN](https://github.com/RELAY-CN). Thanks to its authors and maintainers for an ASM toolkit that works on both JVM and Android; class transformation and mixin registration rely on it.

Game mappings are maintained in [Anvil](https://github.com/RWPP-Team/Anvil). Dex conversion uses [dalvik-dx](https://github.com/JakeWharton/dalvik-dx) (Apache 2.0).

## License

[RWPP-Team Shield](./LICENSE.md). The mapped game library uses [CC0](https://creativecommons.org/publicdomain/zero/1.0/).
