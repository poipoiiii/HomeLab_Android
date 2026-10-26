# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.poi.homelab.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.poi.homelab.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
