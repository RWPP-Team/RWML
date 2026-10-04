package io.github.rwml.api;

/** Lifecycle state of an extension as seen by the host. */
public enum ExtensionState {
    /** Archive has been seen but no descriptor was parsed yet. */
    DISCOVERED,
    /** Descriptor parsed, dependency graph being resolved. */
    RESOLVING,
    /** Artifacts are being fetched from the remote repository. */
    DOWNLOADING,
    /** Classes are being loaded and the entry point instantiated. */
    LOADING,
    /** Loaded and {@code onLoad} completed successfully. */
    ENABLED,
    /** Persisted as disabled; the host does not load it on startup. */
    DISABLED,
    /** A lifecycle callback threw an exception. */
    FAILED,
    /** The runtime Java version cannot satisfy the extension requirement. */
    INCOMPATIBLE
}