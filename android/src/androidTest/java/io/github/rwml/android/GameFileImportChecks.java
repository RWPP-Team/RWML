package io.github.rwml.android;

import android.content.Context;
import io.github.rwml.android.builtin.ImportFixtureProvider;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;

/** Verifies Android game-file routing using only temporary app-cache directories. */
public final class GameFileImportChecks {
    private GameFileImportChecks() {}

    public static void verify(Context context, android.os.Bundle result) throws Exception {
        File cache = context.getCacheDir().getCanonicalFile();
        File root = new File(cache, "rwml-import-check-" + UUID.randomUUID()).getCanonicalFile();
        File fallbackRoot = new File(cache, "rwml-import-fallback-" + UUID.randomUUID()).getCanonicalFile();
        File conflictRoot = new File(cache, "rwml-import-conflict-" + UUID.randomUUID()).getCanonicalFile();
        File corruptRoot = new File(cache, "rwml-import-corrupt-" + UUID.randomUUID()).getCanonicalFile();
        if (!root.toPath().startsWith(cache.toPath()) || !fallbackRoot.toPath().startsWith(cache.toPath())
            || !conflictRoot.toPath().startsWith(cache.toPath())
            || !corruptRoot.toPath().startsWith(cache.toPath())) throw new AssertionError("Unsafe test root");
        ArchiveImports.Request pending = null;
        try {
            if (!"rustedWarfare/units".equals(GameFileImports.relativeDirectory(ArchiveImports.Kind.MOD)))
                throw new AssertionError("The native Android mod path changed");
            if (!"rustedWarfare/rusted_warfare_maps".equals(GameFileImports.relativeDirectory(ArchiveImports.Kind.MAP)))
                throw new AssertionError("The native Android custom-map path changed");

            pending = ArchiveImports.stage(context, ImportFixtureProvider.MOD_URI);
            File mod = GameFileImports.importFileInRoot(context, root, pending);
            pending = null;
            assertBytes(mod, "test rwmod payload");

            // Reopening identical content is an idempotent import, not a second numbered copy.
            pending = ArchiveImports.stage(context, ImportFixtureProvider.MOD_URI);
            File duplicate = GameFileImports.importFileInRoot(context, root, pending);
            pending = null;
            if (!mod.equals(duplicate) || countEntries(mod.getParentFile()) != 1)
                throw new AssertionError("Identical mod content was duplicated");

            // Force the shared-storage fallback even on test filesystems that support hard links.
            pending = ArchiveImports.stage(context, ImportFixtureProvider.MAP_URI);
            File fallbackMap = GameFileImports.importFileInRoot(context, fallbackRoot, pending, true);
            pending = null;
            assertBytes(fallbackMap, "<map/>");
            if (countEntries(fallbackMap.getParentFile()) != 1)
                throw new AssertionError("CREATE_NEW fallback left a temporary or partial file");

            File collisionDir = new File(conflictRoot, GameFileImports.relativeDirectory(ArchiveImports.Kind.MOD));
            Files.createDirectories(collisionDir.toPath());
            File owned = new File(collisionDir, "provider-import.rwmod");
            Files.write(owned.toPath(), "user-owned content".getBytes(StandardCharsets.UTF_8));
            pending = ArchiveImports.stage(context, ImportFixtureProvider.MOD_URI);
            File numbered = GameFileImports.importFileInRoot(context, conflictRoot, pending, true);
            pending = null;
            assertBytes(owned, "user-owned content");
            if (!"provider-import (1).rwmod".equals(numbered.getName()))
                throw new AssertionError("Different content overwrote a user's same-name mod");

            pending = ArchiveImports.stage(context, ImportFixtureProvider.MAP_URI);
            File map = GameFileImports.importFileInRoot(context, root, pending);
            pending = null;
            if (!map.toPath().startsWith(new File(root, GameFileImports.relativeDirectory(ArchiveImports.Kind.MAP)).toPath()))
                throw new AssertionError("The TMX did not use the native custom-map directory");
            assertBytes(map, "<map/>");

            pending = ArchiveImports.stage(context, ImportFixtureProvider.MAP_URI);
            Files.write(pending.file.toPath(), "corrupt snapshot".getBytes(StandardCharsets.UTF_8));
            boolean rejected = false;
            try { GameFileImports.importFileInRoot(context, corruptRoot, pending); }
            catch (IOException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("A snapshot with the wrong hash was installed");
            File corruptDirectory = new File(corruptRoot, GameFileImports.relativeDirectory(ArchiveImports.Kind.MAP));
            if (corruptDirectory.exists() && countEntries(corruptDirectory) != 0)
                throw new AssertionError("A failed copy left a partial map behind");
            ArchiveImports.discard(context, pending);
            pending = null;

            File failureDirectory = new File(corruptRoot, GameFileImports.relativeDirectory(ArchiveImports.Kind.MOD));
            Files.createDirectories(failureDirectory.toPath());
            File failedTarget = new File(failureDirectory, "failed-copy.rwmod");
            boolean copyFailed = false;
            try { GameFileImports.copyExclusive(new File(failureDirectory, "missing-source.bin"),
                    failedTarget.toPath(), "0".repeat(64)); }
            catch (IOException expected) { copyFailed = true; }
            if (!copyFailed || failedTarget.exists())
                throw new AssertionError("CREATE_NEW failure left its owned incomplete destination behind");

            result.putString("gameFileImport",
                "ANDROID_GAME_FILE_IMPORT_PASS: native paths, hard-link commit and CREATE_NEW fallback, SHA-256 verification, duplicate dedupe, collision naming and failed-copy cleanup\n");
        } finally {
            if (pending != null) ArchiveImports.discard(context, pending);
            deleteTree(root);
            deleteTree(fallbackRoot);
            deleteTree(conflictRoot);
            deleteTree(corruptRoot);
        }
    }

    private static void assertBytes(File file, String expected) throws IOException {
        String actual = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (!expected.equals(actual)) throw new AssertionError("Imported bytes changed at " + file);
    }

    private static long countEntries(File directory) throws IOException {
        try (java.util.stream.Stream<java.nio.file.Path> entries = Files.list(directory.toPath())) {
            return entries.count();
        }
    }

    private static void deleteTree(File root) throws IOException {
        if (!root.exists()) return;
        try (java.util.stream.Stream<java.nio.file.Path> entries = Files.walk(root.toPath())) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
    }
}
