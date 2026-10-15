# The core RWML artifact is a self-contained JVM agent: it bundles kotlin-stdlib, asm and the
# IronCore ASM framework, and its agent entry points reference java.lang.instrument. None of that is
# used on Android (mixins are applied at build time instead), so the missing desktop-only types are
# harmless and must not fail the build.
-dontwarn java.lang.instrument.**
-dontwarn com.sun.tools.attach.**
-dontwarn javax.annotation.**

# Extensions are loaded reflectively by RWML itself, so their entry points and mixins must survive
# shrinking if minification is ever turned on.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class io.github.rwml.** { *; }
-keep class kim.der.asm.** { *; }
-keep class com.corrodinggames.rts.** { *; }
-keepclassmembers class * implements io.github.rwml.api.Extension {
    public <init>();
}
