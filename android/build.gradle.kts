/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// ------------------------------------------------------------------------------------------------
// Android 应用负责启动引导，而不是在构建时打补丁。
//
// 桌面宿主可以通过 -javaagent 启动游戏，让框架在类加载时改写类。Android 没有这样的钩子，因此应用需在运行时
// 看到类之前自行完成相同工作：根据扩展 mixin 改写游戏，将结果编译为 dex 并缓存。APK 因此需要暂存待改写的归档
// 及其摘要，应用会据此判断自身存储中的副本是否仍为最新版本。dex 转换器随应用打包，而非构建环境。
// ------------------------------------------------------------------------------------------------

plugins {
    alias(libs.plugins.android.application)
}

group = "io.github.rwpp-team"
version = rootProject.version
description = "RWML Android bootstrapper: rewrites and dexes the game on the device, then caches it."

// Android 平台版本和 Java 版本统一在 gradle/libs.versions.toml 中声明，避免重复定义。
val androidCompileSdk = libs.versions.android.compile.sdk.get().toInt()
val androidMinSdk = libs.versions.android.min.sdk.get().toInt()
val androidTargetSdk = libs.versions.android.target.sdk.get().toInt()
val androidJavaVersion = JavaVersion.toVersion(libs.versions.android.java.version.get())

/**
 * 将项目版本转换为 Android 用于比较的整数，避免发布时只更新其中一个版本号：`1.2.3` 会变成 `10203`，
 * 预发布后缀会被忽略。
 */
fun androidVersionCode(version: String): Int {
    val numbers = version.substringBefore('-')
        .split('.')
        .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    val major = numbers.getOrElse(0) { 0 }
    val minor = numbers.getOrElse(1) { 0 }
    val patch = numbers.getOrElse(2) { 0 }
    require(major in 0..99 && minor in 0..99 && patch in 0..99) {
        "version $version does not fit the major.minor.patch scheme this versionCode uses"
    }
    return major * 10000 + minor * 100 + patch
}

android {
    namespace = "io.github.rwml.android"
    compileSdk = androidCompileSdk

    defaultConfig {
        applicationId = "io.github.rwml.android"
        minSdk = androidMinSdk
        targetSdk = androidTargetSdk
        versionCode = androidVersionCode(version.toString())
        versionName = version.toString()
        testInstrumentationRunner = providers.gradleProperty("rwmlAndroidTestRunner")
            .orElse("io.github.rwml.android.builtin.NavigationRegressionRunner").get()
    }

    compileOptions {
        sourceCompatibility = androidJavaVersion
        targetCompatibility = androidJavaVersion
    }

    buildTypes {
        getByName("release") {
            // 应用通过反射访问 dex 转换器、字节码框架和扩展，因此这些名称不得被混淆改写。proguard-rules.pro
            // 为将来启用代码缩减做好准备，并不表示当前已启用缩减。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        // 游戏以仅编译依赖的形式提供，而 lint 会把游戏类当作应用自身代码来检查。游戏会发布通知，本应用不会发布，
        // 也不应由本应用持有相应权限。
        disable += "NotificationPermission"

        // 列表中的其余文件都是原样还原的游戏资源。它们使用自己的视图、属性和命名空间，但 lint 会把这些都当成本模块
        // 的错误：要求将某命名空间写作 res-auto、要求为游戏未加前缀的属性补前缀，以及找不到本模块编译所依赖归档中
        // 不包含的游戏视图类。这些问题都不应由本模块修复；修改还原文件以通过 lint 会导致文件不再是原样副本。
        disable += setOf("ResAuto", "MissingClass", "MissingPrefix")
    }

    packaging {
        resources {
            // 合并时不允许重名条目，而此处携带的少数 jar 会包含相同元数据。重复项内容相同，因此每种只保留第一份。
            pickFirsts += setOf("gradleRes/**", "kotlin/**", "META-INF/native-image/**")

            // 多版本类，平台不会读取这些类。
            excludes += setOf("META-INF/versions/**")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":game-adapter"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)

    compileOnly(libs.game.lib)

    implementation(libs.dalvik.dx)
    implementation(libs.org.ow2.asm.asm3)
    implementation(libs.org.ow2.asm.asm.tree)
    implementation(libs.org.ow2.asm.asm.commons3)
    implementation(libs.org.ow2.asm.asm.util)

    implementation(libs.asm.framework)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.core)
    // Extensions share the host's Kotlin runtime through parent-first loading.
    // Keep its coroutine ABI compatible with Kotlin 2.4 extensions.
    implementation(libs.kotlinx.coroutines.android)
    // ComponentActivity and extension surfaces share these platform owner types.
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.collection)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestCompileOnly(libs.game.lib)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("rwml.testGameArchive",
        (rootProject.extra["rwmlMappedGameJar"] as Provider<RegularFile>).get().asFile.absolutePath)
}

