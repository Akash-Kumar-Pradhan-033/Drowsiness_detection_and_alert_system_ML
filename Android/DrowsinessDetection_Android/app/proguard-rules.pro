# Keep ONNX Runtime classes
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }

# Keep model-related classes
-keep class com.AK033.drowsinessdetection.** { *; }

# OkHttp rules
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**