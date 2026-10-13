plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vongz.sophon"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/release.jks")
            storePassword = "android"
            keyAlias = "apkeasytool"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.vongz.sophon"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-test-10131808"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.activity.ktx)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.cardview)

    // Coroutines & Lifecycle
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.service)

    // JSON & Network
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Image loading
    implementation(libs.glide)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
