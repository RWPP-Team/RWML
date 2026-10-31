/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;

/**
 * 将 dex 文件或含 dex 的扩展归档加入正在运行的应用类路径。
 *
 * <p>由哪个类加载器持有修补后的类非常重要，因为 mixin 会被编译为目标类中的静态调用，而该调用会通过
 * 定义目标类的加载器解析。因此，首选方案是将 dex 加入应用自身的加载器：修补后的游戏类会与其他类
 * 位于同一路径，mixin 也能访问其所属扩展和加载该扩展的宿主。</p>
 *
 * <p>平台没有提供对应的公开 API。平台自身使用 {@code DexPathList.addDexPath}，因此需要通过反射访问；
 * 如果设备拒绝调用隐藏 API，则回退到以应用加载器为父加载器的 {@link DexClassLoader}。它定义的类完全
 * 相同，唯一差别是这些类不再由应用自身的加载器直接访问。</p>
 *
 * <p>两种方式打开文件的流程相同，也会因同一原因被拒绝：平台不会加载仍可写入的 dex。因此，在此处将
 * 每个文件设为只读，这是最后一个可以执行该操作的位置，也能修复旧版本遗留的可写缓存。扩展归档保留为
 * 完整 JAR，使类加载器还能从同一归档读取 Compose 图片、字体、配置和其他资源。</p>
 */
final class DexClassPath {

    private DexClassPath() {
    }

    /**
     * 将所有 dex 文件或扩展 JAR 加入类路径，并返回现在拥有它们的加载器。
     *
     * @param optimizedDirectory 平台可在此保存自身优化产物的目录
     */
    static ClassLoader mount(List<Path> classPathFiles, Path optimizedDirectory, ClassLoader appLoader)
        throws IOException {
        if (classPathFiles.isEmpty()) {
            throw new IOException("no dex to mount");
        }
        for (Path classPathFile : classPathFiles) {
            Dexer.makeReadOnly(classPathFile);
        }
        // DexClassLoader 要求优化目录已经存在才会启动；下方的回退路径正是需要它的场景。
        Files.createDirectories(optimizedDirectory);
        Throwable refused = null;
        if (appLoader instanceof BaseDexClassLoader) {
            int added = 0;
            try {
                for (; added < classPathFiles.size(); added++) {
                    addToAppLoader(appLoader, classPathFiles.get(added));
                }
                return appLoader;
            } catch (Throwable t) {
                // 部分挂载不会造成问题：相同的类也会由回退加载器提供。
                refused = t;
            }
            // Android 9 及之后版本会拒绝反射访问 SDK 之外的内容，而向运行中的应用路径添加 dex 正属于此类操作。
            // 平台自身的绕过方式也属于隐藏 API，因此只有首次访问被拒绝后才尝试使用它；随后从中断处继续添加，
            // 不会重复处理已经加入路径的 dex。
            exemptHiddenApi();
            try {
                for (; added < classPathFiles.size(); added++) {
                    addToAppLoader(appLoader, classPathFiles.get(added));
                }
                return appLoader;
            } catch (Throwable t) {
                refused = t;
            }
        }

        String path = join(classPathFiles);
        ClassLoader loader = new DexClassLoader(path, optimizedDirectory.toAbsolutePath().toString(),
            null, appLoader);
        if (refused != null) {
            // 需要明确指出：通过此加载器，应用自身的界面类无法解析游戏类，因此所有界面都只能退回到不依赖游戏的功能。
            HostLog.warn("The app class loader refused the patched dex (" + refused
                + "); the game is loaded on a loader of its own, which the app's own classes cannot "
                + "see.", refused);
        }
        return loader;
    }

    /**
     * 将 dex 挂载到独立加载器中；该加载器会先于父加载器搜索，并在此处返回。
     *
     * <p>mixin 注册在此加载器上执行。注册过程需要的替身类型只在执行期间有用；如果游戏也使用同一加载器，
     * 这些类型就会一直保留：类只定义一次且不会重新定义，因此注册期间由替身提供的游戏类型会在此进程
     * 中一直指向替身，之后挂载的修补 dex 也不会再为其提供类。将替身放在其他部分都不使用的加载器上，
     * 才能避免注册过程影响正在运行的游戏。</p>
     *
     * <p>搜索顺序是另一部分。若优先搜索父加载器，mixin 会由父加载器提供，但其中签名引用的游戏类型在
     * 父加载器中无法解析。因此，此加载器先搜索自身路径，只对自身不包含的类回退到父加载器。</p>
     *
     * @param dexFiles 要持有的 dex，按搜索顺序排列
     * @param optimizedDirectory 平台可在此保存自身优化产物的目录
     * @param parent dex 未包含某个类名时负责提供该类的父加载器
     * @param localFirst 先从 dex 查找、之后才询问父加载器的类名；包括替身类型以及 mixin 所引用的扩展自有类型
     */
    static ClassLoader mountIsolated(List<Path> dexFiles, Path optimizedDirectory, ClassLoader parent,
                                     Set<String> localFirst) throws IOException {
        if (dexFiles.isEmpty()) {
            throw new IOException("no dex to mount");
        }
        for (Path dex : dexFiles) {
            Dexer.makeReadOnly(dex);
        }
        Files.createDirectories(optimizedDirectory);
        return new LocalFirstDexLoader(join(dexFiles),
            optimizedDirectory.toAbsolutePath().toString(), null, parent, localFirst);
    }

