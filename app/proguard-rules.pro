# --- AEGIS ProGuard/R8 правила ---

# Сохраняем имена JNI-функций и класс-мост к нативному ядру.
# Имена нативных методов и класса НЕ должны переименовываться (иначе JNI не свяжет).
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.aegis.crypto.NativeAegisCore { *; }

# kotlinx.serialization — сохраняем сериализаторы моделей настроек/безопасности.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.aegis.**$$serializer { *; }
-keepclassmembers class com.aegis.** {
    *** Companion;
}

# Нативная часть WebRTC (включая FrameCryptor) находит свои Java-классы и методы
# по именам через JNI, а своих правил R8 в AAR webrtc-sdk нет.
-keep class org.webrtc.** { *; }
