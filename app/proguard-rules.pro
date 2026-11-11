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
