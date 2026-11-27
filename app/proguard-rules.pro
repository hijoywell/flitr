# Flitr release rules
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# kotlinx.serialization
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# 规则模型通过 @Serializable 生成序列化器, 保留其静态结构
-keep,includedescriptorclasses class com.aios.flitr.**$$serializer { *; }
-keepclassmembers class com.aios.flitr.** {
    *** Companion;
}
-keepclasseswithmembers class com.aios.flitr.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit (text-recognition-chinese, 内置模型) 内部用反射按类名加载实现类。
# R8 full mode 会剥离这些类, 表现为 InputImage.fromBitmap 抛 NullPointerException
# ("getClass() on a null object reference")。整体保留 ML Kit 及其内部实现类。
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_**
