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

# ── i2p EdDSA (provider JCA Ed25519) ──────────────────────────────────────────
# EdDSASecurityProvider (enregistre dans App) declare ses implementations sous
# forme de CHAINES : Provider.put("KeyPairGenerator.EdDSA",
# "net.i2p.crypto.eddsa.KeyPairGenerator"), idem KeyFactory.EdDSA et
# Signature.NONEwithEdDSA (EdDSAEngine). La JCA les charge par nom, via
# Class.forName puis le constructeur sans argument : aucune reference statique
# n y mene. Sans regle, R8 supprime KeyFactory et KeyPairGenerator et renomme
# EdDSAEngine, et KeyPairGenerator.getInstance("EdDSA", provider) echoue en
# release avec "class configured for KeyPairGenerator (provider: EdDSA) cannot
# be found" (la generation de cle Ed25519 est cassee). Les noms ET les membres
# (dont <init>) doivent survivre. Ne pas retirer : ProguardRulesGuardTest.
-keep class net.i2p.crypto.eddsa.** { *; }

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
# Les niveaux verbose et debug n'existent pas en release : R8 supprime les appels
# (ReleaseLogTree les ignore deja, cela evite aussi de construire leurs messages).
# Timber.d(...) passe par Timber$Forest, Timber.tag(...).d(...) par Timber$Tree.
# tag(...) est retire avec l'appel qu'il prepare : Timber garde l'etiquette dans un
# thread-local qu'un prochain appel consomme, donc un tag() reste seul etiquetterait
# a tort la ligne INFO suivante.
-assumenosideeffects class timber.log.Timber$Forest {
    public *** v(...);
    public *** d(...);
    public *** tag(...);
}
-assumenosideeffects class timber.log.Timber$Tree {
    public *** v(...);
    public *** d(...);
}
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** tag(...);
}

# Les journaux de release donnent la classe des exceptions (errorClass) : sans cette regle
# R8 renomme ces classes et la ligne devient illisible ("a<-b").
-keepnames class * extends java.lang.Throwable

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
