/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */
package io.github.rwml.core;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads class-level metadata without defining classes or resolving their dependencies. */
final class ClassFileMetadata {
    private static final String EXTENSION_INFO = "Lio/github/rwml/api/ExtensionInfo;";

    final String superName;
    final Annotation annotation;

    private ClassFileMetadata(String superName, Annotation annotation) {
        this.superName = superName;
        this.annotation = annotation;
    }

    static ClassFileMetadata read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("Invalid class file header");
        }
        in.readUnsignedShort(); // minor version
        in.readUnsignedShort(); // major version: metadata is independent of the host JVM version
        Object[] pool = new Object[in.readUnsignedShort()];
        for (int i = 1; i < pool.length; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: pool[i] = in.readUTF(); break;
                case 3: pool[i] = in.readInt(); break;
                case 4: in.readInt(); break;
                case 5: case 6: in.readLong(); i++; break;
                case 7: pool[i] = in.readUnsignedShort(); break;
                case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                case 15: skip(in, 3); break;
                default: throw new IOException("Unsupported constant pool tag " + tag);
            }
        }
        in.readUnsignedShort(); // access
        in.readUnsignedShort(); // this class
        int parent = in.readUnsignedShort();
        String superName = parent == 0 ? null : (String) pool[(Integer) pool[parent]];
        skip(in, 2L * in.readUnsignedShort()); // interfaces
        skipMembers(in); // fields
        skipMembers(in); // methods
        int attributes = in.readUnsignedShort();
        Annotation found = null;
        for (int i = 0; i < attributes; i++) {
            String name = (String) pool[in.readUnsignedShort()];
            long length = Integer.toUnsignedLong(in.readInt());
            if (!"RuntimeVisibleAnnotations".equals(name)) {
                skip(in, length);
                continue;
            }
            int count = in.readUnsignedShort();
            for (int n = 0; n < count; n++) {
                Annotation annotation = readAnnotation(in, pool);
                if (EXTENSION_INFO.equals(annotation.type)) {
                    found = annotation;
                }
            }
        }
        return new ClassFileMetadata(superName, found);
    }

    private static void skipMembers(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            skip(in, 6); // access, name, descriptor
            int attributes = in.readUnsignedShort();
            for (int n = 0; n < attributes; n++) {
                in.readUnsignedShort();
                skip(in, Integer.toUnsignedLong(in.readInt()));
            }
        }
    }

    private static Annotation readAnnotation(DataInputStream in, Object[] pool) throws IOException {
        String type = (String) pool[in.readUnsignedShort()];
        Map<String, Object> values = new LinkedHashMap<>();
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            String name = (String) pool[in.readUnsignedShort()];
            values.put(name, readValue(in, pool));
        }
        return new Annotation(type, values);
    }

    private static Object readValue(DataInputStream in, Object[] pool) throws IOException {
        int tag = in.readUnsignedByte();
        switch (tag) {
            case 'B': case 'C': case 'D': case 'F': case 'I': case 'J': case 'S': case 'Z': case 's':
                return pool[in.readUnsignedShort()];
            case 'e': in.readUnsignedShort(); in.readUnsignedShort(); return null;
            case 'c': in.readUnsignedShort(); return null;
            case '@': return readAnnotation(in, pool);
            case '[':
                int count = in.readUnsignedShort();
                List<Object> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    values.add(readValue(in, pool));
                }
                return values;
            default: throw new IOException("Invalid annotation value tag " + tag);
        }
    }

    private static void skip(DataInputStream in, long count) throws IOException {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped == 0) {
                if (in.read() == -1) {
                    throw new EOFException("Truncated class file");
                }
                skipped = 1;
            }
            count -= skipped;
        }
    }

    static final class Annotation {
        private final String type;
        private final Map<String, Object> values;

        Annotation(String type, Map<String, Object> values) {
            this.type = type;
            this.values = values;
        }

        String string(String key) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : null;
        }

        List<String> strings(String key) {
            Object value = values.get(key);
            if (!(value instanceof List<?>)) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (item instanceof String) {
                    result.add((String) item);
                }
            }
            return result;
        }

        int integer(String key, int fallback) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }
    }
}
