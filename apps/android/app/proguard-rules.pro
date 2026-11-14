# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# For more details, see
#   https://developer.android.com/studio/build/shrink-code.html

# If you are developing with Flutter, you can add more Flutter specific rules here.
# Keep the Flutter activity
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.**  { *; }
-keep class io.flutter.util.**  { *; }
-keep class io.flutter.view.**  { *; }
-keep class io.flutter.**  { *; }
-keep class io.flutter.plugins.**  { *; }

# Keep Flowww models
-keep class com.flowww.android.content.** { *; }
-keep class com.flowww.android.connection.** { *; }
-keep class com.flowww.android.pairing.** { *; }
-keep class com.flowww.android.security.** { *; }
-keep class com.flowww.android.storage.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
