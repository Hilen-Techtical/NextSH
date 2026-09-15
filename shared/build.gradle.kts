plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions.jvmTarget = "17" }
    }
    jvm("desktop") {
        compilations.all { kotlinOptions.jvmTarget = "17" }
    }

    // Silence Beta-status warnings on `expect`/`actual` classes, we use
    // them deliberately for VaultKeyProvider, DeviceIdentity, EcdhHandshake,
    // randomUuid, etc. The Kotlin team commits to keeping the API stable
    // and the flag is the documented opt-in.
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmCommon by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.bouncycastle.bcprov)
                implementation(libs.bouncycastle.bcpkix)
                // SSHJ: needed for SkSshPublicKey, SkSshSignature, SkAuthMethod
                // (Buffer.PlainBuffer, AbstractAuthMethod, etc.) shared between Android and Desktop
                implementation(libs.sshj)
            }
        }
        val androidMain by getting {
            dependsOn(jvmCommon)
            dependencies {
                implementation(libs.androidx.security.crypto)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.timber)
            }
        }
        val desktopMain by getting {
            dependsOn(jvmCommon)
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
                implementation(libs.jna.platform)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // SSHJ needed by SkSshSignatureTest (Buffer.PlainBuffer used in test assertions)
                implementation(libs.sshj)
            }
        }
    }
}

android {
    namespace = "fr.techtical.nextsh.shared"
    compileSdk = 34
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
