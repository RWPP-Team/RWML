package io.github.rwml.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Static metadata attached to the entry point of an extension.
 *
 * <p>All fields except {@link #id()} and {@link #name()} have defaults so that
 * a minimal extension can be written with a one line annotation. The values
 * declared here may be overridden at runtime by the {@code META-INF/extension.properties}
 * descriptor shipped inside the extension archive.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface ExtensionInfo {

    /** Globally unique identifier, e.g. {@code com.acme.hello}. */
    String id();

    /** Display name. */
    String name();

    /** Semantic version of the extension itself. */
    String version() default "1.0.0";

    /** Long description shown by management tooling. */
    String description() default "";

    /** Primary author. Superseded by {@link #authors()} when non empty. */
    String author() default "";

    /** Full author list. */
    String[] authors() default {};

    /** SPDX license identifier, e.g. {@code Apache-2.0}. */
    String license() default "";

    /** URL of the full license text. */
    String licenseUrl() default "";

    /** Project home page. */
    String homepage() default "";

    /** Classpath resource path of the icon, e.g. {@code /icon.png}. */
    String icon() default "";

    /** Free form search tags. */
    String[] tags() default {};

    /** Version of the host extension API this extension was compiled against. */
    String apiVersion() default "1.0";

    /** Minimum Java runtime version required by this extension. */
    int javaVersion() default 8;

    /**
     * Fully qualified entry point class. When empty the annotated class itself
     * is used as the entry point.
     */
    String mainClass() default "";

    /**
     * Ids of other extensions this extension <em>requires</em>.
     *
     * <p>A missing or failed dependency causes this extension to be skipped
     * and marked as failed. The dependency is also guaranteed to be loaded
     * earlier in the load order.</p>
     */
    String[] dependsOn() default {};

    /**
     * Ids of extensions that should be loaded before this one.
     *
     * <p>Soft ordering hint: unknown ids are silently ignored.</p>
     */
    String[] loadAfter() default {};

    /**
     * Ids of extensions that should be loaded after this one.
     *
     * <p>Soft ordering hint: unknown ids are silently ignored.</p>
     */
    String[] loadBefore() default {};

    /** Maximum time allowed for a single lifecycle callback. */
    long loadTimeoutMillis() default 30_000L;
}