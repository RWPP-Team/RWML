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
 * 在某个平台上启动游戏时按顺序执行的步骤。
 *
 * <p>抽象对象是整个步骤序列，而非单个步骤。所有平台上的游戏启动都包含相同的四项操作：创建引擎、加载资源文件、
 * 加载引擎资源、加载模组，并按此顺序执行且为每项指定名称。不同平台的执行者和方式有所不同：Android 上由 RWML
 * 直接调用引擎；桌面端则由游戏启动器管理窗口，并在首帧执行启动工作。因此，平台应为每个单独执行的操作分别发布一个阶段，
 * 并使用下方的名称。如果某个平台的引擎通过一次调用完成多个操作，则只发布一个阶段，并将引擎消息转发为子进度。</p>
 *
 * <p>扩展也可以添加自己的阶段，例如桌面端专属的 libRocket 初始化和文档字体加载。由内置扩展在此提供这些步骤，
 * 核心模块无需加入平台专属依赖。</p>
 *
 * <p>以下两个特性使扩展代码可以安全地启动游戏：</p>
 * <ul>
 *   <li>启动流程只运行一次。再次调用 {@link #start()} 会返回首次运行的报告，而不会重复启动，因此重复打开界面也不会
 *       创建第二个引擎。</li>
 *   <li>失败阶段会记录下来而不会抛出异常。调用方自行决定如何处理未完整启动的结果。</li>
 * </ul>
 */
public final class GameBoot {

    /**
     * 创建引擎并将其设为进程中唯一引擎的阶段。
     *
     * <p>根据游戏自身的启动流程，此调用会完成引擎初始化；桌面启动器的“GameEngine”步骤和 Android 启动流程都会执行此操作。</p>
     */
    public static final String ENGINE = "engine";

    /** 使游戏资源可读取的阶段：加载资源索引和加载器后端。 */
    public static final String ASSETS = "assets";

    /** 加载引擎资源子系统的阶段：设置、图形、音频和单位。 */
    public static final String RESOURCES = "resources";

    /** 加载已安装模组的阶段。 */
    public static final String MODS = "mods";

    /** 游戏启动完成后，平台可选择发布的最后一个阶段。 */
    public static final String READY = "ready";

    private final GameContext context;
    private final List<GameStage> stages = new ArrayList<>();

    /** 在首个阶段运行前设置，避免重入的 {@link #start()} 重复启动游戏。 */
    private boolean running;
    private GameStage preparation;
    private Thread contributionThread;

    private StageReport report;
    private boolean stopOnFailure;

    public GameBoot(GameContext context) {
        if (context == null) {
            throw new IllegalArgumentException("A boot needs a context");
        }
        this.context = context;
    }

    /** 若宿主需要从部分初始化的游戏类中恢复，可设置为在后续阶段运行前停止。 */
    public synchronized void stopOnFailure() {
        if (running || report != null) { throw new IllegalStateException("The boot has already run"); }
        stopOnFailure = true;
    }

    /** @return 每个阶段运行时收到的上下文。 */
    public GameContext context() {
        return context;
    }

    /**
     * 在阶段列表冻结前设置宿主的准备步骤。延迟执行的扩展 onLoad 回调可在准备期间的当前线程中添加或移除普通阶段。
     * 准备步骤失败时，会在任何引擎阶段运行前停止启动流程。
     */
    public synchronized void beforeStages(String name, GameAction action) {
        if (running || report != null) { throw new IllegalStateException("The boot has already run"); }
        if (preparation != null) { throw new IllegalStateException("Preparation is already configured"); }
        if (contains(name)) { throw new IllegalArgumentException("Duplicate stage '" + name + "'"); }
        preparation = new NamedStage(name, action);
    }

    /**
     * 在列表末尾添加一个阶段。
     *
     * @throws IllegalArgumentException 若其他阶段已使用相同名称；否则按名称生成报告时会丢失其中一个阶段
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

    /** 在列表末尾添加一个以 lambda 表达的阶段。 */
    public void add(String name, GameAction action) {
        add(new NamedStage(name, action));
    }

    /**
     * 在列表开头添加一个阶段。
     *
     * <p>用于添加必须在创建引擎前执行的步骤。桌面启动器也会按此顺序处理：在引擎创建前选定图形类、声音工厂和界面。</p>
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

    /** 在列表开头添加一个以 lambda 表达的阶段。 */
    public void addFirst(String name, GameAction action) {
        addFirst(new NamedStage(name, action));
    }

    /**
     * 移除一个阶段，以便覆盖平台默认行为。
     *
     * @return 是否已注册该名称的阶段
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

    /** @return 是否已注册指定名称的阶段。 */
    public synchronized boolean contains(String name) {
        if (preparation != null && preparation.name().equals(name)) { return true; }
        for (GameStage stage : stages) {
            if (stage.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** @return 已注册阶段的名称，顺序与运行顺序一致。 */
    public synchronized List<String> stageNames() {
        List<String> names = new ArrayList<>(stages.size());
        if (preparation != null) { names.add(preparation.name()); }
        for (GameStage stage : stages) {
            names.add(stage.name());
        }
        return Collections.unmodifiableList(names);
    }

    /** @return 是否已调用 {@link #start()}。 */
    public synchronized boolean isStarted() {
        return report != null;
    }

    /**
     * 按顺序运行所有阶段。
     *
     * <p>此流程只运行一次。再次调用会返回首次运行的报告，因此多个位置都可以安全地请求启动：重复打开界面不会创建第二个引擎。</p>
     *
     * @param listener 接收各阶段的进度报告；可以为 {@code null}
     * @return 已运行阶段和失败阶段的报告
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
                if (stopOnFailure) { break; }
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

    /** 运行所有阶段，但不报告进度。 */
    public StageReport start() {
        return start(ProgressListener.NOOP);
    }

    /** @return 阶段列表是否已关闭；启动流程运行后列表即会关闭。 */
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

    /** 使 lambda 可以携带阶段名称的适配器。 */
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
     * 提供给运行中阶段的上下文，会将子进度转发给启动流程的监听器。
     *
     * <p>除 {@link GameContext#subProgress(String)} 外，其余内容均与宿主原有上下文相同。新增的部分是阶段报告细化进度的
     * 通道；它只在启动流程运行期间存在，因为只有此时才有监听器接收消息。</p>
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

        /** 记录即将运行的阶段及其已报告的进度百分比。 */
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
