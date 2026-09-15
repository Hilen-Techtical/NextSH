import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ── Signature release ─────────────────────────────────────────────────────
// Le secret vit dans `keystore.properties` a la racine du depot, ignore par
// git, jamais dans ce script ni dans les logs. Sans ce fichier (CI, poste
// sans keystore), le build release reste non signe : `bundleRelease` et
// `assembleRelease` passent quand meme, l artefact porte le suffixe
// `-unsigned`. Avec le fichier, une cle manquante ou un keystore introuvable
// arrete un build release avec un message clair plutot que de produire un
// artefact signe avec la mauvaise cle ; un build qui ne demande rien de
// release (assembleDebug, tests) n est qu averti, pour ne pas bloquer le
// poste sur un probleme qui ne le concerne pas encore.
// `storeFile` est relatif a la racine du depot, la ou vit le fichier de
// proprietes ; un chemin absolu convient aussi. Voir docs/build-apk.md.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystore: Map<String, String>? = run {
    if (!keystorePropertiesFile.isFile) return@run null
    val releaseRequested = gradle.startParameter.taskNames.any {
        it.contains("Release", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
    }
    fun problem(message: String): Nothing? {
        if (releaseRequested) error("keystore.properties : $message")
        logger.warn("keystore.properties : $message. Le build release serait non signe.")
        return null
    }
    val props = Properties().apply {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
    val values = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith {
        props.getProperty(it)?.trim().orEmpty()
    }
    val missing = values.filterValues { it.isEmpty() }.keys
    if (missing.isNotEmpty()) return@run problem("propriete(s) manquante(s) ou vide(s) : ${missing.joinToString()}")
    // `~/` est accepte en tete de chemin, Java ne le developpe pas tout seul.
    val storePath = values.getValue("storeFile").let {
        if (it.startsWith("~/") || it.startsWith("~\\")) System.getProperty("user.home") + it.substring(1) else it
    }
    val store = rootProject.file(storePath)
    if (!store.isFile) {
        return@run problem("storeFile introuvable : ${store.absolutePath} (chemin relatif a la racine du depot)")
    }
    values + ("storeFile" to store.absolutePath)
}

// ── Capture d ecran pour la video de demonstration ───────────────────────
// `-PscreenCapture=true` produit une build qui ne pose pas FLAG_SECURE (voir
// core/ScreenCapturePolicy.kt), marquee par un suffixe de version -capture.
// Jamais par defaut, jamais en CI, jamais publiee.
val allowScreenCapture = (project.findProperty("screenCapture") as String?)?.toBoolean() == true

android {
    namespace = "fr.techtical.nextsh"
    compileSdk = 36

    defaultConfig {
        buildConfigField("boolean", "ALLOW_SCREEN_CAPTURE", allowScreenCapture.toString())
        applicationId = "fr.techtical.nextsh"
        minSdk = 29
        targetSdk = 36
        versionCode = 8
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Cree seulement quand keystore.properties existe : voir l en-tete.
        releaseKeystore?.let { ks ->
            create("release") {
                storeFile = file(ks.getValue("storeFile"))
                storePassword = ks.getValue("storePassword")
                keyAlias = ks.getValue("keyAlias")
                keyPassword = ks.getValue("keyPassword")
                // minSdk 29 : la v3 suffit (Android 9 et plus). AGP n emet pas la v2 pour
                // un minSdk superieur ou egal a 28, meme demandee, et la v1 est inutile.
                enableV1Signing = false
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = if (allowScreenCapture) "-debug-capture" else "-debug"
            isDebuggable = true
        }
        release {
            if (allowScreenCapture) versionNameSuffix = "-capture"
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Lint baseline regenerated with AGP 8.9.3 / compileSdk 36
        // (migration API 34 -> 36). New regressions still fail the build;
        // baselined items tracked as TODOs:
        //   - data_extraction_rules.xml FullBackupContent (Room DB path)
        //   - EnrollmentScanScreen.kt UnsafeOptInUsageError (CameraX
        //     ExperimentalGetImage, annotation pending)
        //   - libs.versions.toml PlaySdkIndexVulnerability +
        //     SimilarGradleDependency: both point at the Desktop-only
        //     yubikit 2.8.1 declarations, the Android APK ships 2.4.0
        // Cleared during the migration: NewApi / InlinedApi on
        // KeystoreManager.kt (now guarded for API 29) and the two
        // orientation checks (portrait is now a documented default that
        // NavGraph lifts per route).
        baseline = file("lint-baseline.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            // Conflits courants avec BouncyCastle / SSHJ
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "mozilla/public-suffix-list.txt"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    // Module partagé KMP (domaine, interfaces, use cases)
    implementation(project(":shared"))

    // Modules terminaux locaux (fork Termux)
    implementation(project(":terminal:terminal-emulator"))
    implementation(project(":terminal:terminal-view"))

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    // Lucide icons : DA Techtical refondue (Phase 3). Parité Desktop.
    implementation(libs.compose.icons.lucide)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Security / Vault
    implementation(libs.bundles.security)

    // Credentials (FIDO2 / Passkey, expérimental)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)

    // FIDO2 Hardware Key (YubiKit)
    implementation(libs.bundles.fido2)

    // Room
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager (LAN sync scheduler, periodic work)
    implementation(libs.androidx.work.runtime)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // SSH
    implementation(libs.bundles.ssh)
    // i2p EdDSA: transitive via sshj but declared explicit so App.kt can reference
    // `EdDSASecurityProvider` at compile time to register it at startup.
    implementation(libs.i2p.eddsa)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Logging (no-op en release via ProGuard)
    implementation(libs.timber)

    // CameraX + ML Kit (QR enrollment scanner)
    implementation(libs.bundles.camerax)
    implementation(libs.mlkit.barcode.scanning)

    // Ktor client (LAN enrollment)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.serialization.json)

    // Tests
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
