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
version = "1.0.0"
description = "RWML - a mixin loader framework for the Rusted Warfare."

buildscript {
    // The mapping pipeline is a build-time library, so it lives on this script's own classpath
    // rather than in a module: nothing at run time needs TinyRemapper.
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

val remapFromEnigma = tasks.register<RemapFromEnigmaTask>("remapFromEnigma") {
    group = "jar mapping"

    enigmaDir.set(layout.projectDirectory.dir("work/mappings"))
    inputJar.set(layout.projectDirectory.file("work/game-lib.jar"))
    fromNamespace.set("source")
    toNamespace.set("target")
    outputJar.set(layout.buildDirectory.file("libs/game-lib-named.jar"))
    tinyCache.set(layout.buildDirectory.file("temp/mappings-converted.tiny"))
    derivedReport.set(layout.buildDirectory.file("reports/mappings/derived-nested-classes.txt"))
}

// ------------------------------------------------------------------------------------------------
// genSources: the game's own source, for the interface to open.
//
// The modules compile against the remapped game and never run it, so what they need from it at build
// time is its names. Reading those names back - following a call into the engine, seeing what a field
// holds - is what an interface cannot do without source, and decompiling by hand for every question
// is what this replaces: the game is decompiled once into a sources jar beside the remapped one, and
// both are published into the build's own Maven repository. The modules then depend on the game by
// coordinate, and the `-sources` classifier is reached the way it is for every other dependency.
//
// The decompiler is a build-time tool, so it lives on a configuration of this script rather than in
// a module: nothing at run time decompiles anything.
// ------------------------------------------------------------------------------------------------

/** The decompiler, which is resolved from Maven Central when the task that runs it does. */
val vineflower = configurations.create("vineflower")
dependencies.add(vineflower.name, libs.vineflower)

/** The game's coordinate: what the modules ask for, and what this script publishes. */
val gameLibCoordinate = libs.game.lib.map { it.toString() }
val gameLibVersion = libs.versions.game.lib.get()

/** Where the game's jar and its sources are published, and so where the modules read them from. */
val localGameRepository = layout.buildDirectory.dir("local-maven")

val genSources = tasks.register<DecompileGameSourcesTask>("genSources") {
    group = "jar mapping"
    description = "Decompiles the remapped game into a sources jar, so the interface can open it."

    gameJar.set(remapFromEnigma.flatMap { it.outputJar })
    outputJar.set(layout.buildDirectory.file("libs/game-lib-named-sources.jar"))
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

// Maven library dependencies expose sources through their standard classifier.
subprojects {
    pluginManager.withPlugin("java") {
        apply(plugin = "idea")
        extensions.configure<org.gradle.plugins.ide.idea.model.IdeaModel> {
            module.isDownloadSources = true
        }
        tasks.named("ideaModule") {
            if (rootProject.file("work/game-lib.jar").isFile) {
                dependsOn(publishGameLibForIde)
            }
        }
    }
}

tasks.named("ideaProject") {
    if (file("work/game-lib.jar").isFile) {
        dependsOn(publishGameLibForIde)
    }
}

/**
 * The platform's own API, which is what the decompiler resolves the game's Android-facing names
 * against. The SDK is found where the Android module finds it: the local `sdk.dir`, then the two
 * environment variables, then the newest platform the SDK carries. A build without an SDK still
 * decompiles - the game's own half does not name a platform class - so a missing platform is not
 * a failure.
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
 * Decompiles a jar into a sources jar.
 *
 * The decompiler writes Java files into a directory, which are then packed the way a sources jar
 * packs them, so the archive holds one file per class at the path its package names.
 */
@CacheableTask
abstract class DecompileGameSourcesTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val gameJar: RegularFileProperty

    /** The platform's classes, when the SDK supplies them; the game's own are in [gameJar]. */
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
        val sources = File(temporaryDir, "sources")
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
                "-dgs=1", // keep generic signatures
                "-din=1", // decompile inner classes rather than leaving them as fields
                "-rsy=1", // hide synthetic members, which no reader needs
                "-hdc=0", // keep the implicit super constructor
                "-jrt=1", // read the JDK's own classes from the running runtime
                "-log=WARN"
            )
            classpath.forEach { args("-e=${it.absolutePath}") }
            args(game.absolutePath, sources.absolutePath)
        }

        val written = sources.walkTopDown().filter { it.isFile && it.extension == "java" }
            .sortedBy { it.relativeTo(sources).invariantSeparatorsPath }.toList()
        check(written.isNotEmpty()) { "The decompiler wrote no source for ${game.name}." }

        output.parentFile.mkdirs()
        BufferedOutputStream(FileOutputStream(output)).use { stream ->
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
        logger.lifecycle(
            "Decompiled ${written.size} source files into ${output.name} (${output.length() / 1024} KiB)"
        )
    }

    private companion object {
        const val DECOMPILER = "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler"
    }
}

