package io.github.rwml.api;

/** Checked exception raised for every recoverable extension failure. */
public class ExtensionException extends Exception {

    private static final long serialVersionUID = 1L;

    public ExtensionException(String message) {
        super(message);
    }

    public ExtensionException(String message, Throwable cause) {
        super(message, cause);
    }
}