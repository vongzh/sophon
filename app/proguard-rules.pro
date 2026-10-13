# Sophon ProGuard Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
