# Add project specific ProGuard rules here.

# ── SSHJ ─────────────────────────────────────────────────────────────────────
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keepclassmembers class net.schmizz.** { *; }
-keepclassmembers class com.hierynomus.** { *; }
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**

# ── BouncyCastle (used by SSHJ) ───────────────────────────────────────────────
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keepclassmembers @androidx.room.Dao class * { *; }
-dontwarn androidx.room.**

# ── Hilt ──────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}
-dontwarn dagger.**
-dontwarn javax.inject.**

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── Kotlin Serialization ──────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Compose ───────────────────────────────────────────────────────────────────
-keep @androidx.compose.runtime.Composable class * { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# ── Timber ────────────────────────────────────────────────────────────────────
-dontwarn org.jetbrains.annotations.**

# ── Android Security / Crypto ─────────────────────────────────────────────────
-keep class androidx.security.crypto.** { *; }

# ── General Android ───────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions

# Termux terminal: JNI native methods
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }

# ── Classes absentes a l'execution (R8 missing_rules) ─────────────────────────
# SuppressFBWarnings : annotation FindBugs referencee par YubiKit
# (UsbSmartCardConnection), presente uniquement a la compilation.
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
# sun.security.x509.X509Key : classe interne du JDK referencee par
# BouncyCastle / SSHJ, inexistante sur Android.
-dontwarn sun.security.x509.X509Key
