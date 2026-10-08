/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.asm;

import io.github.rwml.bootstrap.Relauncher;

import kim.der.asm.agent.AsmBootstrap;

import java.lang.instrument.Instrumentation;

/**
 * RWML's own JVM agent entry point.
 *
 * <p>The framework's {@link AsmBootstrap} installs the class-file transformer, but it does not expose
 * the live {@link Instrumentation} instance. RWML needs that instance for one important reason:
 * {@code AsmScanner.scanJar} loads <em>every</em> class in an extension jar (it must, to read the
 * {@code @AsmMixin} annotation reflectively), and that includes the host <em>target</em> classes the
 * mixins rewrite. Because jar entry order is not guaranteed, a target class is frequently loaded by the
 * scanner <em>before</em> its mixin has been registered, so the live transformer sees an empty registry
 * and skips it. The target then stays cached without the mixin.</p>
 *
 * <p>After an archive is scanned and its mixins are registered we call {@link #retransform(Class[])} on
 * those already-loaded target classes. The transformer fires again, the registry is now populated, and
 * the mixin is applied. Retransform is only possible with the {@link Instrumentation} captured here.</p>
 *
 * <p>This class is declared as the jar's {@code Premain-Class}/{@code Agent-Class}; it stores the
 * instrumentation and then delegates transformer installation to the framework.</p>
 */
public final class RwmlAgent {

    private static volatile Instrumentation instrumentation;

    private RwmlAgent() {
    }

    /** JVM {@code -javaagent} entry point. */
    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        AsmBootstrap.premain(agentArgs, inst);
        markInstalled();
    }

    /** JVM runtime {@code VirtualMachine.attach} entry point. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        AsmBootstrap.agentmain(agentArgs, inst);
        markInstalled();
    }

    /**
     * Publishes the fact that the transformer is live.
     *
     * <p>A system property rather than a static field, because the reader is the Java 8 bootstrap,
     * which runs before this class can even be loaded. {@code Bootstrap} uses it to decide whether
     * the game still has to be restarted with {@code -javaagent} - and, because {@code premain} runs
     * before {@code main} starts, the answer is already known by the time the entry point runs.</p>
     */
    private static void markInstalled() {
        System.setProperty(Relauncher.AGENT_INSTALLED_PROPERTY, "true");
    }

    /** @return the live instrumentation, or {@code null} if the agent never started. */
    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    /** Retransforms already-loaded classes so mixins registered after their first load are applied. */
    public static void retransform(Class<?>... classes) throws Exception {
        if (instrumentation != null) {
            instrumentation.retransformClasses(classes);
        }
    }
}
