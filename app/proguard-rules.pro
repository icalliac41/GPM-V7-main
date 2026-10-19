# ═══════════════════════════════════════════════════════════════
#  libxposed API 102 — keep entry & hookers
# ═══════════════════════════════════════════════════════════════
-dontwarn io.github.libxposed.annotation.**
-dontwarn io.github.libxposed.api.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-keepclassmembers,allowoptimization class ** implements io.github.libxposed.api.XposedInterface$Hooker {
    public java.lang.Object intercept(io.github.libxposed.api.XposedInterface$Chain);
}

# ═══════════════════════════════════════════════════════════════
#  Xposed hook package
# ═══════════════════════════════════════════════════════════════
-keep class com.gpm.virtual.xposed.** { *; }
-keep class com.gpm.virtual.util.XposedStatus { *; }
-keep class com.gpm.virtual.data.provider.LocationConfigProvider { *; }

# ═══════════════════════════════════════════════════════════════
#  ⭐ Data package — config & IPC bridge
#  Melindungi: SortirConfigController, InjectionPrefsHelper,
#  ConfigSyncRegistry, CancelCantikController
# ═══════════════════════════════════════════════════════════════
-keep class com.gpm.virtual.data.** { *; }

# ═══════════════════════════════════════════════════════════════
#  ⭐ Nama class XposedModule jangan di-obfuscate
#  LSPosed baca dari META-INF/xposed/java_init.list
# ═══════════════════════════════════════════════════════════════
-keepnames class * extends io.github.libxposed.api.XposedModule

# ═══════════════════════════════════════════════════════════════
#  ⭐ Receiver & Service dinamis
# ═══════════════════════════════════════════════════════════════
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Service { *; }