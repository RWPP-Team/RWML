import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.MappingWriter
import net.fabricmc.mappingio.format.MappingFormat
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils

import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import java.util.jar.Manifest

plugins {
    id("java-library")
    `maven-publish`
    signing
}

group = "io.github.rwpp-team"
version = "1.0.0"
description = "RWML - a hot-reloadable, mixin-capable extension framework for the RTS engine."

buildscript {
    dependencies {
        classpath(libs.fabricmc.tiny.remapper)
        classpath(libs.fabricmc.mapping.io)
    }
}

val bundledRuntime = configurations.create("bundledRuntime") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val bootstrap = sourceSets.create("bootstrap")

tasks.named<JavaCompile>(bootstrap.compileJavaTaskName) {
    options.release.set(8)
    // A hard guarantee, not a convention: no dependency may leak into the Java 8 layer.
    classpath = files()
}

sourceSets["main"].compileClasspath += bootstrap.output
sourceSets["test"].compileClasspath += bootstrap.output
sourceSets["test"].runtimeClasspath += bootstrap.output

dependencies {
    compileOnly(files("ASM-Framework.jar"))
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.org.ow2.asm.asm3)
    compileOnly(libs.org.ow2.asm.asm.tree)
    compileOnly(libs.org.ow2.asm.asm.commons3)
    compileOnly(libs.org.ow2.asm.asm.util)

    bundledRuntime(libs.kotlin.stdlib)
    bundledRuntime(libs.org.ow2.asm.asm3)
    bundledRuntime(libs.org.ow2.asm.asm.tree)
    bundledRuntime(libs.org.ow2.asm.asm.commons3)
    bundledRuntime(libs.org.ow2.asm.asm.util)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Bundle the ASM-Framework classes into the library jar and turn it into a usable Java agent so that
// `-javaagent:rwml.jar` installs the mixin transformer without a separate framework jar.
tasks.named<Jar>("jar") {
    from(bootstrap.output)
    from(zipTree(file("ASM-Framework.jar"))) {
        exclude("META-INF/*.MF", "META-INF/maven/**", "META-INF/*.kotlin_module", "META-INF/versions/**")
    }
    configurations["bundledRuntime"].forEach { jarFile ->
        from(zipTree(jarFile)) {
            exclude("META-INF/*.MF", "META-INF/maven/**", "META-INF/*.kotlin_module", "META-INF/versions/**", "module-info.class")
        }
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "io.github.rwml.asm.RwmlAgent",
            "Agent-Class" to "io.github.rwml.asm.RwmlAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Implementation-Title" to "RWML",
            "Implementation-Version" to version.toString(),
            "Implementation-Vendor" to "io.github.rwpp-team"
        )
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withJavadocJar()
    withSourcesJar()
}

// The Java 8 bootstrap is part of the artifact, so its sources are part of the sources jar too.
tasks.named<Jar>("sourcesJar") {
    from(bootstrap.allSource)
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "io.github.rwpp-team"
            artifactId = "rwml"
            version = project.version.toString()
            pom {
                name.set("RWML")
                description.set(project.description)
                url.set("https://github.com/RWPP-Team/RWML")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("rwpp-team")
                        name.set("RWPP Team")
                        organization.set("RWPP-Team")
                        organizationUrl.set("https://github.com/RWPP-Team")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/RWPP-Team/RWML.git")
                    developerConnection.set("scm:git:ssh://github.com/RWPP-Team/RWML.git")
                    url.set("https://github.com/RWPP-Team/RWML")
                }
            }
        }
    }
    repositories {
        maven {
            name = "localStaging"
            url = uri(layout.buildDirectory.dir("central-staging").get().asFile)
        }
        maven {
            name = "centralPortal"
            val isSnapshot = version.toString().endsWith("SNAPSHOT")
            url = uri(
                if (isSnapshot) "https://central.sonatype.com/repository/maven-snapshots/"
                else "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
            )
            val user = providers.gradleProperty("centralUsername").orNull
            val secret = providers.gradleProperty("centralPassword").orNull
            if (!user.isNullOrBlank() && !secret.isNullOrBlank()) {
                credentials {
                    username = user
                    password = secret
                }
            }
        }
    }
}

