/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core.transform;

import java.nio.file.Path;
import java.util.Objects;

/** Carries the owning extension archive when a registered class-file patch fails. */
public final class ClassFilePatchException extends RuntimeException {

    private final Path archive;
    private final String internalName;
    private final String patchClassName;

    ClassFilePatchException(Path archive, String internalName, String patchClassName, Throwable cause) {
        super("Class-file patch " + patchClassName + " from " + archive.getFileName()
            + " failed for " + internalName, cause);
        this.archive = Objects.requireNonNull(archive, "archive");
        this.internalName = Objects.requireNonNull(internalName, "internalName");
        this.patchClassName = Objects.requireNonNull(patchClassName, "patchClassName");
    }

    public Path archive() {
        return archive;
    }

    public String internalName() {
        return internalName;
    }

    public String patchClassName() {
        return patchClassName;
    }
}