/**
 * Publishes the game and its sources into a Maven repository inside this build's output.
 *
 * The layout is the one Maven defines: `group/artifact/version`, the artifact itself, the sources
 * under the `-sources` classifier, and a POM that says what they are. Writing the four files is
 * cheaper than a publishing plugin and is what the modules resolve against.
 */
abstract class PublishGameLibForIdeTask : DefaultTask() {

    @get:InputFile
    abstract val gameJar: RegularFileProperty

    @get:InputFile
    abstract val sourcesJar: RegularFileProperty

    @get:Input
    abstract val coordinate: Property<String>

    @get:OutputDirectory
    abstract val repository: DirectoryProperty

    @TaskAction
    fun publish() {
        val parts = coordinate.get().split(":")
        check(parts.size == 3) { "The coordinate ${coordinate.get()} is not group:artifact:version." }
        val (group, artifact, version) = parts

        val directory = repository.get()
            .dir(group.replace('.', '/') + '/' + artifact + '/' + version).asFile
        directory.mkdirs()

        val name = "$artifact-$version"
        gameJar.get().asFile.copyTo(File(directory, "$name.jar"), overwrite = true)
        sourcesJar.get().asFile.copyTo(File(directory, "$name-sources.jar"), overwrite = true)
        File(directory, "$name.pom").writeText(pom(group, artifact, version), Charsets.UTF_8)

        logger.lifecycle("Published $group:$artifact:$version to ${repository.get().asFile}")
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
// The modules
//
// :core is the platform-neutral extension system, :desktop is the JVM host that turns it into the
// game's drop-in game-lib.jar, and :android is the app that does the same work on the device. This
// project builds no code of its own: it owns the mapping pipeline, which is what turns the
// obfuscated game into the names every module compiles against, and the packaging of the desktop
// artifact.
// ------------------------------------------------------------------------------------------------

// ------------------------------------------------------------------------------------------------
// Maven Central bundle ZIP task
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
// packageGameJar: build the drop-in replacement for the game's game-lib.jar.
// ------------------------------------------------------------------------------------------------
val gameJarOverride = providers.gradleProperty("rwmlGameJar")

val packageGameJar = tasks.register<PackageGameJarTask>("packageGameJar") {
    group = "distribution"
    description = "Merges the remapped game into RWML's jar and writes the drop-in game-lib.jar " +
            "replacement to build/dist."

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
 * Merges the game into RWML's jar.
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
        val output = outputJar.get().asFile.toPath()
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

        val remapper: TinyRemapper = TinyRemapper.newRemapper()
            .withMappings(
                TinyUtils.createTinyMappingProvider(
                    tinyFile.toPath(),
                    from,
                    to
                )
            )
            .threads(Runtime.getRuntime().availableProcessors())
            .build()

        // The consumer appends into an existing archive, so an old output would keep every class
        // name the mapping no longer produces.
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

        logger.lifecycle("Remapping complete: $input ->$output ($from ->$to)")
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
     * The name of a nested class relative to its outer class. Anonymous classes keep the numeric
     * `$1` suffix, named ones are appended with their first letter capitalised and no separator.
     */
    private fun nestedTarget(outerTarget: String, suffix: String): String =
        outerTarget + suffix.split('$').joinToString("") { segment ->
            if (segment.toIntOrNull() != null) "$" + segment
            else segment.substring(0, 1).uppercase() + segment.substring(1)
        }

    /**
     * The Enigma reader joins a nested class' target onto its outer target with `$`. Rewrite those
     * named targets to [nestedTarget] form. Anonymous targets and untouched third-party classes are
     * left alone.
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
}

// The decompiled sources have no original API documentation. Central permits a
// documentation placeholder; direct IDE users to the matching sources instead.
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
