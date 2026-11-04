# RWML Setup

Windows 安装程序：定位 Rusted Warfare 目录，把 `game-lib.jar` 替换为 RWML 构建产物，保留原版备份，并在游戏目录安装内置的 Java 25 运行时。

## 构建

安装程序由 Gradle 任务构建，宿主归档取自 `:packageGameJar` 的输出，JRE 由 `jre.properties` 指定：

```powershell
.\gradlew.bat :setup:packageExe
```

`:setup:prepareJre` 先下载 JRE 并校验 SHA-256，默认使用国内镜像，失败时改用备用镜像或 Adoptium 官方地址。产物为包含 `game-lib.jar` 与 JRE 的 `setup/build/setup/rwml-setup.exe`，首次启动无需下载 Java。

完全离线构建时传入本地归档：

```powershell
.\gradlew.bat :setup:packageExe -PjreZip="D:\cache\OpenJDK25U-jre_x64_windows_hotspot_25.0.5_8.zip"
```

本地归档仍需与 `jre.properties` 的摘要一致。

## 任务

| 任务 | 作用 |
| --- | --- |
| `:setup:prepareJre` | 下载并校验内置 JRE |
| `:setup:packageExe` | 读取 `build/dist/game-lib.jar`，发布含 JRE 的安装程序 |
| `:setup:buildChecks` | 编译安装逻辑自检 |
| `:setup:checkSetup` | 运行自检：定位、备份、替换、幂等、恢复 |
| `:setup:packageSetup` | `packageExe` 的合集入口 |

宿主归档与 JRE 通过 MSBuild 属性 `GameLibPath`、`JrePath` 传入。安装程序只在编译时嵌入这两份文件，源码目录中没有 jar。

## 行为

1. 从 Steam 注册表、`libraryfolders.vdf` 与卸载项中查找游戏目录，找不到时要求手动选择。
2. 校验目录中存在 `game-lib.jar` 与游戏可执行文件。
3. 首次替换前把原文件复制为 `game-lib.jar.original`。
4. 写入前确认新归档包含 `io/github/rwml/desktop/DesktopRuntime.class`，写入后复核 SHA-256。
5. 把内置 JRE 解压到 `rwml-runtime/jdk-25`，启动器会优先使用它。
6. 在游戏目录写入 `rwml-install.json`，记录版本、时间、摘要与运行时来源。

游戏运行期间不会替换文件。目标目录不可写时提示以管理员身份运行。

## 命令行

```text
rwml-setup --detect
rwml-setup --install [--dir <游戏目录>]
rwml-setup --restore [--dir <游戏目录>]
```

无参数时打开界面。`--install` 省略目录时自动定位。退出码：0 成功，1 失败，2 未找到目录。

`--restore` 恢复原版 `game-lib.jar`，并删除安装程序创建的 `rwml-runtime` 目录。

## 发布

推送 `v*` tag 会触发 `.github/workflows/release.yml`：校验 tag 与 `gradle.properties` 版本一致，构建
`game-lib.jar` 与 `rwml-setup.exe`，生成 `SHA256SUMS.txt` 并上传到同名 Release。

## 不提供 MSIX

MSIX 安装到包自身的虚拟化目录，写入其他应用的安装目录需要额外授权，也不适合 Steam 游戏目录。替换
`game-lib.jar` 属于修改现有安装，因此只提供 EXE。