// Publish the matching game binary and sources before starting a new Gradle invocation.

// ------------------------------------------------------------------------------------------------
// 游戏垫片：补齐暂存归档缺少的内容。
//
// 此模块暂存的归档是游戏中与平台无关的部分，但它引用了两类自身未包含的内容。文件读取器引用了仅存在于 Android 构建中
// 的 `AndroidSAF` 和 `SAFInterface$FileRow`；设备加载语言包时会立即发生 NoClassDefFoundError。另外，归档中自带的
// `android/support/*` 类保留了支持库成员名称，却没有方法实现：`ContextCompat.checkSelfPermission` 会抛异常，
// `ActivityCompat.requestPermissions` 则会直接返回而不弹出请求，导致引擎阶段以“Placeholder only”错误退出。平台不提供
// 支持库，因此这些类必须进入游戏 dex。
//
// 垫片在此处针对模块使用的同一 android.jar 编译，不会引用游戏类。下面的暂存任务会将它写入归档：同名条目会被替换，
// 其余条目会新增。垫片随游戏归档提供，因此应用自身 dex 无需携带游戏类，mixin 规则也保持不变。合并归档的摘要会改变，
// 设备据此识别缓存副本已过期，无需提升缓存格式版本。
// ------------------------------------------------------------------------------------------------

/** 垫片类的输出位置，以及归档条目名称所依据的根目录。 */
val gameShimClassesDirectory = layout.buildDirectory.dir("gameShim/classes")

val compileGameShim = tasks.register<JavaCompile>("compileGameShim") {
    group = "rwml"
    description = "Compiles the classes the staged game archive lacks or only carries as placeholders."
    source("src/gameShim/java")
    destinationDirectory = gameShimClassesDirectory

    // 下方设置的 android.jar 是完整类路径，不含其他内容。
    classpath = files()

    // 使用 Java 8，避免生成设备 dex 转换器无法降级的 invokedynamic 字符串拼接指令。
    sourceCompatibility = "8"
    targetCompatibility = "8"
}

/** 以目录树形式获取垫片类文件；任务输出还包含其他文件，而且普通目录参数不会递归遍历。 */
val gameShimClasses = fileTree(gameShimClassesDirectory.get().asFile) {
    include("**/*.class")
    builtBy(compileGameShim)
}

// 垫片调用真实的平台 API，因此模块自己的 android.jar 就是其完整类路径。`sdkComponents` 在 AGP 9 中是属性而非配置块，
// 类路径会保持为 provider：在此读取会触发 AGP 的“模块自身级别尚未最终确定”保护。
androidComponents {
    compileGameShim.configure {
        options.bootstrapClasspath = files(sdkComponents.bootClasspath)
    }
}

// ------------------------------------------------------------------------------------------------
// 暂存：供应用改写的游戏归档及其摘要。
//
// 应用无法直接改写 APK，因此先将文件复制到自身存储，仅在摘要变化时重新复制。在此处暂存而非放在源码目录中，使 assets
// 目录成为构建输出，AGP 会读取该输出并依赖生成它的任务。
//
// RWML 只暂存待改写的游戏归档；扩展（包括 RWPP 携带的 API）由扩展管理器按需处理。
// ------------------------------------------------------------------------------------------------

