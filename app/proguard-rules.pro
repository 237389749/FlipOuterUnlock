-adaptresourcefilecontents META-INF/xposed/java_init.list
-keepattributes RuntimeVisibleAnnotations

-keep,allowobfuscation,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
    public void onModuleLoaded(...);
    public void onPackageReady(...);
    public void onSystemServerStarting(...);
}
-keep,allowshrinking,allowoptimization,allowobfuscation class ** implements io.github.libxposed.api.XposedInterface$Hooker
-keepclassmembers,allowoptimization class ** implements io.github.libxposed.api.XposedInterface$Hooker {
    public *** intercept(...);
}

-keep class io.github.luckypray.dexkit.** { *; }

-repackageclasses
-allowaccessmodification
