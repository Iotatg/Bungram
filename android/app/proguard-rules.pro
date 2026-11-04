# Bungram release rules
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keep class app.bungram.data.model.** { *; }
-keep class app.bungram.data.remote.dto.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.bungram.**$$serializer { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
