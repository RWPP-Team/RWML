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
// The Android app is a bootstrapper, not a build-time patcher.
//
// A desktop host can start the game with -javaagent and let the framework rewrite classes as they
// load. Android has no such hook, so the app does the same work itself, before the runtime sees the
// classes: it rewrites the game against the extension mixins, compiles the result to dex, and caches
// it. Two things therefore have to be staged into the APK - the archive to rewrite, and its digest,
// which is what tells the app whether the copy in its own storage is still current. The
// dexer travels in the app, not in the build.
// ------------------------------------------------------------------------------------------------

plugins {
    alias(libs.plugins.android.application)
}

group = "io.github.rwpp-team"
version = rootProject.version
description = "RWML Android bootstrapper: rewrites and dexes the game on the device, then caches it."

// The Android platform levels and the Java level live in gradle/libs.versions.toml so each is
// declared once.
val androidCompileSdk = libs.versions.android.compile.sdk.get().toInt()
val androidMinSdk = libs.versions.android.min.sdk.get().toInt()
val androidTargetSdk = libs.versions.android.target.sdk.get().toInt()
val androidJavaVersion = JavaVersion.toVersion(libs.versions.android.java.version.get())

/**
 * Turns a project version into the integer Android compares, so a release cannot bump one number and
 * forget the other: `1.2.3` becomes `10203`, and a prerelease suffix is ignored.
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
        testInstrumentationRunner = "io.github.rwml.android.builtin.NavigationRegressionRunner"
    }

    compileOptions {
        sourceCompatibility = androidJavaVersion
        targetCompatibility = androidJavaVersion
    }

    buildTypes {
        getByName("release") {
            // The app reaches the dexer, the bytecode framework and the extensions through
            // reflection, so nothing may be renamed away from it. proguard-rules.pro is what makes
            // shrinking possible the day it is turned on, not a statement that it is on.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        // The game arrives as a compile-only dependency, and lint reads its classes as though they
        // were this application's own code. The game posts notifications; this application does not,
        // and it is not the one that would hold the permission.
        disable += "NotificationPermission"

        // The rest of the list is the game's resources, which are restored exactly as they shipped.
        // They name views, attributes and namespaces of their own, and lint reads all three as this
        // module's mistakes: a namespace it wants written as res-auto, a prefix it wants on an
        // attribute the game declares without one, and the game's view classes, which the archive
        // this module compiles against does not carry. None of the three is this module's to fix,
        // and editing the restored files to satisfy lint would make them no longer a copy.
        disable += setOf("ResAuto", "MissingClass", "MissingPrefix")
    }

    packaging {
        resources {
            // Merging refuses two entries of one name, and a few of the jars carried here publish the
            // same metadata. The copies are equivalent, so the first of each is kept.
            pickFirsts += setOf("gradleRes/**", "kotlin/**", "META-INF/native-image/**")

            // Multi-release classes, which the platform does not consult.
            excludes += setOf("META-INF/versions/**")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":game-adapter"))

    // The bridge implements the game's own view interface, so it compiles against the game's names.
    // At run time those classes never come from here: they come from the dex the app rewrites,
    // compiles and mounts, which is why this is compileOnly and not implementation. The game's own
    // Android build treats its game libraries the same way. The coordinate is the build's own
    // repository, which is also where the decompiled source of that game is published, so the
    // interface can open it.
    compileOnly(libs.game.lib)

    // The device compiles the rewritten classes to dex itself, and nothing on the platform does
    // that, so the dexer has to be in the APK. `dalvik-dx` is AOSP's dx; it brings `libcore-dex`
    // with it. This is not a compile-only dependency for exactly that reason.
    implementation(libs.dalvik.dx)

    // The device-side rewriter and JarResources drive ASM directly, and those classes have to be in
    // the APK: they run on the device, not at build time.
    implementation(libs.org.ow2.asm.asm3)
    implementation(libs.org.ow2.asm.asm.tree)
    implementation(libs.org.ow2.asm.asm.commons3)
    implementation(libs.org.ow2.asm.asm.util)

    // The offline rewriter is the framework the desktop host installs as a transformer, driven by
    // hand instead of by the JVM: OfflineMixinBridge registers mixins in its registry and applies
    // them with its processor. It is a runtime dependency for that reason, and the framework is
    // written in Kotlin, so the standard library comes with it.
    implementation(libs.asm.framework)
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestCompileOnly(libs.game.lib)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("rwml.testGameArchive",
        rootProject.layout.buildDirectory.file("libs/game-lib-named.jar").get().asFile.absolutePath)
}

// The game above is produced by :remapFromEnigma out of work/game-lib.jar and the mapping set, and
// the coordinate this module reads it through is published with its sources by
// :publishGameLibForIde, so the compiler has to wait for both. It is declared only when the game jar
// is there, because assembling an APK on a checkout that has neither must not require the mapping
// pipeline.
if (rootProject.layout.projectDirectory.file("work/game-lib.jar").asFile.isFile) {
    tasks.withType<JavaCompile>().configureEach {
        dependsOn(":publishGameLibForIde")
    }
}

// ------------------------------------------------------------------------------------------------
// The game shim: what the staged archive is missing.
//
// The archive this module stages is the game's platform-independent half, and it names two kinds of
// things it does not carry. Its file readers reference `AndroidSAF` and `SAFInterface$FileRow`, which
// only the Android build has - on the device that is a NoClassDefFoundError the moment the language
// bundle loads. And its bundled `android/support/*` classes keep the support library's member names
// but no bodies: `ContextCompat.checkSelfPermission` throws and `ActivityCompat.requestPermissions`
// returns without asking anyone, which is how the engine stage dies with "Placeholder only". The
// platform does not supply the support library, so the game dex has to.
//
// The shim is compiled here against the same android.jar the module uses - none of it touches a game
// class - and the staging task below writes it into the archive: entries the shim also names are
// replaced, the rest are added. It travels with the game, so the app's own dex keeps carrying no
// game class and the mixin rule stays intact. The merged archive's digest changes, which is what
// tells the device its cached copy is stale; no cache format bump is needed.
// ------------------------------------------------------------------------------------------------

/** Where the shim's classes land, and the root its entry names are relative to. */
val gameShimClassesDirectory = layout.buildDirectory.dir("gameShim/classes")

