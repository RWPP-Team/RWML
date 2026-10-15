/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.api.ProgressListener;
import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.game.StageReport;
import io.github.rwml.core.GameContextImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boot is the one place where a platform's steps are ordered, so the properties that carry
 * weight are the order, the tolerance of a step that fails, and that it runs once.
 */
class GameBootTest {

    private static GameBoot boot(Path directory) {
        return new GameBoot(new GameContextImpl(new Object(), new String[0], directory, message -> { }));
    }

    @Test void deferredOnLoadCanContributeStagesBeforeTheSnapshot(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        List<String> order = new ArrayList<>();
        boot.add(GameBoot.ENGINE, context -> {
            order.add("engine");
            assertThrows(IllegalStateException.class, () -> boot.add("too-late", ignored -> { }));
        });
        boot.beforeStages("runtime", context -> {
            order.add("dex-and-extensions");
            boot.addFirst("extension-before-engine", ignored -> order.add("extension"));
            boot.add("ready", ignored -> order.add("ready"));
        });
        StageReport report = boot.start();
        assertTrue(report.isClean());
        assertEquals(List.of("dex-and-extensions", "extension", "engine", "ready"), order);
        assertEquals(List.of("runtime", "extension-before-engine", "engine", "ready"), report.ran());
        assertSame(report, boot.start());
    }
    @Test void failedPreparationPreventsAllEngineStages(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        boot.add(GameBoot.ENGINE, context -> { throw new AssertionError("Engine must not run without dex"); });
        boot.beforeStages("runtime", context -> { throw new IllegalStateException("dex failed"); });
        StageReport report = boot.start();
        assertEquals(java.util.Set.of("runtime"), report.failed().keySet());
        assertTrue(report.ran().isEmpty());
        assertSame(report, boot.start());
    }
    @Test void preparationOnlyAcceptsContributionsFromItsOwnThread(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        java.util.concurrent.atomic.AtomicReference<Throwable> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        boot.beforeStages("runtime", context -> {
            Thread outsider = new Thread(() -> {
                try { boot.add("outside", ignored -> { }); } catch (Throwable failure) { outcome.set(failure); }
            });
            outsider.start(); outsider.join();
        });
        assertTrue(boot.start().isClean());
        assertTrue(outcome.get() instanceof IllegalStateException);
        assertFalse(boot.contains("outside"));
    }

    @Test
    void runsTheStagesInTheOrderTheyWereAdded(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        List<String> order = new ArrayList<>();
        boot.add(GameBoot.ENGINE, context -> order.add("engine"));
        boot.add(GameBoot.MODS, context -> order.add("mods"));
        boot.add("last", context -> order.add("last"));

        StageReport report = boot.start();

        assertEquals(List.of("engine", "mods", "last"), order);
        assertEquals(List.of("engine", "mods", "last"), report.ran());
        assertTrue(report.isClean());
    }

    @Test
    void addFirstPutsAStepBeforeTheEngine(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        List<String> order = new ArrayList<>();
        boot.add(GameBoot.ENGINE, context -> order.add("engine"));
        boot.addFirst("platform", context -> order.add("platform"));

        boot.start();

        assertEquals(List.of("platform", "engine"), order);
    }

    @Test
    void aFailingStageIsRecordedAndTheRestStillRun(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        List<String> order = new ArrayList<>();
        boot.add(GameBoot.ENGINE, context -> {
            throw new IllegalStateException("no engine for you");
        });
        boot.add(GameBoot.MODS, context -> order.add("mods"));

        StageReport report = boot.start();

        assertFalse(report.isClean());
        assertEquals(Collections.singletonList("mods"), order);
        assertEquals(List.of("mods"), report.ran());
        assertTrue(report.failed().get(GameBoot.ENGINE).contains("no engine for you"));
    }

    @Test
    void recoveryHostsStopAfterTheFirstStageFailure(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        boot.stopOnFailure();
        boot.add(GameBoot.ENGINE, context -> { throw new LinkageError("failed mixin linkage"); });
        boot.add(GameBoot.MODS, context -> { throw new AssertionError("Must not prepare mods after an engine failure"); });
        StageReport report = boot.start();
        assertEquals(java.util.Set.of(GameBoot.ENGINE), report.failed().keySet());
        assertTrue(report.ran().isEmpty());
        assertSame(report, boot.start());
    }

    @Test
    void runsOnceSoASecondCallCannotBuildASecondEngine(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        List<String> order = new ArrayList<>();
        boot.add(GameBoot.ENGINE, context -> order.add("engine"));

        StageReport first = boot.start();
        StageReport second = boot.start();

        assertEquals(1, order.size());
        assertSame(first, second);
        assertTrue(boot.isStarted());
    }

    @Test
    void reportsEveryStageByNameAndFinishesAtOneHundredPercent(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        boot.add(GameBoot.ENGINE, context -> { });
        boot.add(GameBoot.READY, context -> { });
        List<String> messages = new ArrayList<>();
        int[] lastPercent = {-1};
        ProgressListener listener = (percent, message) -> {
            messages.add(message);
            lastPercent[0] = percent;
        };

        boot.start(listener);

        assertTrue(messages.contains(GameBoot.ENGINE));
        assertTrue(messages.contains(GameBoot.READY));
        assertEquals(100, lastPercent[0]);
    }

    @Test
    void refusesADuplicateNameAndAStepAddedAfterTheRun(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        boot.add(GameBoot.ENGINE, context -> { });

        assertThrows(IllegalArgumentException.class, () -> boot.add(GameBoot.ENGINE, context -> { }));
        assertTrue(boot.contains(GameBoot.ENGINE));

        boot.remove(GameBoot.ENGINE);
        assertFalse(boot.remove(GameBoot.ENGINE));

        boot.add(GameBoot.ENGINE, context -> { });
        boot.start();
        assertThrows(IllegalStateException.class, () -> boot.add(GameBoot.MODS, context -> { }));
    }

    @Test
    void anEmptyBootStartsCleanly(@TempDir Path directory) {
        GameBoot boot = boot(directory);

        assertTrue(boot.stageNames().isEmpty());
        assertTrue(boot.start().isClean());
    }

    @Test
    void aStageSeesTheContextItWasGiven(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        List<Path> seen = new ArrayList<>();
        boot.add(GameBoot.ASSETS, context -> {
            seen.add(context.directory());
            context.log("loading assets");
        });

        boot.start();

        assertEquals(Collections.singletonList(directory), seen);
    }

    @Test
    void subProgressOfAStageReachesTheListenerUnderTheStageName(@TempDir Path directory) {
        GameBoot boot = boot(directory);
        boot.add(GameBoot.ENGINE, context -> {
            // The engine announces its own steps while the stage runs; the boot routes them to the
            // listener so a loading screen shows the real sequence rather than one line per stage.
            context.subProgress("Asset Index");
            context.subProgress("Units");
        });
        boot.add(GameBoot.MODS, context -> { });
        List<String> lines = new ArrayList<>();

        boot.start((percent, message) -> lines.add(message));

        // The stage announcement comes first, then each sub-progress line named after its stage, and
        // the second stage's announcement is not mistaken for sub-progress of the first.
        assertEquals(List.of(GameBoot.ENGINE,
            GameBoot.ENGINE + ": Asset Index",
            GameBoot.ENGINE + ": Units",
            GameBoot.MODS,
            "2 of 2 stage(s) done"), lines);
    }
}
