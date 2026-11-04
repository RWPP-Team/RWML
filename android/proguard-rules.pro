# RWML 核心构件是自包含的 JVM 代理：其中打包了 kotlin-stdlib、asm 和 IronCore ASM 框架，代理入口会引用
# java.lang.instrument。Android 不会用到这些内容（mixin 在构建时应用），因此缺少仅桌面端使用的类型是正常情况，
# 不应导致构建失败。
-dontwarn java.lang.instrument.**
-dontwarn com.sun.tools.attach.**
-dontwarn javax.annotation.**

# RWML 会通过反射加载扩展，因此即使将来启用代码缩减，扩展入口点和 mixin 也必须保留。
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class io.github.rwml.** { *; }
-keep class kim.der.asm.** { *; }
-keep class com.corrodinggames.rts.** { *; }
-keepclassmembers class * implements io.github.rwml.api.Extension {
    public <init>();
}