val compileGameShim = tasks.register<JavaCompile>("compileGameShim") {
    group = "rwml"
    description = "Compiles the classes the staged game archive lacks or only carries as placeholders."
    source("src/gameShim/java")
    destinationDirectory = gameShimClassesDirectory

    // The android.jar set below is the whole class path; nothing else is on it.
    classpath = files()

    // Java 8: no invokedynamic string concat, which the on-device dexer cannot lower.
    sourceCompatibility = "8"
    targetCompatibility = "8"
}

/** The shim's class files, as a tree rather than a task's outputs: the outputs carry more than
    classes, and a plain directory argument is not walked at all. */
val gameShimClasses = fileTree(gameShimClassesDirectory.get().asFile) {
    include("**/*.class")
    builtBy(compileGameShim)
}

// The shim calls the real platform API, so the module's own android.jar is its whole class path.
// `sdkComponents` is a property in AGP 9, not a configuration block, and the class path stays a
// provider: reading it here would trip AGP's "not yet finalized" guard for the module's own level.
androidComponents {
    compileGameShim.configure {
        options.bootstrapClasspath = files(sdkComponents.bootClasspath)
    }
}

// ------------------------------------------------------------------------------------------------
// Staging: the game archive the app rewrites, plus its digest.
//
// The app cannot patch the APK in place, so it copies this into its own storage first and copies
// again only when the digest changed. Staging it here - rather than leaving it in a source tree - is
// what makes the assets directory a build output, so AGP both consumes it and depends on the task
// that produces it.
//
// This application stages nothing else. The core library is an ordinary dependency of the app (see
// `dependencies`), so it reaches the APK as dex, and no extension ships with the application.
// ------------------------------------------------------------------------------------------------

/** Set by -PrwmlGameJar=<path>, relative to the repository root, to stage another game jar. */
val gameJarOverride = providers.gradleProperty("rwmlGameJar")

/** The remapped game, which is what `:remapFromEnigma` writes. Staged only when it exists. */
val remappedGameJar = rootProject.layout.buildDirectory.file("libs/game-lib-named.jar")

