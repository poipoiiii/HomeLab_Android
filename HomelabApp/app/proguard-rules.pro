# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.rong.homelab.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.rong.homelab.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