/** 通过 -PrwmlGameJar=<path> 指定其他游戏 jar，以仓库根目录为相对路径。 */
val gameJarOverride = providers.gradleProperty("rwmlGameJar")

/** `:remapFromEnigma` 生成的映射后游戏；仅在文件存在时暂存。 */
@Suppress("UNCHECKED_CAST")
val remappedGameJar = rootProject.extra["rwmlMappedGameJar"] as Provider<RegularFile>

val stageAndroidAssets = tasks.register<StageAndroidAssetsTask>("stageAndroidAssets") {
    group = "rwml"
    description = "Stages the game archive, with its digest, into the app's assets."
    if (gameJarOverride.isPresent) {
        gameJar.from(rootProject.layout.projectDirectory.file(gameJarOverride.get()))
    } else {
        gameJar.from(remappedGameJar)

        // 该文件由 `:remapFromEnigma` 生成，存在时本任务必须等待它，因此只在文件存在时声明依赖。映射流程需要游戏 jar
        // 和 Anvil 子模块，但构建 debug APK 不应依赖两者：检出目录缺少这些内容时无需等待，应用仍可构建运行，
        // 只是没有游戏类可供改写。
        if (rootProject.file("work/game-lib.jar").isFile) {
            dependsOn(":remapFromEnigma")
        }
    }
    outputDirectory.set(layout.buildDirectory.dir("generated/rwml-assets"))
    // 暂存归档时会一并写入垫片类：替换归档中同名条目，并补入归档未包含但有引用的条目。
    dependsOn(compileGameShim)
    shimClasses.from(gameShimClasses)
    shimRoot.set(gameShimClassesDirectory)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            stageAndroidAssets,
            StageAndroidAssetsTask::outputDirectory
        )
    }
}

/**
 * 将应用要安装的归档与垫片合并，复制到专供应用使用的 assets 根目录。
 *
 * <p>输出目录中的所有内容都由此任务生成，因此不再暂存的归档会被删除，否则它仍会留在 APK 中。任务输出不依赖运行位置，
 * 因此可以缓存。</p>
 */
@CacheableTask
abstract class StageAndroidAssetsTask : DefaultTask() {

    /** 要暂存的游戏类（如果已生成）。这是文件集合，因此缺少 JAR 不算错误：应用仍可在没有游戏类的情况下运行。 */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val gameJar: ConfigurableFileCollection

    /** 替身类（如果已编译）。这些类会写入暂存归档；集合为空也是合法的。 */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shimClasses: ConfigurableFileCollection

    /** 根 `shimClasses` 所在的目录；据此将类文件转换为归档条目名称。 */
    @get:Internal
    abstract val shimRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val assetsRoot = outputDirectory.get().asFile
        val digests = sortedMapOf<String, String>()

        // 根目录完全由此任务管理：旧布局遗留的目录已不属于应用，若只清理暂存分组，它仍会残留。
        assetsRoot.listFiles().orEmpty()
            .filter { it.name != ASSET_ROOT }
            .forEach { stale ->
                stale.deleteRecursively()
                logger.lifecycle("  removed ${stale.name}  (not staged any more)")
            }

        val game = gameJar.files.toList()
        check(game.size <= 1) {
            "one game archive at a time, but ${game.size} were given: " +
                    game.joinToString { it.name }
        }
        stageGroup(assetsRoot, GAME_GROUP, game, digests)

        // 当前构建不再写入的分组已不属于应用。若不删除旧构建留下的副本，它仍会被打包进 APK。
        File(assetsRoot, ASSET_ROOT).listFiles().orEmpty()
            .filter { it.isDirectory && it.name != GAME_GROUP }
            .forEach { stale ->
                stale.deleteRecursively()
                logger.lifecycle("  removed $ASSET_ROOT/${stale.name}  (not staged any more)")
            }