val stageAndroidAssets = tasks.register<StageAndroidAssetsTask>("stageAndroidAssets") {
    group = "rwml"
    description = "Stages the game archive, with its digest, into the app's assets."
    if (gameJarOverride.isPresent) {
        gameJar.from(rootProject.layout.projectDirectory.file(gameJarOverride.get()))
    } else {
        gameJar.from(remappedGameJar)

        // That file is `:remapFromEnigma`'s output, so this task has to wait for that one whenever
        // it is there. The dependency is declared only then. The mapping pipeline needs the game jar
        // and the Anvil submodule, and assembling a debug APK must not require either: on a checkout
        // that has neither, there is nothing to wait for, and the app builds and runs with no game
        // classes to rewrite.
        if (remappedGameJar.get().asFile.isFile) {
            dependsOn(":remapFromEnigma")
        }
    }
    outputDirectory.set(layout.buildDirectory.dir("generated/rwml-assets"))

    // The shim classes are written into the archive as it is staged: replacing the entries it
    // already carries under the same names, adding the ones it references but does not carry.
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
 * Copies the archive the app installs into an assets root it owns outright, merged with the shim.
 *
 * <p>Everything under the output directory is written here, so an archive that is no longer staged
 * is deleted rather than left behind - otherwise it would keep shipping in the APK. The task writes
 * nothing that depends on where it runs, so its result is cacheable.</p>
 */
@CacheableTask
abstract class StageAndroidAssetsTask : DefaultTask() {

    /** The game classes to stage, if any were produced. A file collection, so a missing jar is not
        an error: the app runs without game classes. */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val gameJar: ConfigurableFileCollection

    /** The shim classes, if any were compiled. Written into the staged archive; empty is legal. */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shimClasses: ConfigurableFileCollection

    /** The root `shimClasses` lives under, which is what turns a class file into an entry name. */
    @get:Internal
    abstract val shimRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val assetsRoot = outputDirectory.get().asFile
        val digests = sortedMapOf<String, String>()

        // The root is the task's own: a directory left by an earlier layout is not part of the app
        // any more, and pruning only the staged groups would keep it.
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

        // A group this build no longer writes is not part of the app any more. The copy an earlier
        // build left behind would keep shipping in the APK.
        File(assetsRoot, ASSET_ROOT).listFiles().orEmpty()
            .filter { it.isDirectory && it.name != GAME_GROUP }
            .forEach { stale ->
                stale.deleteRecursively()
                logger.lifecycle("  removed $ASSET_ROOT/${stale.name}  (not staged any more)")
            }

        writeManifest(assetsRoot, digests)
    }

    /**
     * Copies every archive of one group into `<assets>/rwml/<group>`, then deletes whatever else
     * that directory still holds. A file that is not there is skipped: the app is expected to start
     * even when nothing was staged.
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
            stageArchive(archive, stagedFile)
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
     * Writes one staged archive. A plain copy, unless there are shim classes: then the archive's own
     * entries are written first and the shim's after them, and a game entry the shim also names is
     * skipped, which is how a placeholder body is replaced. Every timestamp the shim contributes is
     * fixed, so an unchanged input yields the same archive and the same digest.
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

    /** The shim's class files as jar entry names, relative to [shimRoot]. */
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

    /** Deterministic: no timestamp, so an unchanged input leaves the file alone. */
    private fun writeManifest(assetsRoot: File, digests: Map<String, String>) {
        val text = buildString {
            append("# Written by :android:stageAndroidAssets. Do not edit.\n")
            append("# Consumed by io.github.rwml.android.AssetInstaller.\n")
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
        /** Root of everything staged into `assets/`, as AssetInstaller.ASSET_ROOT has it. */
        const val ASSET_ROOT = "rwml"

        /** The group the app installs the game classes from, as AndroidBootstrap names it. */
        const val GAME_GROUP = "game"

        /** The file AssetInstaller reads the digests from. */
        const val MANIFEST_NAME = "manifest.properties"

        /** Fixed entry time for every shim class, so the merged archive is reproducible. */
        const val SHIM_TIMESTAMP = 946684800000L
    }
}
