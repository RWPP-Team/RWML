/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.asm;

import io.github.rwml.bootstrap.Relauncher;

import java.lang.instrument.Instrumentation;

/**
 * Owns the desktop transformer and instrumentation. A failed transform notifies recovery and prevents
 * the JVM from defining an unpatched class. Registered targets already loaded can be retransformed.
 */
public final class RwmlAgent {

    private static volatile Instrumentation instrumentation;
    private static boolean installed;

    private RwmlAgent() {
    }

    /** JVM {@code -javaagent} entry point. */
    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        install(inst);
        markInstalled();
    }

    /** JVM runtime {@code VirtualMachine.attach} entry point. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        install(inst);
        markInstalled();
    }

    private static synchronized void install(Instrumentation inst) {
        if (!installed) {
            // Preserve the framework's explicit AsmCore.transform API without installing a second transformer.
            new kim.der.asm.agent.AsmBootstrap();
            inst.addTransformer(new FailureAwareTransformer(MixinSupport.instance()::failed), true);
            installed = true;
        }
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
