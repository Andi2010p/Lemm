# ============================================================================
#  Lemma — R8 / ProGuard keep rules for the release build.
#  Strategy: keep ALL app classes (so Firebase POJOs, reflection and the
#  drawing-command parser never break), and let R8 shrink + obfuscate the
#  libraries and strip unused resources. Explicit keeps below cover libraries
#  that use reflection and would otherwise break at runtime.
# ============================================================================

# Keep readable crash stack traces from the field.
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# --- App code: keep everything (safe; still shrinks/obfuscates libraries) ---
-keep class com.example.lemm.** { *; }

# --- JavaMail / Jakarta Mail (SMTP OTP email) — reflection-based providers ---
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn java.awt.**
-dontwarn myjava.awt.**

# --- Google Generative AI (Gemini) ---
-keep class com.google.ai.client.generativeai.** { *; }
-dontwarn com.google.ai.client.generativeai.**

# --- Firebase (Auth + Realtime Database). DB uses reflection for model I/O. ---
-keep class com.google.firebase.** { *; }
-keepnames class com.google.firebase.** { *; }
-keepclassmembers class * {
    @com.google.firebase.database.PropertyName <methods>;
}
-dontwarn com.google.firebase.**

# --- JTS Topology Suite (2D geometry engine) ---
-keep class org.locationtech.jts.** { *; }
-dontwarn org.locationtech.jts.**

# --- Google Play Billing ---
-keep class com.android.billingclient.api.** { *; }

# --- OkHttp / Okio (transitive) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# --- Kotlin / coroutines metadata (used by the Gemini client) ---
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# --- Strip debug logging from the shipped app ---
# R8 removes these calls entirely in release, so nothing logged while solving/chatting (problem text,
# billing state, key handling) can be read off a user's device with `adb logcat`.
# Log.w / Log.e are deliberately KEPT so real errors still surface in crash reports.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
