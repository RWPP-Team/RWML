package io.github.rwml.api;

/** Receives coarse grained progress notifications during a load operation. */
@FunctionalInterface
public interface ProgressListener {

    /** Shared no-op instance used when the caller is not interested. */
    ProgressListener NOOP = (percent, message) -> {
    };

    /**
     * @param percent completion ratio in the range {@code [0, 100]}
     * @param message short human readable status line
     */
    void onProgress(int percent, String message);
}