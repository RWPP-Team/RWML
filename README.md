<h1 align="center">RWML</h1>

<div align="center"><img alt="Tests: Gradle" src="https://img.shields.io/badge/tests-Gradle-blue.svg"> <img alt="License: RWPP-Team Shield" src="https://img.shields.io/badge/license-RWPP--Team%20Shield-blue.svg"> <img alt="Version: 1.0.1" src="https://img.shields.io/badge/version-1.0.1-green.svg"> <img alt="Language: Java" src="https://img.shields.io/badge/language-Java-orange.svg"></div>

<p align="center">🇺🇸 <a href="./README.md">English</a> | 🇨🇳 <a href="./README.zh.md">简体中文</a></p>

## Contents

[Introduction](#introduction) · [Installation](#installation) · [Usage](#usage) · [API](#api-reference) · [Building](#building) · [Contributing](#contributing) · [License](#license)

## Introduction

RWML is a mixin extension loader for Rusted Warfare on desktop and Android. It loads extensions in dependency order, patches game classes, and supports custom interfaces.

| Module | Purpose |
|---|---|
| `core` | Shared extension API, dependency resolution, and boot interfaces |
| `game-adapter` | Shared game data, settings and language hooks, kept out of core and UI renderers |
| `desktop` | JVM loading, class transformation, and desktop builtin UI |
| `android` | Game dex preparation, caching, permissions, and Android builtin UI |
| `example` | Sample extension |

## Installation

Build the artifacts with the [commands below](#building).

| Platform | Requirement | Install |
|---|---|---|
| Desktop | Java 8+; RWML downloads Java 25 when needed | Replace the game's `game-lib.jar` with `build/dist/game-lib.jar` |
| Android | Android 8.0+ (API 26) | Install `android/build/outputs/apk/debug/android-debug.apk` |

> [!IMPORTANT]
> Back up the original desktop `game-lib.jar` before replacing it. The first runtime download requires network access.

## Usage

Put extension jars in `extensions/` beside the desktop game library, or in `/storage/emulated/0/rustedWarfare/extensions` on Android. Android requests storage access before loading.

Extension changes and enable/disable settings take effect when the launcher next loads them. Android checks SHA-256 at that point and rebuilds changed caches.

Both launchers offer system/English/Simplified Chinese language selection and extension management.
Import a jar with the file picker, search installed extensions, inspect dependencies, enable/disable,
or remove archives while retaining their data. Replacing an existing extension asks for confirmation;
restart RWML to apply changes. Android also accepts extension files through file managers' Open with
action. Diagnostic output keeps its original language.

Desktop options: `--rwml-no-launcher` starts directly, while `--rwml-manager` opens the management
launcher even if a UI extension is unavailable. The Java 8 bootstrap shows runtime provisioning
progress. Android management is available before game dex preparation.

## API Reference

Compile extensions against core and the mapped game library (includes decompiled sources). The `v2` in `game-lib:v2-1.15` identifies the mapping revision; the game version remains 1.15:

```kotlin
repositories { mavenCentral() }
dependencies {
    compileOnly("io.github.rwpp-team:rwml-core:1.0.1")
    compileOnly("io.github.rwpp-team:game-lib:v2-1.15")
}
```

All extension metadata lives in UTF-8 `META-INF/extension.properties`; `@ExtensionInfo` is no longer supported. Different versions of the same id can coexist. Choose one in extension management, then restart to apply it.

Add `META-INF/extension.properties` to your jar:

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

| API / descriptor key | Purpose |
|---|---|
| `Extension.onLoad(ExtensionContext)` | Entry point, called once per loaded instance |
| `ExtensionContext` | Private data directory, logging, class loader, and mixin bridge |
| `dependsOn` / `loadAfter` / `loadBefore` | Required dependencies / preferred load order |
| `ui-framework` | Class implementing `UiFramework.start(UiHost)` |
| `UiHost.gameBoot()` | Shared game initialization and progress reporting |
| `UiHost.services()` / `ExtensionContext.ui()` | Translations, archive management, events and typed services |
| `GameUi` / `GameSettings` | Portable menu data, translations, map names and typed settings |

See [UI framework services](./docs/ui-framework.md) for lifecycle, hook and extension examples.

Only one UI framework loads; external frameworks take priority over builtin. On Android, the selected framework starts before game dex preparation. Draw the loading screen, then start `GameBoot` on a worker; use game classes after boot succeeds.

Use `compileOnly` for core, game APIs, and ASM annotations; do not bundle host libraries. Android extensions also need executable `classes.dex` alongside `.class` metadata and mixins. See the [example](./example), [shared API](./core/src/main/java/io/github/rwml/api), and [ASM-Framework](https://github.com/RELAY-CN/ASM-Framework).

## Building

Requires a JDK 25 toolchain, Android SDK 35, the original `work/game-lib.jar`, and mappings in `work/mappings`. On Windows, replace `./gradlew` with `.\gradlew.bat`.

```bash
git submodule update --init --recursive
./gradlew publishGameLibForIde
./gradlew packageGameJar :android:assembleDebug :example:jar
```

`publishGameLibForIde` uses Vineflower to generate `build/libs/game-lib-named-sources.jar` and publishes binary/source artifacts to `build/local-maven`. Reload Gradle in IDEA or Android Studio with dependency source downloads enabled, or attach the sources jar manually.

## Contributing

Issues and pull requests are welcome. Discuss larger changes in an issue first. [QQ group 1004399208](https://qm.qq.com/q/auLINpWtr4).

```bash
./gradlew :core:check :desktop:test :android:testDebugUnitTest verifyGameSources
```

## License

[RWPP-Team Shield](./LICENSE.md); `game-lib`: [CC0](https://creativecommons.org/publicdomain/zero/1.0/). Third-party components: [ASM-Framework](https://github.com/RELAY-CN/ASM-Framework) and [dx](https://github.com/JakeWharton/dalvik-dx) — [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0).
