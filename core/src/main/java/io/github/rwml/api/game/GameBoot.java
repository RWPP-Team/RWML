/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game;

import io.github.rwml.api.ProgressListener;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ordered sequence of steps that brings the game up on one platform.
 *
 * <p>The sequence is the abstraction; the steps are not. Starting the game means the same four
 * things on every platform - create the engine, load its assets, load its resources, load its mods -
 * and the game performs them in that order with a name set on each. What differs is who performs
 * them and how: on Android RWML calls the engine itself, on the desktop the game's own launcher owns
 * the window and does the work on its first frame. A platform therefore publishes one stage per
 * operation it performs separately, using the names below, and a platform whose engine performs them
 * in one call publishes one stage and forwards the engine's own messages as sub-progress.</p>
 *
 * <p>An extension adds stages of its own, which is what the desktop-specific loading does: the
 * libRocket setup and the document fonts belong to the desktop, so a bundled extension contributes
 * them here rather than the core growing a platform-shaped hole.</p>
 *
 * <p>Two properties make the boot safe to run from extension code:</p>
 * <ul>
 *   <li>it runs once. A second {@link #start()} returns the first report instead of booting
 *       twice, so an interface that is opened twice cannot create a second engine.</li>
 *   <li>a failing stage is recorded, not thrown. The caller decides what a partial boot is worth.</li>
 * </ul>
 */
public final class GameBoot {

    /**
     * The stage that creates the engine and makes it the process's one engine.
     *
     * <p>On the game's own terms this is the call that ends with an initialised engine, which is
     * what the desktop launcher's "GameEngine" step and the Android path both do.</p>
     */
    public static final String ENGINE = "engine";

    /** The stage that makes the game's assets readable: the asset index and the loader backends. */
    public static final String ASSETS = "assets";

    /** The stage that loads the engine's resource subsystems: settings, graphics, audio, units. */
    public static final String RESOURCES = "resources";

    /** The stage that loads the installed mods. */
    public static final String MODS = "mods";

    /** The stage a platform may publish last, once the game is up. */
    public static final String READY = "ready";

    private final GameContext context;
    private final List<GameStage> stages = new ArrayList<>();

    /** Set before the first stage runs, so a re-entrant {@link #start()} cannot boot twice. */
    private boolean running;
    private GameStage preparation;
    private Thread contributionThread;

    private StageReport report;

    public GameBoot(GameContext context) {
        if (context == null) {
            throw new IllegalArgumentException("A boot needs a context");
        }
        this.context = context;
    }

    /** @return what every stage is handed while it runs. */
    public GameContext context() {
        return context;
    }

    /**
     * Sets the host's preparation before the stage list is frozen. Deferred extension onLoad
     * callbacks may add/remove ordinary stages on this calling thread during preparation.
     * A preparation failure stops the boot before any engine stage executes.
     */
    public synchronized void beforeStages(String name, GameAction action) {
        if (running || report != null) { throw new IllegalStateException("The boot has already run"); }
        if (preparation != null) { throw new IllegalStateException("Preparation is already configured"); }
        if (contains(name)) { throw new IllegalArgumentException("Duplicate stage '" + name + "'"); }
        preparation = new NamedStage(name, action);
    }

    /**
     * Appends a stage.
     *
     * @throws IllegalArgumentException when another stage already carries the same name, because a
     *     report keyed by name would otherwise lose one of them
     */
    public synchronized void add(GameStage stage) {
        if (stage == null) {
            throw new IllegalArgumentException("The stage is null");
        }
        String name = stage.name();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("A stage needs a name");
        }
        if (booted()) {
            throw new IllegalStateException("The boot has already run");
        }
        if (contains(name)) {
            throw new IllegalArgumentException("A stage named '" + name + "' is already registered");
        }
        stages.add(stage);
    }

    /** Appends a stage written as a lambda. */
    public void add(String name, GameAction action) {
        add(new NamedStage(name, action));
    }

    /**
     * Prepends a stage.
     *
     * <p>Used for the steps that have to happen before the engine is created. The desktop launcher
     * orders them that way itself: the graphics class, the sound factory and the interface are all
     * chosen before the engine exists.</p>
     */
    public synchronized void addFirst(GameStage stage) {
        if (stage == null) {
            throw new IllegalArgumentException("The stage is null");
        }
        String name = stage.name();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("A stage needs a name");
        }
        if (booted()) {
            throw new IllegalStateException("The boot has already run");
        }
        if (contains(name)) {
            throw new IllegalArgumentException("A stage named '" + name + "' is already registered");
        }
        stages.add(0, stage);
    }

    /** Prepends a stage written as a lambda. */
    public void addFirst(String name, GameAction action) {
        addFirst(new NamedStage(name, action));
    }

    /**
     * Removes a stage, so a platform can be overridden.
     *
     * @return whether a stage of that name was registered
     */
    public synchronized boolean remove(String name) {
        if (booted()) {
            throw new IllegalStateException("The boot has already run");
        }
        if (preparation != null && preparation.name().equals(name)) {
            preparation = null;
            return true;
        }
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).name().equals(name)) {
                stages.remove(i);
                return true;
            }
        }
        return false;
    }

    /** @return whether a stage of that name is registered. */
    public synchronized boolean contains(String name) {
        if (preparation != null && preparation.name().equals(name)) { return true; }
        for (GameStage stage : stages) {
            if (stage.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** @return the names of the registered stages, in the order they will run. */
    public synchronized List<String> stageNames() {
        List<String> names = new ArrayList<>(stages.size());
        if (preparation != null) { names.add(preparation.name()); }
        for (GameStage stage : stages) {
            names.add(stage.name());
        }
        return Collections.unmodifiableList(names);
    }

    /** @return whether {@link #start()} has run. */
    public synchronized boolean isStarted() {
        return report != null;
    }

    /**
     * Runs every stage in order.
     *
     * <p>Runs once. The second call returns the first report, which is what makes the boot safe to
     * ask for from more than one place: an interface started twice must not create a second
     * engine.</p>
     *
     * @param listener receives one report per stage; may be {@code null}
     * @return what ran and what failed
     */
    public StageReport start(ProgressListener listener) {
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        List<GameStage> snapshot;
        GameStage prepare;
        synchronized (this) {
            if (report != null) {
                return report;
            }
            if (running) {
                return new StageReport(Collections.<String>emptyList(),
                    Collections.singletonMap("*", "the boot is already running"));
            }
            running = true;
            prepare = preparation;
            contributionThread = prepare == null ? null : Thread.currentThread();
        }

        List<String> ran = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        ReportingContext reporting = new ReportingContext(context, progress);
        if (prepare != null) {
            progress.onProgress(1, prepare.name());
            reporting.beginStage(1, prepare.name());
            try {
                prepare.run(reporting);
                ran.add(prepare.name());
            } catch (Throwable failure) {
                failed.put(prepare.name(), describe(failure));
            } finally {
                synchronized (this) { contributionThread = null; }
            }
        }
        synchronized (this) {
            snapshot = failed.isEmpty() ? new ArrayList<>(stages) : Collections.emptyList();
        }
        int total = Math.max(1, snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            GameStage stage = snapshot.get(i);
            int begin = prepare == null ? 1 : 35;
            int percent = begin + (int) ((99.0 - begin) * i / total);
            progress.onProgress(percent, stage.name());
            reporting.beginStage(percent, stage.name());
            try {
                stage.run(reporting);
                ran.add(stage.name());
            } catch (Throwable t) {
                String reason = describe(t);
                failed.put(stage.name(), reason);
            }
        }
        int stageCount;
        synchronized (this) { stageCount = stages.size() + (prepare == null ? 0 : 1); }
        progress.onProgress(100, ran.size() + " of " + stageCount + " stage(s) done");

        StageReport result = new StageReport(ran, failed);
        synchronized (this) {
            report = result;
            running = false;
        }
        return result;
    }

    /** Runs every stage with no progress reporting. */
    public StageReport start() {
        return start(ProgressListener.NOOP);
    }

    /** @return whether the stage list is closed, which a running boot has already made it. */
    private boolean booted() {
        return report != null || (running && Thread.currentThread() != contributionThread);
    }

    private static String describe(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        return current.getClass().getSimpleName() + stringWriter;
    }

    /** The adapter that lets a lambda carry a stage name. */
    private static final class NamedStage implements GameStage {

        private final String name;
        private final GameAction action;

        NamedStage(String name, GameAction action) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("A stage needs a name");
            }
            if (action == null) {
                throw new IllegalArgumentException("The action of '" + name + "' is null");
            }
            this.name = name;
            this.action = action;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void run(GameContext context) throws Exception {
            action.run(context);
        }
    }

    /**
     * The context a running stage is handed, which routes sub-progress to the boot's listener.
     *
     * <p>Everything but {@link GameContext#subProgress(String)} is the host's context as it was;
     * the one addition is the channel the stage reports finer lines through, and it only exists
     * while the boot is running, because that is the only time there is a listener to hear it.</p>
     */
    private static final class ReportingContext implements GameContext {

        private final GameContext delegate;
        private final ProgressListener progress;
        private volatile String stage = "";
        private volatile int percent;

        ReportingContext(GameContext delegate, ProgressListener progress) {
            this.delegate = delegate;
            this.progress = progress;
        }

        /** Notes which stage is about to run and at what percent it was announced. */
        void beginStage(int percent, String name) {
            this.percent = percent;
            this.stage = name;
        }

        @Override
        public Object platform() {
            return delegate.platform();
        }

        @Override
        public List<String> arguments() {
            return delegate.arguments();
        }

        @Override
        public Path directory() {
            return delegate.directory();
        }

        @Override
        public void log(String message) {
            delegate.log(message);
        }

        @Override
        public void subProgress(String message) {
            String name = stage;
            String line = name.isEmpty() || name.equals(message) ? message : name + ": " + message;
            progress.onProgress(percent, line);
        }
    }
}
