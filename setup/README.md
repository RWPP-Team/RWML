# RWML Setup

Windows 安装程序：定位 Rusted Warfare 目录，把 `game-lib.jar` 替换为 RWML 构建产物，并保留原版备份。

## 构建

安装程序由 Gradle 任务构建，宿主归档直接取自 `:packageGameJar` 的输出，不复制进源码目录：

```powershell
.\gradlew.bat :setup:packageExe
```

产物：`setup/build/setup/rwml-setup.exe`。

MSI 需要 WiX 5：

```powershell
dotnet tool install --global wix --version 5.0.2
.\gradlew.bat :setup:packageMsi -Pwix "$env:USERPROFILE\.dotnet\tools\wix.exe"
```

产物：`setup/build/setup/rwml-setup.msi`。`packageSetup` 同时构建两者。

## 任务

| 任务 | 作用 |
| --- | --- |
| `:setup:packageExe` | 读取 `build/dist/game-lib.jar`，发布安装程序 |
| `:setup:packageMsi` | 用 WiX 打包 MSI |
| `:setup:buildChecks` | 编译安装逻辑自检 |
| `:setup:checkSetup` | 运行自检：定位、备份、替换、幂等、恢复 |
| `:setup:packageSetup` | 前两项的合集 |

宿主归档通过 MSBuild 属性 `GameLibPath` 传入。安装程序只在编译时嵌入这一份文件，源码目录中没有 jar。

## 行为

1. 从 Steam 注册表、`libraryfolders.vdf` 与卸载项中查找游戏目录，找不到时要求手动选择。
2. 校验目录中存在 `game-lib.jar` 与游戏可执行文件。
3. 首次替换前把原文件复制为 `game-lib.jar.original`。
4. 写入前确认新归档包含 `io/github/rwml/desktop/DesktopRuntime.class`，写入后复核 SHA-256。
5. 在游戏目录写入 `rwml-install.json`，记录版本、时间与摘要。

游戏运行期间不会替换文件。目标目录不可写时提示以管理员身份运行。

## 命令行

```text
rwml-setup --detect
rwml-setup --install [--dir <游戏目录>]
rwml-setup --restore [--dir <游戏目录>]
```

无参数时打开界面。`--install` 省略目录时自动定位。退出码：0 成功，1 失败，2 未找到目录。

## MSI

MSI 把安装程序放在 `%LOCALAPPDATA%\RWML Setup`，并在安装序列中调用它：

```powershell
msiexec /i rwml-setup.msi GAMEDIR="D:\Steam\steamapps\common\Rusted Warfare"
```

省略 `GAMEDIR` 时不做替换。MSI 没有选择目录的界面，交互安装请使用 EXE。

## 发布

推送 `v*` tag 会触发 `.github/workflows/release.yml`：校验 tag 与 `gradle.properties` 版本一致，构建
`game-lib.jar`、`rwml-setup.exe` 与 `rwml-setup.msi`，生成 `SHA256SUMS.txt` 并上传到同名 Release。

## 不提供 MSIX

MSIX 安装到包自身的虚拟化目录，写入其他应用的安装目录需要额外授权，也不适合 Steam 游戏目录。替换
`game-lib.jar` 属于修改现有安装，因此只提供 EXE 与 MSI。
