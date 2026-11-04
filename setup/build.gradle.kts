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
//   :setup:prepareJre  下载并校验随安装程序发布的 JRE
//   :setup:packageExe  读取 :packageGameJar 的输出，生成内含 JRE 的 setup/build/setup/rwml-setup.exe
//   :setup:check       在临时目录验证定位、替换、幂等与恢复
//
// 宿主归档与 JRE 通过 MSBuild 属性 GameLibPath、JrePath 传入，不复制到源码目录，也不写死任何路径。
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

// ------------------------------------------------------------------------------------------------
// 内置 JRE。版本与摘要记录在 jre.properties，发布时无需联网查找最新版本；镜像不可用时尝试备用地址，
// 也可用 -PjreZip=<本地归档> 完全离线构建。
// ------------------------------------------------------------------------------------------------
val jrePropertiesFile = layout.projectDirectory.file("jre.properties")
val jreProperties = java.util.Properties().apply {
    jrePropertiesFile.asFile.inputStream().use { load(it) }
}
val jreArchiveName = jreProperties.getProperty("archive")
val jreSha256 = jreProperties.getProperty("sha256").lowercase()
val jreUrls = listOfNotNull(
    jreProperties.getProperty("url")?.takeIf { it.isNotBlank() },
    jreProperties.getProperty("fallbackUrl")?.takeIf { it.isNotBlank() },
    jreProperties.getProperty("officialUrl")?.takeIf { it.isNotBlank() }
)
val jreArchive = layout.buildDirectory.dir("jre").map { it.file(jreArchiveName) }

fun sha256Of(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

fun downloadFile(url: String, target: File) {
    val connection = java.net.URI(url).toURL().openConnection()
    connection.connectTimeout = 20_000
    connection.readTimeout = 120_000
    connection.setRequestProperty("User-Agent", "rwml-build")
    try {
        if (connection is java.net.HttpURLConnection) {
            val status = connection.responseCode
            check(status in 200..299) { "HTTP $status from $url" }
        }
        connection.getInputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    } catch (failure: Exception) {
        target.delete()
        throw failure
    } finally {
        if (connection is java.net.HttpURLConnection) {
            connection.disconnect()
        }
    }
}

val prepareJre = tasks.register("prepareJre") {
    group = "distribution"
    description = "Downloads the JRE bundled into rwml-setup.exe and verifies its digest."
    inputs.file(jrePropertiesFile)
    outputs.file(jreArchive)
    // 每次都核对摘要，避免缓存了一个损坏的归档后继续打包。
    outputs.upToDateWhen { false }
    val supplied = providers.gradleProperty("jreZip")

    doLast {
        val target = jreArchive.get().asFile
        if (target.isFile && sha256Of(target) == jreSha256) {
            return@doLast
        }
        target.parentFile.mkdirs()
        val local = supplied.orNull
        if (local != null) {
            val source = file(local)
            check(source.isFile) { "The supplied JRE archive does not exist: $source" }
            source.copyTo(target, overwrite = true)
        } else {
            var failure: Exception? = null
            for (url in jreUrls) {
                try {
                    downloadFile(url, target)
                    failure = null
                    break
                } catch (error: Exception) {
                    failure = error
                    logger.warn("Cannot download the bundled JRE from $url: ${error.message}")
                }
            }
            if (!target.isFile) {
                throw GradleException("Cannot download the bundled JRE: ${failure?.message}", failure)
            }
        }
        val actual = sha256Of(target)
        check(actual == jreSha256) {
            "The bundled JRE has SHA-256 $actual, expected $jreSha256"
        }
    }
}

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

val packageExe = tasks.register<Exec>("packageExe") {
    group = "distribution"
    description = "Publishes a self-contained rwml-setup.exe with game-lib.jar and the JRE embedded."
    dependsOn(packageGameJar, prepareJre)

    inputs.files(sourceFiles)
    inputs.file(gameLib)
    inputs.file(jreArchive)
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
        "-p:JrePath=${jreArchive.get().asFile.absolutePath}",
        "-p:DebugType=None",
        "-p:DebugSymbols=false",
        "--nologo"
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
requireWindows(buildChecks)
requireWindows(checkSetup)

tasks.register("packageSetup") {
    group = "distribution"
    description = "Builds the installer."
    dependsOn(packageExe)
}

tasks.named("check") {
    dependsOn(checkSetup)
}
