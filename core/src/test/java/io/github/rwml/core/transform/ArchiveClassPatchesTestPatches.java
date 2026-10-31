/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core.transform;

import io.github.rwml.api.ClassFilePatch;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Public patch fixtures loaded reflectively by {@link ArchiveClassPatchesTest}. */
public final class ArchiveClassPatchesTestPatches {
    private ArchiveClassPatchesTestPatches() { }

    public abstract static class AppendingPatch implements ClassFilePatch {
        private final String suffix;

        protected AppendingPatch(String suffix) {
            this.suffix = suffix;
        }

        @Override
        public byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader loader) {
            String input = new String(classfileBuffer, StandardCharsets.UTF_8);
            return (input + suffix).getBytes(StandardCharsets.UTF_8);
        }
    }

    public static final class AppendA extends AppendingPatch {
        public AppendA() { super("A"); }
    }

    public static final class AppendB extends AppendingPatch {
        public AppendB() { super("B"); }
    }

    public static final class CountedPatch extends AppendingPatch {
        public static final AtomicInteger INSTANCES = new AtomicInteger();

        public CountedPatch() {
            super("I");
            INSTANCES.incrementAndGet();
        }
    }

    public static final class ReturnsNull implements ClassFilePatch {
        public ReturnsNull() { }

        @Override
        public byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader loader) {
            return null;
        }
    }
}
