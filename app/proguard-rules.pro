# Keep serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.example.aichat.**$$serializer { *; }
-keepclassmembers class com.example.aichat.** { *** Companion; }
-keepclasseswithmembers class com.example.aichat.** { kotlinx.serialization.KSerializer serializer(...); }
