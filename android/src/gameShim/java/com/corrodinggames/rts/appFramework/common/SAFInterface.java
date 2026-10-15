package com.corrodinggames.rts.appFramework.common;

/**
 * The holder a SAF directory listing answers with.
 *
 * <p>The platform-independent game archive does not carry this class, yet its file readers reference
 * it by this exact name (they were compiled against the Android build, which has it). This shim
 * restores it, so the archive the device mounts resolves every name its own classes name.</p>
 */
public interface SAFInterface {

    /** One listed document: its id, its display name, and whether it is a directory. */
    class FileRow {

        public String id;
        public String name;
        public boolean isDirectory;
    }
}
