/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

// ------------------------------------------------------------------------------------------------
// Windows 安装程序。
//
// 安装程序本身是 .NET Framework 项目，这里只把它接入 Gradle 生命周期：
//   :setup:packageExe  读取 :packageGameJar 的输出，生成 setup/build/setup/rwml-setup.exe
//   :setup:packageMsi  用 WiX 生成同一目录下的 MSI
//   :setup:check       在临时目录验证定位、替换、幂等与恢复
//
// 宿主归档通过 MSBuild 属性 GameLibPath 传入，不复制到源码目录，也不写死任何路径。
// ------------------------------------------------------------------------------------------------

plugins {
    base
}

description = "Windows installer for the RWML game-lib replacement."
version = rootProject.version

val setupProject = layout.projectDirectory.dir("src/RwmlSetup")
val checksProject = layout.projectDirectory.dir("tests/RwmlSetup.Checks")
val installerDir = layout.buildDirectory.dir("setup")
val checksOutput = layout.buildDirectory.dir("checks")
val gameLib = rootProject.layout.buildDirectory.file("dist/game-lib.jar")
val packageGameJar = rootProject.tasks.named("packageGameJar")

val versionText = version.toString()
val dotnetBudget = 10L * 60L * 1000L

fun requireWindows(task: TaskProvider<out Task>) {
    task.configure {
        doFirst {
            val os = System.getProperty("os.name").lowercase()
            check(os.contains("windows")) {
                "The installer can only be built on Windows (current OS: ${System.getProperty("os.name")})."
            }
        }
    }
}

fun requireDotnet() {
    try {
        val process = ProcessBuilder("dotnet", "--version")
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        check(process.waitFor() == 0) { "dotnet --version failed. Install the .NET SDK 8 or newer." }
    } catch (failure: Exception) {
        throw GradleException("The .NET SDK is required to build the installer: ${failure.message}", failure)
    }
}

/** 安装程序源码（不含 bin/obj）。 */
val sourceFiles = fileTree(setupProject) {
    include("**/*.cs", "**/*.csproj", "**/*.manifest")
}

val exeFile = installerDir.map { it.file("rwml-setup.exe") }
val msiFile = installerDir.map { it.file("rwml-setup.msi") }

val packageExe = tasks.register<Exec>("packageExe") {
    group = "distribution"
    description = "Publishes rwml-setup.exe with the current game-lib.jar embedded."
    dependsOn(packageGameJar)

    inputs.files(sourceFiles)
    inputs.file(gameLib)
    outputs.dir(installerDir)

    workingDir = projectDir
    timeout.set(java.time.Duration.ofMillis(dotnetBudget))

    doFirst { requireDotnet() }

    commandLine(
        "dotnet", "publish", setupProject.asFile.absolutePath,
        "-c", "Release",
        "-o", installerDir.get().asFile.absolutePath,
        "-p:Version=$versionText",
        "-p:GameLibPath=${gameLib.get().asFile.absolutePath}",
        "-p:DebugType=None",
        "-p:DebugSymbols=false",
        "--nologo"
    )
}

val packageMsi = tasks.register<Exec>("packageMsi") {
    group = "distribution"
    description = "Builds rwml-setup.msi with WiX from the published installer."
    dependsOn(packageExe)

    inputs.file(layout.projectDirectory.file("packaging/msi/Setup.wxs"))
    inputs.dir(installerDir)
    outputs.file(msiFile)

    workingDir = projectDir

    commandLine(
        providers.gradleProperty("wix").getOrElse("wix"), "build",
        layout.projectDirectory.file("packaging/msi/Setup.wxs").asFile.absolutePath,
        "-d", "Version=$versionText",
        "-d", "SourceExe=${exeFile.get().asFile.absolutePath}",
        "-o", msiFile.get().asFile.absolutePath
    )
}

val buildChecks = tasks.register<Exec>("buildChecks") {
    group = "verification"
    description = "Compiles the installer checks against the current game-lib.jar."
    dependsOn(packageGameJar)

    inputs.files(fileTree(checksProject) { include("**/*.cs", "**/*.csproj") })
    inputs.files(fileTree(setupProject) { include("GameInstaller.cs", "GameLocator.cs") })
    inputs.file(gameLib)
    outputs.dir(checksOutput)

    workingDir = projectDir
    timeout.set(java.time.Duration.ofMillis(dotnetBudget))

    doFirst { requireDotnet() }

    commandLine(
        "dotnet", "build", checksProject.asFile.absolutePath,
        "-c", "Release",
        "-o", checksOutput.get().asFile.absolutePath,
        "-p:GameLibPath=${gameLib.get().asFile.absolutePath}",
        "--nologo"
    )
}

val checkSetup = tasks.register<Exec>("checkSetup") {
    group = "verification"
    description = "Runs the installer checks: detection, backup, replacement, idempotence and restore."
    dependsOn(buildChecks)

    workingDir = projectDir

    commandLine(checksOutput.map { it.file("rwml-setup-checks.exe").asFile.absolutePath }.get())
}

requireWindows(packageExe)
requireWindows(packageMsi)
requireWindows(buildChecks)
requireWindows(checkSetup)

tasks.register("packageSetup") {
    group = "distribution"
    description = "Builds the installer and the MSI."
    dependsOn(packageExe, packageMsi)
}

tasks.named("check") {
    dependsOn(checkSetup)
}
