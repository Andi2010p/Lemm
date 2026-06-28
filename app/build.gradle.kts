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
    namespace = "com.example.lemm"
    compileSdk = 36

    defaultConfig {
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
    // Google Sign-In Library
    implementation("com.google.android.gms:play-services-auth:21.1.1")
    // Google Generative AI
    implementation("com.google.ai.client.generativeai:generativeai:0.7.0")
    // ML Kit & Text Recognition
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    // Mail Sending (OTP)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("com.android.billingclient:billing:6.1.0")
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