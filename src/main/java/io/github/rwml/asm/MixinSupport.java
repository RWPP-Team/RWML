package io.github.rwml.asm;

import kim.der.asm.AsmScanner;
import kim.der.asm.agent.AsmBootstrap;
import kim.der.asm.transformer.AsmProcessor;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Bridge between the RWML extension system and the IronCore ASM-Framework.
 *
 * <p>The framework provides an annotation-driven, Mixin-like bytecode model: extensions declare
 * {@code @AsmMixin} classes and the framework rewrites the matching target classes as they are
 * loaded. Two things have to happen for that to work inside this host:</p>
 * <ol>
 *   <li>The framework's {@link AsmBootstrap} transformer must be installed into the running JVM so
 *       class loading is intercepted. This is done once, by self-attaching the framework's agent jar
 *       at runtime (no {@code -javaagent} launch flag required). If the host was already started with
 *       {@code -javaagent:rwml.jar} the self-attach is skipped.</li>
 *   <li>Every extension archive is scanned for {@code @AsmMixin} classes and they are registered into
 *       the shared registry, so the transformer knows what to apply.</li>
 * </ol>
 *
 * <p>Registration is idempotent and best-effort: a malformed extension jar never aborts the
 * surrounding load pass, and a failing agent attach only turns off live bytecode rewriting (the
 * mixins are still registered for a future run).</p>
 */
public final class MixinSupport {

    /** JVM internal descriptor of the framework's mixin marker annotation. */
    private static final String ASM_MIXIN_DESCRIPTOR = "Lkim/der/asm/api/annotation/AsmMixin;";

    private static final MixinSupport INSTANCE = new MixinSupport();

    private volatile boolean transformerInstalled;

    /** Canonical paths of the archives already handed to the system class loader. */
    private final Set<String> exposedArchives = new LinkedHashSet<>();

    private MixinSupport() {
    }

    public static MixinSupport instance() {
        return INSTANCE;
    }

    /**
     * Makes sure the ASM class-file transformer is installed. Safe to call many times; only attempts
     * the (idempotent) agent attach once.
     */
    public synchronized void installTransformer() {
        if (transformerInstalled) {
            return;
        }
        if (isAsmAgentInstalled()) {
            transformerInstalled = true;
            return;
        }
        String jar = findAsmFrameworkJar();
        if (jar == null) {
            System.err.println("[rwml-asm] ASM-Framework jar not found on the classpath; "
                + "mixin injection is disabled. Launch with -javaagent:rwml.jar to enable it.");
            return;
        }
        try {
            attachAgent(jar);
            transformerInstalled = true;
        } catch (Throwable t) {
            System.err.println("[rwml-asm] Could not self-attach the ASM agent (" + t + "). "
                + "Mixins will only apply if the host was launched with -javaagent:rwml.jar.");
        }
    }

    /** @return whether the ASM transformer is currently active in this JVM. */
    public boolean isInstalled() {
        return transformerInstalled || isAsmAgentInstalled();
    }

    /**
     * Scans an extension archive, registers every {@code @AsmMixin} it contains into the shared
     * registry, and retransforms target classes that were already loaded.
     *
     * <p>The framework's scanner loads every class in the jar (it must, to read the annotations
     * reflectively), and jar entry order is not guaranteed. A target class can therefore end up
     * defined <em>before</em> its mixin is registered, in which case the live transformer saw an
     * empty registry and skipped it. Retransforming those targets afterwards fixes that; classes not
     * yet loaded pick the mixin up automatically on first load.</p>
     */
    public void registerArchive(Path archive) {
        try {
            exposeToHostLoader(archive);
            AsmScanner.scanJarWithResult(archive.toFile(), "");
            retransformTargets(collectTargets(archive));
        } catch (Throwable t) {
            System.err.println("[rwml-asm] Failed to scan mixins in " + archive.getFileName() + ": " + t);
        }
    }

    /**
     * Makes an extension archive visible to the class loader that runs the host's own classes.
     *
     * <p>A mixin is compiled into a <em>static call</em> on the mixin class, emitted inside the target
     * method, and the JVM resolves that call through the defining loader of the target class. For an
     * extension that patches a class of the game - the main use case - the target is loaded by the
     * system class loader, which knows nothing about a jar sitting in {@code extensions/}. The
     * injected call would then fail with {@code NoClassDefFoundError} the first time the patched
     * method ran.</p>
     *
     * <p>{@link Instrumentation#appendToSystemClassLoaderSearch} is the supported way to fix that, and
     * it is available precisely because the bootstrap starts the game with {@code -javaagent}. The
     * {@link JarFile} is deliberately never closed: the JVM reads classes out of it for the rest of
     * the process lifetime.</p>
     */
    private void exposeToHostLoader(Path archive) {
        Instrumentation instrumentation = RwmlAgent.instrumentation();
        if (instrumentation == null) {
            // Without an agent there is no live rewriting either, so there is nothing to expose.
            return;
        }
        String key;
        try {
            key = archive.toRealPath().toString();
        } catch (IOException e) {
            key = archive.toAbsolutePath().toString();
        }
        synchronized (exposedArchives) {
            if (!exposedArchives.add(key)) {
                return;
            }
        }
        try {
            instrumentation.appendToSystemClassLoaderSearch(new JarFile(archive.toFile()));
        } catch (Throwable t) {
            System.err.println("[rwml-asm] Could not add " + archive.getFileName() + " to the host class "
                + "path; mixins patching host classes may fail to link: " + t);
        }
    }

