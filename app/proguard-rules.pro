# Safety nets for libraries that load classes by reflection
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
-keep class androidx.camera.extensions.** { *; }
-keep class androidx.camera.camera2.** { *; }
-dontwarn com.google.**
-dontwarn androidx.camera.**