        writeManifest(assetsRoot, digests)
    }

    /**
     * 将某分组的所有归档复制到 `<assets>/rwml/<group>`，然后删除该目录中的其他内容。文件不存在时跳过；
     * 即使没有暂存任何文件，应用也应能够启动。
     */
    private fun stageGroup(
        assetsRoot: File,
        group: String,
        archives: List<File>,
        digests: MutableMap<String, String>
    ) {
        val staged = archives.filter { it.isFile }.sortedBy { it.name }
        val directory = File(assetsRoot, "$ASSET_ROOT/$group")
        directory.mkdirs()

        val kept = mutableSetOf<String>()
        for (archive in staged) {
            check(kept.add(archive.name)) {
                "two archives in $ASSET_ROOT/$group are called ${archive.name}"
            }
            val stagedFile = File(directory, archive.name)
            if (group == GAME_GROUP) stageArchive(archive, stagedFile)
            else archive.copyTo(stagedFile, overwrite = true)
            val digest = sha256(stagedFile)
            digests["$group/${archive.name}"] = digest
            logger.lifecycle("  $group/${archive.name}  ${digest.take(12)}")
        }

        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name !in kept }
            .forEach { stale ->
                stale.delete()
                logger.lifecycle("  removed $group/${stale.name}  (not staged any more)")
            }

        if (staged.isEmpty()) {
            logger.lifecycle("  nothing staged for $group")
        }
    }

    /**
     * 写入一个暂存归档。没有垫片类时直接复制；否则先写入归档自身条目，再写入垫片条目，并跳过与垫片同名的游戏条目，
     * 从而替换占位实现。垫片条目的时间戳固定，因此输入未变时归档及其摘要也保持不变。
     */
    private fun stageArchive(archive: File, target: File) {
        val shims = shimEntries()
        if (shims.isEmpty()) {
            archive.copyTo(target, overwrite = true)
            return
        }
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            ZipFile(archive).use { source ->
                val entries = source.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || shims.containsKey(entry.name)) {
                        continue
                    }
                    val copy = ZipEntry(entry.name)
                    copy.time = entry.time
                    out.putNextEntry(copy)
                    source.getInputStream(entry).copyTo(out)
                    out.closeEntry()
                }
            }
            for (name in shims.keys.sorted()) {
                val entry = ZipEntry(name)
                entry.time = SHIM_TIMESTAMP
                out.putNextEntry(entry)
                shims[name]!!.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    /** 垫片类文件相对于 [shimRoot] 的 jar 条目路径。 */
    private fun shimEntries(): Map<String, File> {
        val root = shimRoot.get().asFile
        val entries = mutableMapOf<String, File>()
        for (file in shimClasses.files) {
            if (file.isFile && file.name.endsWith(".class")) {
                entries[file.relativeTo(root).invariantSeparatorsPath] = file
            }
        }
        return entries
    }

    /** 保证结果稳定：不写入时间戳，输入未变时不改动文件。 */
    private fun writeManifest(assetsRoot: File, digests: Map<String, String>) {
        val text = buildString {
            append("# 由 :android:stageAndroidAssets 生成，请勿手动修改。\n")
            append("# 由 io.github.rwml.android.AssetInstaller 读取。\n")
            digests.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
        }
        val manifest = File(assetsRoot, "$ASSET_ROOT/$MANIFEST_NAME")
        manifest.parentFile.mkdirs()
        if (!manifest.isFile || manifest.readText() != text) {
            manifest.writeText(text)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** 按 AssetInstaller.ASSET_ROOT 设置的 assets 暂存根目录。 */
        const val ASSET_ROOT = "rwml"

        /** 应用安装游戏类的分组名称，与 AndroidBootstrap 中的定义一致。 */
        const val GAME_GROUP = "game"
        /** AssetInstaller 读取摘要的文件。 */
        const val MANIFEST_NAME = "manifest.properties"

        /** 固定所有垫片类条目的时间，使合并后的归档可重复生成。 */
        const val SHIM_TIMESTAMP = 946684800000L
    }
}