    /**
     * Reads the target class names straight out of the {@code @AsmMixin} annotations of an archive.
     *
     * <p>Deliberately done with ASM rather than reflection: reflection would have to resolve the mixin
     * class through <em>this</em> class loader, while the mixin actually lives in the extension's own
     * class loader and is simply not visible here. Working on the raw bytes is load-order and
     * class-loader independent.</p>
     */
    private static Set<String> collectTargets(Path archive) {
        Set<String> targets = new LinkedHashSet<>();
        try (JarFile jar = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = readFully(in);
                }
                readMixinTargets(bytes, targets);
            }
        } catch (Throwable ignored) {
            // A malformed archive simply contributes no targets.
        }
        return targets;
    }

    /** Extracts {@code value} / {@code targets} of a possible {@code @AsmMixin} on one class. */
    private static void readMixinTargets(byte[] classBytes, Set<String> targets) {
        try {
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!ASM_MIXIN_DESCRIPTOR.equals(descriptor)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            if ("value".equals(name) && value instanceof String text) {
                                add(text);
                            }
                        }

                        @Override
                        public AnnotationVisitor visitArray(String name) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String name, Object value) {
                                    if (value instanceof String text) {
                                        add(text);
                                    }
                                }
                            };
                        }

                        private void add(String text) {
                            if (text != null && !text.trim().isEmpty()) {
                                targets.add(text.trim());
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable ignored) {
            // Not a class we can parse; skip it.
        }
    }

    private void retransformTargets(Set<String> targets) {
        Instrumentation instrumentation = RwmlAgent.instrumentation();
        if (instrumentation == null || targets.isEmpty()) {
            // No agent attached (and none could be self-attached): live rewriting is unavailable, but
            // the mixins remain registered for the next launch.
            return;
        }
        ClassLoader loader = getClass().getClassLoader();
        for (String internalName : targets) {
            try {
                Class<?> target = Class.forName(internalName.replace('/', '.'), false, loader);
                instrumentation.retransformClasses(target);
            } catch (Throwable ignored) {
                // Not loaded yet (or not visible here): the transformer will apply the mixin the
                // first time the class is actually loaded.
            }
        }
    }

    /** Registers a single mixin class directly (e.g. generated at runtime). */
    public void register(Class<?> mixin) {
        kim.der.asm.AsmRegistry.register(mixin);
    }

    /**
     * Applies every registered mixin to a class buffer. Mostly useful for extensions that load or
     * generate classes themselves; the installed transformer already covers classes loaded by the
     * host.
     */
    public byte[] transform(String internalName, byte[] classfileBuffer, ClassLoader loader) {
        return new AsmProcessor().transform(internalName, classfileBuffer, loader);
    }

    // ------------------------------------------------------------------
    // Agent detection / attach (reflective so the host compiles without the
    // JDK attach module on the classpath)
    // ------------------------------------------------------------------

    /**
     * The framework's agent stores its live instance in a private static field of {@code AsmCore}. We
     * read it to avoid installing a second transformer when one is already present (which would
     * double-apply mixins).
     */
    private static boolean isAsmAgentInstalled() {
        try {
            Class<?> core = Class.forName("kim.der.asm.agent.AsmCore");
            Field agentField = core.getDeclaredField("agent");
            agentField.setAccessible(true);
            return agentField.get(null) != null;
        } catch (Throwable ignored) {
            // If we cannot prove it is installed, assume not and let installTransformer try.
            return false;
        }
    }

    /** Resolves the on-disk path of the ASM-Framework jar from its agent class. */
    private static String findAsmFrameworkJar() {
        try {
            URL location = AsmBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            URI uri = location.toURI();
            if ("jar".equals(uri.getScheme())) {
                String part = uri.getSchemeSpecificPart();
                if (part.startsWith("file:")) {
                    part = part.substring("file:".length());
                }
                while (part.endsWith("!") || part.endsWith("/")) {
                    part = part.substring(0, part.length() - 1);
                }
                return part.isEmpty() ? null : part;
            }
            return location.getPath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Self-attaches the framework agent jar so its transformer is registered. */
    private static void attachAgent(String jarPath) throws Exception {
        Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Method attach = vmClass.getMethod("attach", String.class);
        Method loadAgent = vmClass.getMethod("loadAgent", String.class, String.class);
        Method detach = vmClass.getMethod("detach");
        Object vm = attach.invoke(null, currentProcessId());
        try {
            loadAgent.invoke(vm, jarPath, "");
        } finally {
            detach.invoke(vm);
        }
    }

    /** Current PID without relying on {@code ProcessHandle} (Java 9+). */
    private static String currentProcessId() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : name;
    }

    /** Java 8 stand-in for {@code InputStream.readAllBytes} (missing on Android below API 33). */
    private static byte[] readFully(InputStream stream) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
