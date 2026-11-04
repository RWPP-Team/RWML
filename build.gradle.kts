/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.MappingWriter
import net.fabricmc.mappingio.format.MappingFormat
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils

import java.lang.classfile.ClassFile
import java.lang.classfile.ClassTransform
import java.lang.classfile.attribute.InnerClassInfo
import java.lang.classfile.attribute.InnerClassesAttribute
import java.lang.classfile.attribute.SourceFileAttribute
import java.lang.constant.ClassDesc
import org.gradle.api.tasks.bundling.Jar
import org.gradle.process.ExecOperations

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile

import javax.inject.Inject

plugins {
    idea
    `maven-publish`
    signing
}

group = "io.github.rwpp-team"
version = providers.gradleProperty("version").get()
description = "RWML - a mixin loader framework for the Rusted Warfare."

buildscript {
    // 映射流程库只在构建时使用，因此放在此脚本自己的类路径中，而不属于任何模块；运行时不需要 TinyRemapper。
    repositories {
        mavenCentral()
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
    }
    dependencies {
        classpath(libs.fabricmc.tiny.remapper)
        classpath(libs.fabricmc.mapping.io)
    }
}

// Both IDEA and Kotlin/Gradle daemons may keep jars open on Windows. Never replace an
// archive that an earlier import is using, even before it reaches the Maven repository.
val mappingOutputRevision = run {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val inputs = listOf(file("work/game-lib.jar"), file("build.gradle.kts"), file("gradle/libs.versions.toml")) +
        fileTree("work/mappings").files.filter { it.isFile }
            .sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }
    inputs.forEach { input ->
        digest.update(input.relativeTo(rootDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        if (input.isFile) input.inputStream().buffered().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.update(0.toByte())
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
val mappedGameArchive = layout.buildDirectory.file("mapped-game/$mappingOutputRevision/game-lib-named.jar")
val mappedGameSources = layout.buildDirectory.file("mapped-game/$mappingOutputRevision/game-lib-named-sources.jar")
extra["rwmlMappedGameJar"] = mappedGameArchive

val remapFromEnigma = tasks.register<RemapFromEnigmaTask>("remapFromEnigma") {
    group = "jar mapping"

    enigmaDir.set(layout.projectDirectory.dir("work/mappings"))
    inputJar.set(layout.projectDirectory.file("work/game-lib.jar"))
    fromNamespace.set("source")
    toNamespace.set("target")
    outputJar.set(mappedGameArchive)
    tinyCache.set(layout.buildDirectory.file("temp/mappings-converted.tiny"))
    derivedReport.set(layout.buildDirectory.file("reports/mappings/derived-nested-classes.txt"))
}

// ------------------------------------------------------------------------------------------------
// genSources：为界面查看而生成的游戏源码。
//
// 各模块只需针对映射后的游戏进行编译，不会运行游戏，因此构建时只需要游戏的名称信息。要追踪引擎调用、
// 查看字段内容，界面必须能访问源码；本任务通过反编译一次并在映射后 jar 旁生成 sources jar，避免每次
// 查阅都手动反编译。两个 jar 都会发布到构建专用的 Maven 仓库。模块随后通过坐标依赖游戏，并像其他依赖
// 一样通过 `-sources` 分类器获取源码。
//
// 反编译器是构建工具，因此通过此脚本的配置引入，而不属于某个模块；运行时不会执行反编译。
// ------------------------------------------------------------------------------------------------

/** 运行反编译任务时从 Maven Central 解析的反编译器。 */
val vineflower = configurations.create("vineflower")
dependencies.add(vineflower.name, libs.vineflower)

/** 游戏的依赖坐标：供各模块请求，也是此脚本发布时使用的坐标。 */
val gameLibCoordinate = libs.game.lib.map { it.toString() }
val gameLibVersion = libs.versions.game.lib.get()

/** 游戏 jar 及其源码的发布位置，也是各模块读取它们的位置。 */
val localGameRepository = layout.buildDirectory.dir("local-maven")

val genSources = tasks.register<DecompileGameSourcesTask>("genSources") {
    group = "jar mapping"
    description = "Decompiles the remapped game into a sources jar, so the interface can open it."

    gameJar.set(remapFromEnigma.flatMap { it.outputJar })
    outputJar.set(mappedGameSources)
    toolClasspath.from(vineflower)
    androidPlatformJar()?.let { androidJar.set(it) }
}

val publishGameLibForIde = tasks.register<PublishGameLibForIdeTask>("publishGameLibForIde") {
    group = "jar mapping"
    description = "Publishes the remapped game and its sources into the build's own repository."

    gameJar.set(remapFromEnigma.flatMap { it.outputJar })
    sourcesJar.set(genSources.flatMap { it.outputJar })
    coordinate.set(gameLibCoordinate)
    repository.set(localGameRepository)
}

tasks.register("gameSourcesJar") {
    group = "jar mapping"
    description = "Builds the standalone decompiled game sources jar."
    dependsOn(genSources)
}

tasks.register("verifyGameSources") {
    group = "verification"
    description = "Checks source paths and the published sources artifact."
    dependsOn(publishGameLibForIde)
    doLast {
        ZipFile(genSources.get().outputJar.get().asFile).use { sources ->
            val entries = sources.entries().asSequence().toList()
            check(entries.isNotEmpty() && entries.all { it.name.endsWith(".java") }) {
                "The sources archive must contain Java sources, not binaries or nested archives."
            }
            for (name in listOf(
                "com/corrodinggames/rts/gameFramework/GameEngine",
                "com/corrodinggames/rts/game/RWGameEngine",
                "com/corrodinggames/rts/java/MainLauncher"
            )) {
                check(sources.getEntry("$name.java") != null) { "Missing mapped source: $name" }
            }
            logger.lifecycle("Verified ${entries.size} decompiled Java source entries.")
        }
    }
}

// Maven 库依赖通过标准分类器提供源码。
subprojects {
    apply(plugin = "idea")
    extensions.configure<org.gradle.plugins.ide.idea.model.IdeaModel> {
        module.isDownloadSources = true
    }
}

/**
 * 平台自身的 API，反编译器用它解析游戏中面向 Android 的名称。SDK 的查找顺序与 Android 模块相同：先看本地
 * `sdk.dir`，再看两个环境变量，最后选择 SDK 中版本最新的平台。没有 SDK 时仍可反编译，因为游戏自身部分
 * 不引用平台类；缺少平台 API 不会导致失败。
 */
fun androidPlatformJar(): File? {
    val local = layout.projectDirectory.file("local.properties").asFile
    val configured = if (local.isFile) {
        java.util.Properties().apply { local.inputStream().use { load(it) } }.getProperty("sdk.dir")
    } else {
        null
    }
    val sdk = configured ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val platform = sdk?.let { File(it, "platforms") }?.listFiles()?.sortedBy { it.name }?.lastOrNull()
    val jar = platform?.let { File(it, "android.jar") }
    if (jar == null || !jar.isFile) {
        logger.info("No Android platform is available; the sources are decompiled without it.")
        return null
    }
    return jar
}

/**
 * 将 jar 反编译为 sources jar。
 *
 * 反编译器先将 Java 文件写入目录，再按 sources jar 的结构打包，使归档中每个类都位于对应包路径下的文件中。
 */
@CacheableTask
abstract class DecompileGameSourcesTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val gameJar: RegularFileProperty

    /** SDK 提供的平台类；游戏自身的类位于 [gameJar] 中。 */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val androidJar: RegularFileProperty

    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun decompile() {
        val output = outputJar.get().asFile
        if (output.isFile) {
            logger.lifecycle("Reusing immutable game sources ${output.absolutePath}")
            return
        }
        val sources = File(temporaryDir, "sources")
        val temporaryRoot = temporaryDir.toPath().toAbsolutePath().normalize()
        val sourcesPath = sources.toPath().toAbsolutePath().normalize()
        check(sourcesPath.startsWith(temporaryRoot) && sourcesPath != temporaryRoot) {
            "Source staging must stay inside $temporaryRoot"
        }
        sources.deleteRecursively()
        sources.mkdirs()

        val game = gameJar.get().asFile
        val classpath = buildList {
            add(game)
            if (androidJar.isPresent) {
                add(androidJar.get().asFile)
            }
        }

        execOperations.javaexec {
            setClasspath(toolClasspath)
            mainClass.set(DECOMPILER)
            args(
                "-dgs=1", // 保留泛型签名
                "-din=1", // 反编译内部类，不将其保留为字段
                "-rsy=1", // 隐藏无需查看的合成成员
                "-hdc=0", // 保留隐式的父类构造函数
                "-jrt=1", // 从当前运行时读取 JDK 自带类
                "-log=WARN"
            )
            classpath.forEach { args("-e=${it.absolutePath}") }
            args(game.absolutePath, sources.absolutePath)
        }

        val written = sources.walkTopDown().filter { it.isFile && it.extension == "java" }
            .sortedBy { it.relativeTo(sources).invariantSeparatorsPath }.toList()
        check(written.isNotEmpty()) { "The decompiler wrote no source for ${game.name}." }

        output.parentFile.mkdirs()
        val temporaryOutput = File(temporaryDir, "game-sources.jar")
        BufferedOutputStream(FileOutputStream(temporaryOutput)).use { stream ->
            JarOutputStream(stream).use { jar ->
                for (file in written) {
                    val entry = JarEntry(file.relativeTo(sources).invariantSeparatorsPath)
                    entry.time = 0L
                    jar.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(jar) }
                    jar.closeEntry()
                }
            }
        }
        try {
            java.nio.file.Files.move(temporaryOutput.toPath(), output.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temporaryOutput.toPath(), output.toPath())
        }
        logger.lifecycle(
            "Decompiled ${written.size} source files into ${output.name} (${output.length() / 1024} KiB)"
        )
    }

    private companion object {
        const val DECOMPILER = "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler"
    }
}

/**
 * 将游戏及其源码发布到此构建输出目录内的 Maven 仓库。
 *
 * 每一组映射 jar 和源码 jar 都发布到按内容摘要隔离的 Maven 仓库中。仓库根目录的指针只在新版本完整
 * 写入后更新，避免另一个 Gradle 进程打开 sources jar 时，本任务或 Gradle 的输出清理先删除它。
 */
abstract class PublishGameLibForIdeTask : DefaultTask() {

    @get:InputFile
    abstract val gameJar: RegularFileProperty

    @get:InputFile
    abstract val sourcesJar: RegularFileProperty

    @get:Input
    abstract val coordinate: Property<String>

    @get:org.gradle.api.tasks.Internal
    abstract val repository: DirectoryProperty

    @TaskAction
    fun publish() {
        val parts = coordinate.get().split(":")
        check(parts.size == 3) { "The coordinate ${coordinate.get()} is not group:artifact:version." }
        val (group, artifact, version) = parts
        val jar = gameJar.get().asFile
        val sources = sourcesJar.get().asFile
        val baseRepository = repository.get().asFile.toPath().toAbsolutePath().normalize()
        val revision = "revisions/${contentRevision(jar, sources, coordinate.get())}"
        val revisionRepository = baseRepository.resolve(revision).normalize()
        check(revisionRepository.startsWith(baseRepository)) { "Invalid game-lib revision path: $revision" }

        val artifactName = "$artifact-$version"
        val publicationRelativePath = Path.of(group.replace('.', '/'), artifact, version)
        val publicationDirectory = revisionRepository.resolve(publicationRelativePath)
        val binaryOutput = publicationDirectory.resolve("$artifactName.jar")
        val sourcesOutput = publicationDirectory.resolve("$artifactName-sources.jar")
        val pomOutput = publicationDirectory.resolve("$artifactName.pom")
        val expectedPom = pom(group, artifact, version)

        if (java.nio.file.Files.exists(revisionRepository)) {
            checkRevision(jar, sources, expectedPom, binaryOutput, sourcesOutput, pomOutput)
            logger.lifecycle("Reusing existing game-lib revision $revision")
        } else {
            publishRevision(
                jar,
                sources,
                expectedPom,
                artifactName,
                revisionRepository,
                publicationRelativePath
            )
            logger.lifecycle("Published a new game-lib revision $revision")
        }

        writeCurrentRepository(baseRepository, revision)
        logger.lifecycle("Published $group:$artifact:$version to ${revisionRepository.toFile()}")
    }

    private fun contentRevision(jar: File, sources: File, coordinate: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(coordinate.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        for ((label, file) in listOf("game-lib" to jar, "sources" to sources)) {
            digest.update(label.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun publishRevision(
        jar: File,
        sources: File,
        pom: String,
        artifactName: String,
        revisionRepository: java.nio.file.Path,
        publicationRelativePath: java.nio.file.Path,
    ) {
        val revisionsDirectory = revisionRepository.parent
        java.nio.file.Files.createDirectories(revisionsDirectory)
        val temporaryRepository = java.nio.file.Files.createTempDirectory(revisionsDirectory, "publishing-")
        try {
            val publicationDirectory = temporaryRepository.resolve(publicationRelativePath)
            java.nio.file.Files.createDirectories(publicationDirectory)
            java.nio.file.Files.copy(jar.toPath(), publicationDirectory.resolve("$artifactName.jar"))
            java.nio.file.Files.copy(sources.toPath(), publicationDirectory.resolve("$artifactName-sources.jar"))
            java.nio.file.Files.writeString(publicationDirectory.resolve("$artifactName.pom"), pom, Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    temporaryRepository,
                    revisionRepository,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                try {
                    java.nio.file.Files.move(temporaryRepository, revisionRepository)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    checkRevision(
                        jar,
                        sources,
                        pom,
                        revisionRepository.resolve(publicationRelativePath).resolve("$artifactName.jar"),
                        revisionRepository.resolve(publicationRelativePath).resolve("$artifactName-sources.jar"),
                        revisionRepository.resolve(publicationRelativePath).resolve("$artifactName.pom")
                    )
                }
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                checkRevision(
                    jar,
                    sources,
                    pom,
                    revisionRepository.resolve(publicationRelativePath).resolve("$artifactName.jar"),
                    revisionRepository.resolve(publicationRelativePath).resolve("$artifactName-sources.jar"),
                    revisionRepository.resolve(publicationRelativePath).resolve("$artifactName.pom")
                )
            }
        } finally {
            if (java.nio.file.Files.exists(temporaryRepository)) {
                deleteTree(temporaryRepository)
            }
        }
    }

    private fun checkRevision(
        jar: File,
        sources: File,
        pom: String,
        jarOutput: java.nio.file.Path,
        sourcesOutput: java.nio.file.Path,
        pomOutput: java.nio.file.Path,
    ) {
        check(java.nio.file.Files.isRegularFile(jarOutput)) { "Incomplete game-lib revision: $jarOutput" }
        check(java.nio.file.Files.isRegularFile(sourcesOutput)) { "Incomplete game-lib revision: $sourcesOutput" }
        check(java.nio.file.Files.isRegularFile(pomOutput)) { "Incomplete game-lib revision: $pomOutput" }
        check(java.nio.file.Files.mismatch(jar.toPath(), jarOutput) == -1L) {
            "The game-lib jar differs from the existing revision artifact $jarOutput."
        }
        check(java.nio.file.Files.mismatch(sources.toPath(), sourcesOutput) == -1L) {
            "The sources jar differs from the existing revision artifact $sourcesOutput."
        }
        check(java.nio.file.Files.readString(pomOutput, Charsets.UTF_8) == pom) {
            "The POM differs from the existing revision artifact $pomOutput."
        }
    }

    private fun writeCurrentRepository(baseRepository: java.nio.file.Path, revision: String) {
        java.nio.file.Files.createDirectories(baseRepository)
        val pointer = baseRepository.resolve("current-repository.txt")
        val contents = "$revision\n"
        if (java.nio.file.Files.isRegularFile(pointer) &&
            java.nio.file.Files.readString(pointer, Charsets.UTF_8) == contents
        ) return

        val temporary = java.nio.file.Files.createTempFile(baseRepository, "current-repository.", ".tmp")
        try {
            java.nio.file.Files.writeString(temporary, contents, Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    temporary,
                    pointer,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temporary, pointer, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temporary)
        }
    }

    private fun deleteTree(root: java.nio.file.Path) {
        java.nio.file.Files.walk(root).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) }
        }
    }

    private fun pom(group: String, artifact: String, version: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>$group</groupId>
          <artifactId>$artifact</artifactId>
          <version>$version</version>
          <packaging>jar</packaging>
        </project>
    """.trimIndent()
}

// ------------------------------------------------------------------------------------------------
// 各模块
//
// :core 是跨平台扩展系统；:desktop 是 JVM 宿主，会将其打包成可替换游戏原 game-lib.jar 的文件；:android
// 是在设备上完成同样工作的应用。本项目自身不编译代码，负责映射流程（将混淆游戏转换成各模块编译所用的名称）
// 以及桌面构件的打包。
// ------------------------------------------------------------------------------------------------

// ------------------------------------------------------------------------------------------------
// Maven Central 发布包 ZIP 任务
// ------------------------------------------------------------------------------------------------
tasks.register<Zip>("bundleForCentral") {
    group = "publishing"
    description = "Builds the Maven Central bundle ZIP from :core's staging directory."
    dependsOn(":core:check", ":core:publishAllPublicationsToLocalStagingRepository")
    val publicationPath = "${project(":core").group.toString().replace('.', '/')}/rwml-core/${version}"
    val staging = project(":core").layout.buildDirectory.dir("central-staging")
    from(staging) {
        include("$publicationPath/**")
        exclude("**/*.asc.*")
    }
    doFirst {
        check(!version.toString().endsWith("SNAPSHOT")) { "Central release bundles cannot contain snapshots." }
        val directory = staging.get().asFile.resolve(publicationPath)
        for (suffix in listOf(".pom", ".jar", "-sources.jar", "-javadoc.jar", ".module")) {
            val artifact = directory.resolve("rwml-core-${version}$suffix")
            for (ending in listOf("", ".asc", ".md5", ".sha1")) {
                check(artifact.resolveSibling(artifact.name + ending).isFile) {
                    "Missing ${artifact.name}$ending. Configure GPG signing before building a Central bundle."
                }
            }
        }
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    archiveFileName.set("rwml-${version}-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
}

// ------------------------------------------------------------------------------------------------
// packageGameJar：构建可替换游戏 game-lib.jar 的文件。
// ------------------------------------------------------------------------------------------------
val gameJarOverride = providers.gradleProperty("rwmlGameJar")

val packageGameJar = tasks.register<PackageGameJarTask>("packageGameJar") {
    group = "distribution"
    description = "Merges the remapped game into RWML's jar and writes the drop-in game-lib.jar " +
            "replacement to build/dist."
    if (!gameJarOverride.isPresent) dependsOn(remapFromEnigma)

    rwmlJar.set(project(":desktop").tasks.named<Jar>("jar").flatMap { it.archiveFile })
    gameJar.set(
        gameJarOverride
            .map { layout.projectDirectory.file(it) }
            .orElse(remapFromEnigma.flatMap { it.outputJar })
    )
    targetJavaVersion.set(providers.gradleProperty("rwmlTargetJavaVersion").getOrElse("25"))
    outputJar.set(layout.buildDirectory.file("dist/game-lib.jar"))
}

/**
 * 将游戏合并到 RWML 的 jar 中。
 */
abstract class PackageGameJarTask : DefaultTask() {

    @get:InputFile
    abstract val rwmlJar: RegularFileProperty

    @get:InputFile
    abstract val gameJar: RegularFileProperty

    @get:Input
    abstract val targetJavaVersion: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun packageJar() {
        val entries = LinkedHashMap<String, ByteArray>()
        var fromGame = 0

        for ((label, source) in listOf(
            "rwml" to rwmlJar.get().asFile,
            "game" to gameJar.get().asFile
        )) {
            ZipFile(source).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || isExcludedEntry(entry.name)) {
                        continue
                    }
                    if (entries.containsKey(entry.name)) {
                        logger.debug("Skipping duplicate entry ${entry.name} (from$label)")
                        continue
                    }
                    entries[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
                    if (label == "game") {
                        fromGame++
                    }
                }
            }
        }

        val output = outputJar.get().asFile
        output.parentFile.mkdirs()
        BufferedOutputStream(FileOutputStream(output)).use { fileStream ->
            JarOutputStream(fileStream).use { out ->
                out.putNextEntry(JarEntry(JarFile.MANIFEST_NAME))
                buildManifest().write(out)
                out.closeEntry()

                out.putNextEntry(JarEntry(BOOTSTRAP_PROPERTIES))
                out.write(bootstrapProperties().toByteArray(Charsets.UTF_8))
                out.closeEntry()

                for (directory in directoryEntries(entries.keys)) {
                    out.putNextEntry(JarEntry(directory))
                    out.closeEntry()
                }
                for ((name, bytes) in entries) {
                    out.putNextEntry(JarEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }

        verifyOutput(output)

        logger.lifecycle(
            "Packaged ${output.name}: ${entries.size} entries ($fromGame from the game), " +
                    "${output.length() / 1024} KiB"
        )
        logger.lifecycle(
            "  Main-Class: $GAME_ENTRY_POINT (RWML bootstrap, Java${targetJavaVersion.get().trim()})"
        )
        logger.lifecycle("  handed over to: $GAME_LAUNCHER")
    }

    private fun verifyOutput(output: File) {
        val bootstrap = GAME_ENTRY_POINT.replace('.', '/')
        val launcher = GAME_LAUNCHER.replace('.', '/')
        ZipFile(output).use { zip ->
            check(zip.getEntry("$bootstrap$CLASS_SUFFIX") != null) {
                "The RWML bootstrap $GAME_ENTRY_POINT is missing from the packaged jar."
            }
            check(zip.getEntry("$launcher$CLASS_SUFFIX") != null) {
                "The game entry point $GAME_LAUNCHER is missing from the packaged jar. The jar " +
                        "being merged has to come from :remapFromEnigma, which gives the game's own " +
                        "Main that name - an unremapped game-lib.jar would still contain $GAME_ENTRY_POINT."
            }
        }
    }

    private fun isExcludedEntry(name: String): Boolean {
        val upper = name.uppercase()
        return when {
            upper == JarFile.MANIFEST_NAME.uppercase() -> true
            upper.startsWith("META-INF/VERSIONS/") -> true
            upper == "META-INF/INDEX.LIST" -> true
            upper == "MODULE-INFO.CLASS" -> true
            upper.startsWith("META-INF/") && (
                    upper.endsWith(".SF") || upper.endsWith(".RSA") ||
                            upper.endsWith(".DSA") || upper.endsWith(".EC")
                    ) -> true
            else -> false
        }
    }

    private fun directoryEntries(names: Collection<String>): List<String> {
        val directories = sortedSetOf<String>()
        for (name in names) {
            var slash = name.lastIndexOf('/')
            while (slash > 0) {
                directories.add(name.substring(0, slash + 1))
                slash = name.lastIndexOf('/', slash - 1)
            }
        }
        return directories.toList()
    }

    private fun buildManifest(): Manifest {
        val manifest = Manifest()
        ZipFile(gameJar.get().asFile).use { zip ->
            val entry = zip.getEntry(JarFile.MANIFEST_NAME)
            if (entry != null) {
                zip.getInputStream(entry).use { stream ->
                    for ((key, value) in Manifest(stream).mainAttributes) {
                        manifest.mainAttributes[key] = value
                    }
                }
            }
        }
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = GAME_ENTRY_POINT
        manifest.mainAttributes.putValue("Premain-Class", "io.github.rwml.asm.RwmlAgent")
        manifest.mainAttributes.putValue("Agent-Class", "io.github.rwml.asm.RwmlAgent")
        manifest.mainAttributes.putValue("Can-Redefine-Classes", "true")
        manifest.mainAttributes.putValue("Can-Retransform-Classes", "true")
        manifest.mainAttributes.putValue("Implementation-Title", "RWML")
        manifest.mainAttributes.putValue("Implementation-Version", project.version.toString())
        manifest.mainAttributes.putValue("RWML-Target-Java-Version", targetJavaVersion.get().trim())
        return manifest
    }

    private fun bootstrapProperties() = buildString {
        append("# Written by the :packageGameJar Gradle task. Do not edit.\n")
        append("# Consumed by io.github.rwml.bootstrap.GameEntry.\n")
        append("targetJavaVersion=").append(targetJavaVersion.get().trim()).append('\n')
    }

    private companion object {
        const val GAME_ENTRY_POINT = "com.corrodinggames.rts.java.Main"
        const val GAME_LAUNCHER = "com.corrodinggames.rts.java.MainLauncher"
        const val CLASS_SUFFIX = ".class"
        const val BOOTSTRAP_PROPERTIES = "META-INF/rwml-bootstrap.properties"
    }
}

abstract class RemapFromEnigmaTask : DefaultTask() {
    @get:InputDirectory
    abstract val enigmaDir: DirectoryProperty

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:Input
    abstract val fromNamespace: Property<String>

    @get:Input
    abstract val toNamespace: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:OutputFile
    @get:Optional
    abstract val tinyCache: RegularFileProperty

    @get:OutputFile
    abstract val derivedReport: RegularFileProperty

    @TaskAction
    fun remap() {
        val enigmaPath = enigmaDir.get().asFile.toPath()
        val input = inputJar.get().asFile.toPath()
        val destination = outputJar.get().asFile.toPath()
        val output = File(temporaryDir, "game-lib-remapping.jar").toPath()
        val from = fromNamespace.get()
        val to = toNamespace.get()

        val tree = MemoryMappingTree()
        MappingReader.read(enigmaPath, MappingFormat.ENIGMA_DIR, tree)
        logger.lifecycle("Loaded mappings from Enigma directory: $enigmaPath")

        val normalised = normaliseNestedTargetNames(tree, to)
        logger.lifecycle("Normalised ${normalised.size} named nested-class targets.")
        normalised.take(3).forEach { logger.lifecycle("  $it") }

        val derived = addNestedClassMappings(tree, input, to)
        logger.lifecycle(
            "Derived ${derived.size} nested-class mappings from${tree.classes.size} mapped classes."
        )
        derived.take(3).forEach { logger.lifecycle("  $it") }

        val report = derivedReport.get().asFile
        report.parentFile.mkdirs()
        report.writeText(derived.joinToString("\n", "", "\n"), Charsets.UTF_8)

        val tinyFile = tinyCache.get().asFile
        tinyFile.parentFile.mkdirs()

        MappingWriter.create(
            tinyFile.toPath(),
            MappingFormat.TINY_2_FILE
        ).use { writer ->
            tree.accept(writer)
        }
        logger.lifecycle("Wrote mappings to Tiny v2: ${tinyFile.absolutePath}")

        if (java.nio.file.Files.isRegularFile(destination)) {
            logger.lifecycle("Reusing immutable mapped game archive $destination")
            return
        }

        val remapper: TinyRemapper = TinyRemapper.newRemapper()
            .withMappings(
                TinyUtils.createTinyMappingProvider(
                    tinyFile.toPath(),
                    from,
                    to
                )
            )
            // ProGuard replaced SourceFile with the literal "SourceFile". IDEA resolves attached
            // sources by package + this attribute, so mapped class names alone are insufficient.
            .rebuildSourceFilenames(true)
            .threads(Runtime.getRuntime().availableProcessors())
            .build()

        // Only the private task staging file is replaceable. Published/generated revisions are immutable.
        output.toFile().parentFile.mkdirs()
        val outputFile = output.toFile()
        check(!outputFile.exists() || outputFile.delete()) {
            "Could not replace the existing remap output ${outputFile.absolutePath}."
        }

        try {
            remapper.readInputs(input)

            OutputConsumerPath.Builder(output).build().use { consumer ->
                consumer.addNonClassFiles(
                    input,
                    NonClassCopyMode.FIX_META_INF,
                    remapper
                )
                remapper.apply(consumer)
            }
        } finally {
            remapper.finish()
        }

        val restoredInnerClasses = restoreNamedInnerClasses(input, output, tree, to)
        logger.lifecycle("Restored missing InnerClasses entries in $restoredInnerClasses class files.")
        java.nio.file.Files.createDirectories(destination.parent)
        try {
            java.nio.file.Files.move(output, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(output, destination)
        }
        logger.lifecycle("Remapping complete: $input ->$destination ($from ->$to)")
    }

    /**
     * Some bundled classes contain nested class files but omit their InnerClasses attributes. Restore named member
     * relationships from source metadata where available, then from a clear binary-name/mapping relationship. The
     * latter also covers flattened mapped names such as `Root$TableData` -> `RootTableData`. Keep existing entries;
     * skip classes marked as local or anonymous by numeric binary-name segments or an EnclosingMethod attribute.
     */
    private fun restoreNamedInnerClasses(
        input: Path,
        output: Path,
        tree: MemoryMappingTree,
        toNamespace: String,
    ): Int {
        val dstNs = tree.getNamespaceId(toNamespace)
        check(dstNs >= 0) {
            "The mapping namespaces are ${tree.srcNamespace} ->${tree.dstNamespaces}, which does not contain \"$toNamespace\"."
        }

        val sourceToTarget = LinkedHashMap<String, String>()
        tree.classes.forEach { cls ->
            val source = cls.srcName
            val target = cls.getDstName(dstNs)
            if (source != null) sourceToTarget[source] = target ?: source
        }

        val sourceClassNames = jarClassNames(input).toSet()
        sourceClassNames.forEach { sourceToTarget.putIfAbsent(it, it) }

        data class SourceInnerClass(
            val outerName: String?,
            val innerName: String?,
            val flags: Int,
        )

        data class SourceClass(
            val hasEnclosingMethod: Boolean,
            val flags: Int,
            val possibleOuterOwners: Set<String>,
        )

        val sourceClasses = LinkedHashMap<String, SourceClass>()
        val sourceInnerClasses = LinkedHashMap<String, MutableList<SourceInnerClass>>()
        ZipFile(input.toFile()).use { inputZip ->
            val classFile = ClassFile.of()
            val entries = inputZip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/versions/")) continue

                val sourceName = entry.name.removeSuffix(".class")
                val model = classFile.parse(inputZip.getInputStream(entry).use { it.readBytes() })
                val attributes = model.attributes()
                val possibleOuterOwners = model.fields().mapNotNull { field ->
                    val descriptor = field.fieldType().stringValue()
                    val isPotentialOuter = field.fieldName().stringValue().startsWith("this\$") ||
                            field.flags().has(java.lang.reflect.AccessFlag.SYNTHETIC)
                    if (isPotentialOuter && descriptor.startsWith("L") && descriptor.endsWith(";")) {
                        descriptor.substring(1, descriptor.length - 1)
                    } else {
                        null
                    }
                }.toSet()
                sourceClasses[sourceName] = SourceClass(
                    hasEnclosingMethod = attributes.any { it.attributeName().stringValue() == "EnclosingMethod" },
                    flags = model.flags().flagsMask(),
                    possibleOuterOwners = possibleOuterOwners,
                )

                attributes.filterIsInstance<InnerClassesAttribute>().forEach { attribute ->
                    attribute.classes().forEach { innerClass ->
                        val childName = innerClass.innerClass().asInternalName()
                        sourceInnerClasses.getOrPut(childName) { ArrayList() }.add(
                            SourceInnerClass(
                                outerName = innerClass.outerClass().orElse(null)?.asInternalName(),
                                innerName = innerClass.innerName().orElse(null)?.stringValue(),
                                flags = innerClass.flagsMask(),
                            )
                        )
                    }
                }
            }
        }

        fun hasLocalOrAnonymousAncestor(sourceName: String): Boolean {
            var current = sourceName
            while (true) {
                val simpleName = current.substringAfterLast('/')
                if (simpleName.split('$').drop(1).any { it.isEmpty() || it.first().isDigit() }) return true
                if (sourceClasses[current]?.hasEnclosingMethod == true) return true

                val separator = current.lastIndexOf('$')
                if (separator <= current.lastIndexOf('/')) return false
                current = current.substring(0, separator)
            }
        }

        val entriesByOwner = LinkedHashMap<String, LinkedHashMap<String, InnerClassInfo>>()
        for ((sourceName, targetName) in sourceToTarget) {
            if (sourceName !in sourceClassNames) continue

            val sourceEntries = sourceInnerClasses[sourceName].orEmpty()
            val memberEntries = sourceEntries
                .filter { it.outerName != null && it.innerName != null }
                .distinctBy { Triple(it.outerName, it.innerName, it.flags) }
            // An explicit local/anonymous/non-member record is evidence against inventing member metadata.
            if (sourceEntries.isNotEmpty() && memberEntries.isEmpty()) continue
            if (memberEntries.size > 1) continue

            val sourceMember = memberEntries.singleOrNull()
            if (hasLocalOrAnonymousAncestor(sourceName)) continue

            val sourceOwner = sourceMember?.outerName ?: sourceName.let { name ->
                val separator = name.lastIndexOf('$')
                if (separator > name.lastIndexOf('/')) name.substring(0, separator) else ""
            }
            if (sourceOwner.isEmpty() || sourceOwner !in sourceClassNames) continue
            if (hasLocalOrAnonymousAncestor(sourceOwner)) continue

            val targetOwner = sourceToTarget[sourceOwner] ?: continue
            if (targetName == targetOwner) continue
            if (sourceMember == null &&
                    targetName.substringBeforeLast('/', "") != targetOwner.substringBeforeLast('/', "")) continue

            val binaryPrefix = "$targetOwner\$"
            val hasMappedOwnerPrefix = targetName.startsWith(binaryPrefix) ||
                    (targetName.startsWith(targetOwner) && targetName.length > targetOwner.length)
            // Without source InnerClasses evidence, a mapped owner prefix distinguishes flattened member names
            // from an explicitly named top-level class that happens to contain '$' in its source name.
            if (sourceMember == null && !hasMappedOwnerPrefix) continue

            val nestedName = when {
                targetName.startsWith(binaryPrefix) -> targetName.removePrefix(binaryPrefix)
                targetName.startsWith(targetOwner) -> targetName.removePrefix(targetOwner)
                else -> targetName.substringAfterLast('/')
            }.removePrefix("\$")
            if (nestedName.isEmpty() || nestedName.first().isDigit()) continue

            val classInfo = sourceClasses[sourceName] ?: continue
            val flags = if (sourceMember != null) {
                sourceMember.flags
            } else {
                val hasOuterInstance = sourceOwner in classInfo.possibleOuterOwners
                val memberFlags = classInfo.flags and INNER_CLASS_FLAGS
                if (hasOuterInstance) memberFlags else memberFlags or ClassFile.ACC_STATIC
            }
            val nestedInfo = InnerClassInfo.of(
                ClassDesc.of(targetName.replace('/', '.')),
                java.util.Optional.of(ClassDesc.of(targetOwner.replace('/', '.'))),
                java.util.Optional.of(nestedName),
                flags
            )

            entriesByOwner.getOrPut(targetOwner) { LinkedHashMap() }[targetName] = nestedInfo
            entriesByOwner.getOrPut(targetName) { LinkedHashMap() }[targetName] = nestedInfo
        }

        if (entriesByOwner.isEmpty()) return 0

        fun compilationUnitName(targetName: String): String {
            var owner = targetName
            val visited = HashSet<String>()
            while (visited.add(owner)) {
                owner = entriesByOwner[owner]?.get(owner)?.outerClass()?.orElse(null)?.asInternalName() ?: break
            }
            return owner.substringAfterLast('/').substringBefore('$') + ".java"
        }

        val outputFile = output.toFile()
        val temporary = java.nio.file.Files.createTempFile(outputFile.parentFile.toPath(), "${outputFile.name}.", ".tmp")
        var restored = 0
        val restoredRelationships = LinkedHashMap<String, InnerClassInfo>()
        try {
            ZipFile(outputFile).use { zip ->
                JarOutputStream(java.nio.file.Files.newOutputStream(temporary)).use { jar ->
                    val classFile = ClassFile.of()
                    val entryEnumeration = zip.entries()
                    while (entryEnumeration.hasMoreElements()) {
                        val entry = entryEnumeration.nextElement()
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val targetName = entry.name.removeSuffix(".class")
                        val nestedEntries = if (entry.name.endsWith(".class")) entriesByOwner[targetName] else null
                        val result = if (nestedEntries == null || nestedEntries.isEmpty()) {
                            bytes
                        } else {
                            val model = classFile.parse(bytes)
                            val existingInnerClasses = model.attributes().filterIsInstance<InnerClassesAttribute>()
                            // Some mapped members have flattened binary names (RootTableData),
                            // while their source lives in the restored outer class (Root.java).
                            val sourceFileName = compilationUnitName(targetName)
                            val existingSourceFiles = model.attributes().filterIsInstance<SourceFileAttribute>()
                            val sourceFileChanged = existingSourceFiles.singleOrNull()?.sourceFile()?.stringValue() != sourceFileName
                            val mergedEntries = LinkedHashMap<String, InnerClassInfo>()
                            existingInnerClasses.forEach { attribute ->
                                attribute.classes().forEach { innerClass ->
                                    mergedEntries.putIfAbsent(innerClass.innerClass().asInternalName(), innerClass)
                                }
                            }
                            val missingEntries = nestedEntries.filterKeys { it !in mergedEntries }
                            missingEntries.forEach { (innerName, info) ->
                                mergedEntries[innerName] = info
                                restoredRelationships.putIfAbsent(innerName, info)
                            }
                            if (missingEntries.isEmpty() && existingInnerClasses.size <= 1 && !sourceFileChanged) {
                                bytes
                            } else {
                                restored++
                                val info = mergedEntries.values.sortedBy { it.innerClass().asInternalName() }
                                classFile.transformClass(
                                    model,
                                    ClassTransform.dropping { element -> element is InnerClassesAttribute || element is SourceFileAttribute }
                                        .andThen(ClassTransform.endHandler { builder ->
                                        builder.with(InnerClassesAttribute.of(info))
                                        builder.with(SourceFileAttribute.of(sourceFileName))
                                        })
                                )
                            }
                        }

                        val outputEntry = java.util.jar.JarEntry(entry.name)
                        if (entry.time >= 0L) outputEntry.time = entry.time
                        jar.putNextEntry(outputEntry)
                        jar.write(result)
                        jar.closeEntry()
                    }
                }
            }
            replaceRemappedJar(temporary, outputFile)
        } finally {
            java.nio.file.Files.deleteIfExists(temporary)
        }

        if (restoredRelationships.isNotEmpty()) {
            logger.lifecycle("Added InnerClasses entries for ${restoredRelationships.size} named member classes.")
            restoredRelationships.values
                .filter { innerClass ->
                    val targetOwner = innerClass.outerClass().orElse(null)?.asInternalName() ?: return@filter false
                    val targetName = innerClass.innerClass().asInternalName()
                    targetName.startsWith(targetOwner) && !targetName.startsWith("$targetOwner\$")
                }
                .take(5)
                .forEach { innerClass ->
                    val targetOwner = innerClass.outerClass().orElse(null)?.asInternalName() ?: return@forEach
                    val innerName = innerClass.innerName().orElse(null)?.stringValue() ?: return@forEach
                    logger.lifecycle(
                        "  ${innerClass.innerClass().asInternalName()} is ${targetOwner}.$innerName"
                    )
                }
        }
        return restored
    }

    private fun replaceRemappedJar(temporary: Path, output: File) {
        try {
            java.nio.file.Files.move(
                temporary,
                output.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temporary, output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun jarClassNames(jar: Path): List<String> =
        ZipFile(jar.toFile()).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") && !it.startsWith("META-INF/versions/") }
                .map { it.removeSuffix(".class") }
                .sorted()
                .toList()
        }

    private fun addNestedClassMappings(
        tree: MemoryMappingTree,
        jar: Path,
        toNamespace: String,
    ): List<String> {
        val dstNs = tree.getNamespaceId(toNamespace)
        check(dstNs >= 0) {
            "The mapping namespaces are ${tree.srcNamespace} ->${tree.dstNamespaces}, which does " +
                    "not contain \"$toNamespace\"."
        }

        val renamed = HashMap<String, String>()
        tree.classes.forEach { cls ->
            val source = cls.srcName
            val target = cls.getDstName(dstNs)
            if (source != null && target != null) renamed[source] = target
        }

        val taken = HashSet(renamed.values)
        val derived = ArrayList<String>()
        jarClassNames(jar).forEach { name ->
            val separator = name.indexOf('$')
            if (separator < 0 || renamed.containsKey(name)) return@forEach

            val outerTarget = renamed[name.substring(0, separator)] ?: return@forEach
            val target = nestedTarget(outerTarget, name.substring(separator + 1))
            check(taken.add(target)) {
                "The nested class $name and another class both want the name $target. It needs an " +
                        "explicit CLASS line in work/mappings."
            }

            val cls = tree.getClass(name) ?: run {
                tree.visitClass(name)
                tree.getClass(name)
            }
            if (cls != null) {
                cls.setDstName(target, dstNs)
                derived.add("$name ->$target")
            }
        }
        return derived
    }

    /**
     * 内部类相对于外部类的名称。匿名类保留数字后缀（如 `$1`）；具名内部类则直接拼接，首字母大写且不加分隔符。
     */
    private fun nestedTarget(outerTarget: String, suffix: String): String =
        outerTarget + suffix.split('$').joinToString("") { segment ->
            if (segment.toIntOrNull() != null) "$" + segment
            else segment.substring(0, 1).uppercase() + segment.substring(1)
        }

    /**
     * Enigma 读取器会用 `$` 将内部类目标名拼接到外部类目标名后。将具名内部类目标改写为 [nestedTarget] 格式，
     * 匿名类和未修改的第三方类保持不变。
     */
    private fun normaliseNestedTargetNames(
        tree: MemoryMappingTree,
        toNamespace: String,
    ): List<String> {
        val dstNs = tree.getNamespaceId(toNamespace)
        check(dstNs >= 0) {
            "The mapping namespaces are ${tree.srcNamespace} ->${tree.dstNamespaces}, which does " +
                    "not contain \"$toNamespace\"."
        }

        val changed = ArrayList<String>()
        tree.classes.toList().forEach { cls ->
            val source = cls.srcName ?: return@forEach
            val target = cls.getDstName(dstNs) ?: return@forEach
            if (target == source) return@forEach

            val separator = target.lastIndexOf('$')
            if (separator < 0) return@forEach
            val suffix = target.substring(separator + 1)
            if (suffix.toIntOrNull() != null) return@forEach

            val joined = nestedTarget(target.substring(0, separator), suffix)
            if (joined != target) {
                cls.setDstName(joined, dstNs)
                changed.add("$source ->$joined")
            }
        }
        return changed
    }

    private companion object {
        const val INNER_CLASS_FLAGS =
            ClassFile.ACC_PUBLIC or ClassFile.ACC_PRIVATE or ClassFile.ACC_PROTECTED or ClassFile.ACC_FINAL or
                    ClassFile.ACC_INTERFACE or ClassFile.ACC_ABSTRACT or ClassFile.ACC_SYNTHETIC or
                    ClassFile.ACC_ANNOTATION or ClassFile.ACC_ENUM
    }
}

// 反编译源码没有原始 API 文档。Central 允许使用占位文档；IDE 用户可直接查看对应源码。
val gameJavadocJar = tasks.register<Jar>("gameJavadocJar") {
    archiveBaseName.set("game-lib")
    archiveVersion.set(gameLibVersion)
    archiveClassifier.set("javadoc")
    from(layout.projectDirectory.dir("docs/game-lib"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications {
        create<MavenPublication>("gameLib") {
            groupId = "io.github.rwpp-team"
            artifactId = "game-lib"
            version = gameLibVersion
            artifact(remapFromEnigma.flatMap { it.outputJar }) {
                builtBy(remapFromEnigma)
            }
            artifact(genSources.flatMap { it.outputJar }) {
                classifier = "sources"
                builtBy(genSources)
            }
            artifact(gameJavadocJar)
            pom {
                name.set("Rusted Warfare mapped game library")
                description.set("Rusted Warfare 1.15 game classes with Anvil mappings and decompiled sources for extension development.")
                url.set("https://github.com/RWPP-Team/Anvil")
                licenses {
                    license {
                        name.set("CC0 1.0 Universal")
                        url.set("https://creativecommons.org/publicdomain/zero/1.0/")
                    }
                }
                developers {
                    developer {
                        id.set("rwpp-team")
                        name.set("RWPP Team")
                        email.set("1225327866@qq.com")
                        organization.set("RWPP-Team")
                        organizationUrl.set("https://github.com/RWPP-Team")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/RWPP-Team/Anvil.git")
                    developerConnection.set("scm:git:ssh://github.com/RWPP-Team/Anvil.git")
                    url.set("https://github.com/RWPP-Team/Anvil")
                }
            }
        }
    }
    repositories {
        maven {
            name = "gameStaging"
            url = uri(layout.buildDirectory.dir("game-central-staging"))
        }
    }
}

signing {
    val key = providers.gradleProperty("signingInMemoryKey").orNull?.takeIf { it.isNotBlank() }
        ?: System.getenv("GPG_PRIVATE_KEY")?.takeIf { it.isNotBlank() }
    val keyId = providers.gradleProperty("signing.gnupg.keyName").orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty("signing.keyId").orNull?.takeIf { it.isNotBlank() }
    isRequired = key != null || keyId != null
    if (key != null) {
        useInMemoryPgpKeys(key, providers.gradleProperty("signingInMemoryPassword").orNull
            ?.takeIf { it.isNotBlank() } ?: System.getenv("GPG_PASSPHRASE"))
        sign(publishing.publications["gameLib"])
    } else if (keyId != null) {
        useGpgCmd()
        sign(publishing.publications["gameLib"])
    }
}

tasks.register<Zip>("bundleGameLibForCentral") {
    group = "publishing"
    description = "Builds the signed mapped game library bundle for Maven Central."
    dependsOn("verifyGameSources", "publishGameLibPublicationToGameStagingRepository")
    val publicationPath = "io/github/rwpp-team/game-lib/$gameLibVersion"
    val staging = layout.buildDirectory.dir("game-central-staging")
    from(staging) {
        include("$publicationPath/**")
        exclude("**/*.asc.*")
    }
    doFirst {
        check(!gameLibVersion.endsWith("SNAPSHOT")) { "Central releases cannot contain snapshots." }
        val directory = staging.get().asFile.resolve(publicationPath)
        for (suffix in listOf(".pom", ".jar", "-sources.jar", "-javadoc.jar")) {
            val artifact = directory.resolve("game-lib-$gameLibVersion$suffix")
            for (ending in listOf("", ".asc", ".md5", ".sha1")) {
                check(artifact.resolveSibling(artifact.name + ending).isFile) {
                    "Missing ${artifact.name}$ending. Configure GPG signing before bundling."
                }
            }
        }
    }
    archiveFileName.set("game-lib-$gameLibVersion-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
