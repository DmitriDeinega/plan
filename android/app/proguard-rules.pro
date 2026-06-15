# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.plan.data.api.** { *; }
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Gson
-keepattributes Signature
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }

# Hilt
-dontwarn com.google.errorprone.annotations.*
