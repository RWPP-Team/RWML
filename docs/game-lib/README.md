# Rusted Warfare mapped game library

`io.github.rwpp-team:game-lib` provides Rusted Warfare 1.15 game classes with [Anvil mappings](https://github.com/RWPP-Team/Anvil). Its mapping revision is versioned independently of the game version.

Original API documentation is unavailable. Attach the `-sources.jar` matching your selected `game-lib` dependency in your IDE to browse the complete Vineflower-decompiled sources using the same mapped names.

Use `compileOnly` when developing RWML extensions; the game supplies these classes at runtime.

## IDEA source navigation

IDEA searches attached source roots using the class package and its `SourceFile` attribute.
The original archive stores the literal `SourceFile` for many classes. Remapping names and publishing
a sources jar alone therefore cannot restore navigation: IDEA looks for `android/graphics/SourceFile`
instead of `android/graphics/Paint.java`.
See [IDEA's source lookup implementation](https://github.com/JetBrains/intellij-community/blob/master/java/java-impl/src/com/intellij/psi/impl/JavaPsiImplementationHelperImpl.java).

The remap task now rebuilds source filenames, including the actual outer compilation unit for restored
members with flattened binary names (`RootTableData` lives in `Root.java`). All RWML modules use the
Maven `game-lib` dependency so Gradle can attach its matching `-sources.jar`. A raw `files(...)`
dependency has no source classifier or module identity; it must not be used for the IDE dependency.

Generated binary/source jars live under `build/mapped-game/<input-and-implementation-hash>/`.
The published pair lives under an immutable `build/local-maven/revisions/<hash>/` repository selected
by `current-repository.txt`. Older artifacts remain available to open IDEs and Gradle daemons.

After changing mappings, run these as **separate Gradle invocations**:

```powershell
# E:/RWML: generate and publish the matching binary and sources.
.\gradlew.bat publishGameLibForIde

# Start a new invocation for compilation, or reload all Gradle projects in IDEA.
.\gradlew.bat :api:compileKotlinJvm
```

Settings reads the repository pointer when each invocation starts. Publishing and compiling in a single
invocation can leave dependency resolution pointing at the previous revision. RWPP/AOI-Veil must also
reload Gradle after publishing; an already-open class/decompiler tab may need to be reopened.

Do not overwrite IDEA's external-system caches or delete older jars to force navigation.

License: [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/).
