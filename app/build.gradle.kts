import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
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

        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }
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
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation("com.google.ai.client.generativeai:generativeai:0.7.0")
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-auth")
    implementation(libs.google.generativeai)
    implementation("com.google.android.gms:play-services-auth:21.1.1")
    implementation(libs.guava)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.appcompat)
    implementation(libs.com.google.android.material)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")
    implementation(libs.play.services.mlkit.text.recognition)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test:runner:1.5.2")
    implementation(libs.mlkit.text.recognition)

}
