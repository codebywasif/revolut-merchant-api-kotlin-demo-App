plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.revolutdemo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.revolutdemo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // === Revolut sandbox keys ===
        // SECRET key — only here because this is a single-page sandbox demo.
        // Move to a backend before going to production.
        buildConfigField(
            "String",
            "REVOLUT_SECRET_KEY",
            "\"sk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\""
        )
        // PUBLIC key — paste the pk_... key from the same sandbox API page.
        buildConfigField(
            "String",
            "REVOLUT_PUBLIC_KEY",
            "\"pk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\""
        )
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Revolut Merchant Card Form SDK
    implementation("com.revolut.payments:merchantcardform:3.1.2")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
