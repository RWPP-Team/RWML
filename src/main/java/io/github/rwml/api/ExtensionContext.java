package io.github.rwml.api;

import io.github.rwml.asm.MixinSupport;

import java.nio.file.Path;

/** Runtime services handed to an extension when it is loaded. */
public interface ExtensionContext {

    /** @return the id declared by the owning extension. */
    String extensionId();

    /** @return a private, writable directory dedicated to this extension. */
    Path dataDirectory();

    /** @return the isolated class loader that owns this extension. */
    ClassLoader classLoader();

    /**
     * @return the ASM mixin bridge. Use it to register mixins programmatically
     *         or to transform class buffers the extension loads itself.
     */
    MixinSupport mixin();

    /** Writes an informational message to the host log. */
    void log(String message);

    /** Writes an error message with stack trace to the host log. */
    void log(String message, Throwable error);
}