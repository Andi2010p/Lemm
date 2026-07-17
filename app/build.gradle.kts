// D:/codes/Homeworks.Uwc/Lemm/app/build.gradle.kts

import java.util.Properties

plugins {
    // Remove the 'id("com.android.application")' line
    // Keep the Kotlin Android plugin
    alias(libs.plugins.android.application) // <-- Keep this one, it uses the version catalog
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services) // <--- THIS MUST BE HERE
    // Add the Google services Gradle plugin
}

val localProperties = Properties()
val localPropertiesFile = project.rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}
android {
    // namespace = the Java package — internal only, Play never sees it. Leave it alone.
    namespace = "com.example.lemm"
    compileSdk = 36

    defaultConfig {
        // ⚠️ RELEASE BLOCKER — Play REJECTS any applicationId starting with "com.example".
        // Change this to  io.github.andi2010p.lemma  as the LAST step before your first upload, at the
        // same time as registering that package in the Firebase console (Firebase matches
        // google-services.json on the applicationId, so the build fails until the new json is in app/).
        // This id is PERMANENT once published — it can never be changed. See RELEASE_CHECKLIST.md.
        applicationId = "com.example.lemm"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Read the key from the properties object we loaded above
        val geminiApiKey = localProperties.getProperty("GEMINI_API_KEY") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")

        // Comma-separated backup keys used as fallback when the primary key is out of quota.
        val geminiBackupKeys = localProperties.getProperty("GEMINI_BACKUP_KEYS") ?: ""
        buildConfigField("String", "GEMINI_BACKUP_KEYS", "\"$geminiBackupKeys\"")

        // Transactional-email (SMTP) credentials — kept OUT of source. Put these in local.properties:
        //   MAIL_USER=lemmaofficial13@gmail.com
        //   MAIL_APP_PASSWORD=xxxx xxxx xxxx xxxx   (a Google "App Password", no quotes)
        val mailUser = localProperties.getProperty("MAIL_USER") ?: ""
        buildConfigField("String", "MAIL_USER", "\"$mailUser\"")
        val mailAppPassword = (localProperties.getProperty("MAIL_APP_PASSWORD") ?: "").trim()
        buildConfigField("String", "MAIL_APP_PASSWORD", "\"$mailAppPassword\"")

    }
    buildFeatures {
        buildConfig = true
    }

    // Release signing. Provide these in local.properties (kept out of source control):
    //   RELEASE_STORE_FILE=../keystore/lemma-release.jks
    //   RELEASE_STORE_PASSWORD=...
    //   RELEASE_KEY_ALIAS=lemma
    //   RELEASE_KEY_PASSWORD=...
    // Generate one with:
    //   keytool -genkey -v -keystore lemma-release.jks -alias lemma -keyalg RSA -keysize 2048 -validity 10000
    val hasReleaseKeystore = localProperties.getProperty("RELEASE_STORE_FILE")?.isNotBlank() == true
    if (!hasReleaseKeystore) {
        logger.warn(
            "\n⚠️  RELEASE SIGNING: no RELEASE_STORE_FILE in local.properties.\n" +
            "    The release build will be signed with the DEBUG key.\n" +
            "    Google Play REJECTS debug-signed artifacts — do not upload this .aab.\n"
        )
    }
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(localProperties.getProperty("RELEASE_STORE_FILE"))
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use the real release key when provided; otherwise fall back to the debug key so the
            // release variant still builds locally/CI. NEVER publish a debug-signed APK to Play.
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        // NOTE: debug build keeps the base applicationId (com.example.lemm) so it matches the
        // Firebase google-services.json. Do NOT add an applicationIdSuffix without also adding that
        // package to Firebase, or the google-services plugin will fail the build.
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
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE.txt"
        }
    }
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    // Android Core & UI
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")

    implementation(platform(libs.firebase.bom))
    // Geometry/CAD Engine
    implementation("org.locationtech.jts:jts-core:1.19.0")

    // Firebase products - versions are now managed by the firebase-bom
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-auth")
    // Callable Cloud Functions: the AI proxy, OTP email, and every consent-checked social write.
    implementation("com.google.firebase:firebase-functions")
    // Cloud Storage: chat photos, voice notes and files (bytes live here, only the URL goes in the DB).
    implementation("com.google.firebase:firebase-storage")
    // App Check + Play Integrity: proves a request came from a genuine, unmodified Lemma install.
    // Without it, anyone can pull google-services.json out of the APK and talk to the backend
    // with a script — security rules cannot tell your app from curl.
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    // Google Sign-In Library
    implementation("com.google.android.gms:play-services-auth:21.1.1")
    // App-lock: fingerprint / face unlock prompt with a PIN fallback.
    implementation("androidx.biometric:biometric:1.1.0")
    // Google Generative AI
    implementation("com.google.ai.client.generativeai:generativeai:0.7.0")
    // ML Kit & Text Recognition
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    // Mail Sending (OTP)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    // Play REQUIRES Billing Library 8+ for new apps/updates from 2026-08-31 (v6 is already below the
    // v7+ floor enforced since 2025-08). Keep this at 8.x or newer or Play will reject the upload.
    implementation("com.android.billingclient:billing:8.3.0")
    // Utilities
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.guava:guava:33.0.0-android")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // ViewModel + LiveData (used by the History MVVM layer) come transitively via appcompat/activity.

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
}