    /** @return 加载器是否在自身路径上拥有此 dex；若是，则应用类不应遮蔽其中的类。 */
    static boolean isAppLoader(ClassLoader loader) {
        return loader != null && loader == DexClassPath.class.getClassLoader();
    }

    /**
     * 请求平台允许访问此类所需的成员。
     *
     * <p>{@code VMRuntime.setHiddenApiExemptions} 是平台绕过隐藏 API 策略的内部接口，使用前缀
     * {@code "L"} 可以一次豁免所有成员。该接口本身也是隐藏 API，因此仍通过反射访问；若平台拒绝访问，
     * 就继续使用回退方案。</p>
     */
    private static void exemptHiddenApi() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method exemptions = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            exemptions.setAccessible(true);
            exemptions.invoke(runtime, (Object) new String[] {"L"});
        } catch (Throwable ignored) {
            // 拒绝此操作的平台也会拒绝 dex 路径访问，因此继续使用回退加载器。
        }
    }

    private static void addToAppLoader(ClassLoader appLoader, Path dex) throws Exception {
        Object pathList = readField(appLoader, "pathList");
        if (pathList == null) {
            throw new NoSuchFieldException("BaseDexClassLoader.pathList");
        }
        Method add = findAddDexPath(pathList.getClass());
        if (add == null) {
            throw new NoSuchMethodException("DexPathList.addDexPath");
        }
        add.setAccessible(true);
        String path = dex.toAbsolutePath().toString();
        Class<?>[] parameters = add.getParameterTypes();
        if (parameters.length == 1) {
            add.invoke(pathList, path);
        } else if (parameters[1] == Boolean.TYPE) {
            // 较新版本接收 dex 是否可信的标志，而不是优化目录。
            add.invoke(pathList, path, Boolean.TRUE);
        } else {
            // 为兼容性，API 26 及之后版本仍接收优化目录参数，但会忽略它。
            add.invoke(pathList, path, (File) null);
        }
    }

    private static Method findAddDexPath(Class<?> pathListClass) {
        for (Class<?> type = pathListClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("addDexPath")) {
                    continue;
                }
                int count = method.getParameterCount();
                if (count == 2 && method.getParameterTypes()[0] == String.class) {
                    return method;
                }
                if (count == 1 && method.getParameterTypes()[0] == String.class) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续向父加载器查找。
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 一种 dex 加载器：针对指定类名，优先从自身 dex 加载，然后再询问父加载器。
     *
     * <p>平台的加载器都会先搜索父加载器。用于注册 mixin 时，这会导致扩展类来自游戏运行所用的加载器，
     * 但扩展中的签名引用了该加载器无法解析的游戏类型。优先搜索自身路径可让 mixin 类和替身类位于同一
     * 加载器中；签名引用的类型必须与 mixin 位于同一加载器，才能相互解析。</p>
     *
     * <p>需要查找的类名是显式指定的，而不是根据 dex 内容推导。注册器会将游戏替身和扩展 mixin 引用的扩展自有
     * 类型列入此集合；平台、框架、应用及共享运行库类型仍由父加载器提供。</p>
     *
     * <p>此加载器定义的内容不会泄漏：它只交给注册过程使用，并会随注册过程一起丢弃。</p>
     */
    private static final class LocalFirstDexLoader extends DexClassLoader {

        private final Set<String> localFirst;

        private LocalFirstDexLoader(String dexPath, String optimizedDirectory,
                                    String librarySearchPath, ClassLoader parent,
                                    Set<String> localFirst) {
            super(dexPath, optimizedDirectory, librarySearchPath, parent);
            this.localFirst = localFirst;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!localFirst.contains(name)) {
                return super.loadClass(name, resolve);
            }
            // 监视器由此加载器自行持有，因为 Android 上的 ClassLoader 没有 getClassLoadingLock，
            // 这里也无法访问平台的并行加载器实现。
            synchronized (this) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    try {
                        found = findClass(name);
                    } catch (ClassNotFoundException notLocal) {
                        found = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }
    }

    private static String join(List<Path> dexFiles) {
        StringBuilder path = new StringBuilder();
        for (Path dex : dexFiles) {
            if (path.length() > 0) {
                path.append(File.pathSeparatorChar);
            }
            path.append(dex.toAbsolutePath());
        }
        return path.toString();
    }
}
