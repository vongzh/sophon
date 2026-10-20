import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// ---------------------------------------------------------------------------
// 签名配置：优先使用 CI 环境变量 / local.properties，其次自动降级为仓库内置的
// keystore/release.jks。未提供任何密钥时保持未签名，绝不阻断编译构建。
// ---------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(propName)?.takeIf { it.isNotBlank() }

val customKeystorePath = signingValue("SOPHON_KEYSTORE_PATH", "sophon.keystore.path")
val defaultKeystore = rootProject.file("keystore/release.jks")
val releaseKeystoreFile = when {
    customKeystorePath != null && file(customKeystorePath).exists() -> file(customKeystorePath)
    defaultKeystore.exists() -> defaultKeystore
    else -> null
}
val hasReleaseSigning = releaseKeystoreFile != null

android {
    namespace = "com.vongzh.sophon"
    compileSdk = 35

    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = signingValue("SOPHON_KEYSTORE_PASSWORD", "sophon.keystore.password") ?: "android"
                keyAlias = signingValue("SOPHON_KEY_ALIAS", "sophon.key.alias") ?: "apkeasytool"
                keyPassword = signingValue("SOPHON_KEY_PASSWORD", "sophon.key.password") ?: "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.vongzh.sophon"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

    // LAN Share & QR Code
    implementation(libs.nanohttpd)
    implementation(libs.zxing)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
