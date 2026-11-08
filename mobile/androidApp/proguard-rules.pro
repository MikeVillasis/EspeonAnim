# MediaPipe tasks-vision: JNI looks up Java classes/methods by name, and it uses protobuf-lite
# generated messages reflectively.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.proto.**
# Optional LiteRT-LM classes referenced only by the ImageEmbedder task (unused here, not shipped)
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**

# Ktor (OkHttp engine)
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization: keep generated serializers for the shared module's @Serializable classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class io.github.mikevillasis.espeonanim.** { *; }

# CameraX ships its own consumer rules; keep the extension/camera2 interop entry points to be safe
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
