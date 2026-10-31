/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * A non-defining parent loader that makes declared extension dependencies visible to a child extension.
 *
 * <p>Classes remain defined by their owning extension loader. This is important for shared library APIs:
 * two consumers that depend on the same provider resolve a type to the provider's class, preserving one
 * class identity and static state. Host classes are still resolved first.</p>
 */
public final class ExtensionDependencyClassLoader extends ClassLoader {

    private final Object loadLock = new Object();
    private final List<ClassLoader> dependencies;

    public ExtensionDependencyClassLoader(ClassLoader hostLoader, List<ClassLoader> dependencies) {
        super(hostLoader);
        this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Android's ClassLoader API does not expose getClassLoadingLock on every supported runtime.
        synchronized (loadLock) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }

            ClassLoader host = getParent();
            try {
                return host == null ? Class.forName(name, false, null) : host.loadClass(name);
            } catch (ClassNotFoundException absentFromHost) {
                for (ClassLoader dependency : dependencies) {
                    try {
                        return dependency.loadClass(name);
                    } catch (ClassNotFoundException absentFromDependency) {
                        // Try the next declared dependency in order.
                    }
                }
                throw new ClassNotFoundException(name + " (not found in host or declared dependencies)",
                    absentFromHost);
            }
        }
    }

    @Override
    public URL getResource(String name) {
        URL fromHost = getParent() == null ? ClassLoader.getSystemResource(name) : getParent().getResource(name);
        if (fromHost != null) {
            return fromHost;
        }
        for (ClassLoader dependency : dependencies) {
            URL resource = dependency.getResource(name);
            if (resource != null) {
                return resource;
            }
        }
        return null;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> resources = new ArrayList<>();
        Enumeration<URL> host = getParent() == null
            ? ClassLoader.getSystemResources(name) : getParent().getResources(name);
        append(resources, host);
        for (ClassLoader dependency : dependencies) {
            append(resources, dependency.getResources(name));
        }
        return Collections.enumeration(resources);
    }

    private static void append(List<URL> target, Enumeration<URL> source) {
        while (source.hasMoreElements()) {
            URL resource = source.nextElement();
            if (!target.contains(resource)) {
                target.add(resource);
            }
        }
    }
}
