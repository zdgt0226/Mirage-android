# Mirage 保留规则 (release 已开启 R8 minify + resource shrinking)

# ── JNI 边界 ────────────────────────────────────────────────────────────
# mirage-jni 用 find_class("com/mirage/android/core/MirageNative") 按字符串
# 反查该类, 再用 call_static_method 按方法名调 protectFd / resolveConnectionOwner
# (见 native/mirage-jni/src/lib.rs:230,253,272)。类名、方法名、签名全部不可改。
# 同时 external fun 对应的 native 方法也必须保名, 否则 System.loadLibrary 后
# 找不到符号直接 UnsatisfiedLinkError。
-keep class com.mirage.android.core.MirageNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 被 JNI 回调触达的解析器 (MirageNative.resolveConnectionOwner 转发到此)
-keep class com.mirage.android.core.ConnectionOwnerResolver { *; }

# CoreService.protectFd / setActive / clearActive 经 MirageNative 静态转发,
# 属于 JNI 可达路径。
-keep class com.mirage.android.CoreService {
    public static *** protectFd(...);
    public static *** setActive(...);
    public static *** clearActive(...);
    public static *** getActive(...);
}

# ── AIDL / Binder ───────────────────────────────────────────────────────
# AIDL 生成的 Stub/Proxy 依赖反射与固定内部结构, 混淆会破坏跨进程调用。
-keep interface com.mirage.android.core.ICoreService { *; }
-keep interface com.mirage.android.core.ICoreCallback { *; }
-keep class com.mirage.android.core.ICoreService$* { *; }
-keep class com.mirage.android.core.ICoreCallback$* { *; }
-keep class * implements android.os.IInterface { *; }

# ── 清单中声明的组件 ────────────────────────────────────────────────────
-keep class com.mirage.android.MainActivity { *; }
-keep class com.mirage.android.service.MirageTileService { *; }
-keep class com.mirage.android.receiver.CoreActionReceiver { *; }
-keep class com.mirage.android.ui.GeoAssetActivity { *; }
-keep class com.mirage.android.ui.AppFilterActivity { *; }

# ── 数据模型 ────────────────────────────────────────────────────────────
# 与 Rust 侧靠 JSON 字段名对接, 字段名不可混淆。
-keepclassmembers class com.mirage.android.data.model.** {
    <fields>;
    <init>(...);
}

# 保留行号以便解读线上崩溃栈
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