signing {
    val inMemoryKey = providers.gradleProperty("signingInMemoryKey").orNull
        ?: System.getenv("GPG_PRIVATE_KEY")
    val inMemoryPassword = providers.gradleProperty("signingInMemoryPassword").orNull
        ?: System.getenv("GPG_PASSPHRASE")
    val gpgKeyId = providers.gradleProperty("signing.keyId").orNull
    val hasKey = !inMemoryKey.isNullOrBlank() || !gpgKeyId.isNullOrBlank()

    isRequired = hasKey && !version.toString().endsWith("SNAPSHOT")
    when {
        !inMemoryKey.isNullOrBlank() -> {
            useInMemoryPgpKeys(inMemoryKey, inMemoryPassword)
            sign(publishing.publications["maven"])
        }
        !gpgKeyId.isNullOrBlank() -> {
            useGpgCmd()
            sign(publishing.publications["maven"])
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Maven Central: package the local staging repository into the ZIP bundle the Central Portal expects,
// so the release can also be completed by hand (upload at https://central.sonatype.com/publishing).
// ------------------------------------------------------------------------------------------------
tasks.register<Zip>("bundleForCentral") {
    group = "publishing"
    description = "Builds the Maven Central bundle ZIP from build/central-staging."
    dependsOn("publishAllPublicationsToLocalStagingRepository")
    from(layout.buildDirectory.dir("central-staging"))
    archiveFileName.set("rwml-${version}-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
}

// ------------------------------------------------------------------------------------------------
// packageGameJar: build the drop-in replacement for the game's game-lib.jar.
//
// The game's launcher starts the JVM on the jar's `Main-Class`, fixed to
// com.corrodinggames.rts.java.Main, so the replacement jar has to provide exactly that class. RWML
// writes it in `src/bootstrap/java`. The game's own entry point carries the name `MainLauncher`,
// assigned to it by the Enigma mappings (`:remapFromEnigma`), so the two halves only have to be
// merged - RWML's entries winning on a name collision - and nothing is rewritten.
//
// The result is NOT publishable: it contains the game's proprietary code. Only the plain `rwml`
// artifact (the `jar` task, and therefore the Maven Central publication) is distributed.
// ------------------------------------------------------------------------------------------------
/** Set by -PrwmlGameJar=<path> to merge a jar other than the one `:remapFromEnigma` produces. */
val gameJarOverride = providers.gradleProperty("rwmlGameJar")

val packageGameJar = tasks.register<PackageGameJarTask>("packageGameJar") {
    group = "distribution"
    description = "Merges the remapped game into RWML's jar and writes the drop-in game-lib.jar " +
        "replacement to build/dist."

    rwmlJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    // `remapFromEnigma` is registered further down the script; naming it here keeps this
    // registration next to the task class it configures.
    gameJar.set(
        gameJarOverride
            .map { layout.projectDirectory.file(it) }
            .orElse(tasks.named<RemapFromEnigmaTask>("remapFromEnigma").flatMap { it.outputJar })
    )
    targetJavaVersion.set(providers.gradleProperty("rwmlTargetJavaVersion").getOrElse("25"))
    outputJar.set(layout.buildDirectory.file("dist/game-lib.jar"))
    if (!gameJarOverride.isPresent) {
        dependsOn("remapFromEnigma")
    }
}

/**
 * Merges the game into RWML's jar.
 *
 * <p>RWML's entries are copied first, so the framework wins wherever both jars define the same path;
 * the game's entries follow. No bytecode is rewritten: RWML's {@code com.corrodinggames.rts.java.Main}
 * comes from its own sources, and the game's original entry point is already called
 * {@code MainLauncher} by the time the jar reaches this task.</p>
 */
abstract class PackageGameJarTask : DefaultTask() {

    /** RWML's own jar: the library, the agent and the Java 8 bootstrap. */
    @get:InputFile
    abstract val rwmlJar: RegularFileProperty

    /** The game jar to inline. Its entry point must already be named {@code MainLauncher}. */
    @get:InputFile
    abstract val gameJar: RegularFileProperty

    /** JVM feature version the bootstrap is told to run the game on. */
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
                        logger.debug("Skipping duplicate entry ${entry.name} (from $label)")
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
                // The manifest has to be the first entry for strict JarInputStream readers.
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
            "  Main-Class: $GAME_ENTRY_POINT (RWML bootstrap, Java ${targetJavaVersion.get().trim()})"
        )
        logger.lifecycle("  handed over to: $GAME_LAUNCHER")
    }

    /**
     * Fails the build when the merged jar could not start.
     *
     * <p>The two halves are merged by path, so the one thing worth asserting is that both entry
     * points ended up in the result: RWML's own {@code Main}, which the launcher starts, and the
     * game's {@code MainLauncher}, which the bootstrap hands over to. A missing one shows up as a
     * game that refuses to start, which is far harder to read out of a game log than out of a build
     * failure.</p>
     */
    private fun verifyOutput(output: File) {
        // Jar entries are path-shaped: the constants are dotted class names.
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

    /**
     * Signature files are dropped because the archive is reassembled, and multi-release variants
     * never appear here: RWML's own jar excludes them and the game is Java 8.
     */
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

    /** Every parent directory of every entry, shallowest first, for tools that expect them. */
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

    /**
     * Builds the merged manifest.
     *
     * <p>Starts from the game's own manifest so anything its launcher reads survives, then takes over
     * the attributes RWML has to control: the entry point, and the agent hooks that let
     * {@code -javaagent:<this jar>} install the bytecode transformer before the game's classes
     * load.</p>
     */
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

    /** The naming contract the Java 8 bootstrap reads at runtime. */
    private fun bootstrapProperties() = buildString {
        append("# Written by the :packageGameJar Gradle task. Do not edit.\n")
        append("# Consumed by io.github.rwml.bootstrap.GameEntry.\n")
        append("targetJavaVersion=").append(targetJavaVersion.get().trim()).append('\n')
    }

    private companion object {
        /**
         * Mirrors `io.github.rwml.bootstrap.GameEntry#MAIN`.
         *
         * Duplicated as a literal on purpose: `GameEntry` lives in the `bootstrap` source set, which
         * is compiled *by* this build, so it is not on the build script's own class path.
         */
        const val GAME_ENTRY_POINT = "com.corrodinggames.rts.java.Main"

        /** Mirrors `io.github.rwml.bootstrap.GameEntry#GAME_MAIN`. */
        const val GAME_LAUNCHER = "com.corrodinggames.rts.java.MainLauncher"

        const val CLASS_SUFFIX = ".class"
        const val BOOTSTRAP_PROPERTIES = "META-INF/rwml-bootstrap.properties"
    }
}

// ------------------------------------------------------------------------------------------------
// Local-only helper: remap the host game library from Enigma mappings. Not part of the published
// library (the game lib is never a dependency of RWML itself).
//
// An Enigma `CLASS` line renames exactly one class file. Anonymous, local and nested classes are
// separate files, so a class nested in a renamed class would keep its obfuscated package while the
// outer class moves, and every package-private access across the new package boundary fails at
// runtime with `IllegalAccessError`. Enigma cannot address a `$` name reliably, so the missing
// entries are derived from the outer class instead of being written by hand: `X$n` becomes
// `dst(X)_n`, which places the nested class in the package its renamed outer class moves to.
// ------------------------------------------------------------------------------------------------
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

    /** The nested-class entries this task derived, one `source -> target` pair per line. */
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

        val derived = addNestedClassMappings(tree, input, to)
        logger.lifecycle(
            "Derived ${derived.size} nested-class mappings from ${tree.classes.size} mapped classes."
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

        logger.lifecycle("Remapping complete: $input -> $output ($from -> $to)")
    }

    /** The names of the classes the jar holds, without the `.class` suffix. */
    private fun jarClassNames(jar: Path): List<String> =
        ZipFile(jar.toFile()).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") && !it.startsWith("META-INF/versions/") }
                .map { it.removeSuffix(".class") }
                .sorted()
                .toList()
        }

    /**
     * Adds one entry per nested class of a renamed class and returns them as `source -> target`
     * pairs.
     *
     * The separator is replaced because Enigma cannot address a `$` name reliably, and the outer
     * class's target package is what keeps the nested class in the same package as its renamed outer
     * class. A class whose outermost class carries no `CLASS` line keeps its name, and so does a
     * class that already has an entry of its own.
     */
    private fun addNestedClassMappings(
        tree: MemoryMappingTree,
        jar: Path,
        toNamespace: String,
    ): List<String> {
        val dstNs = tree.getNamespaceId(toNamespace)
        check(dstNs >= 0) {
            "The mapping namespaces are ${tree.srcNamespace} -> ${tree.dstNamespaces}, which does " +
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
            val target = outerTarget + "_" + name.substring(separator + 1).replace('$', '_')
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
                derived.add("$name -> $target")
            }
        }
        return derived
    }
}
