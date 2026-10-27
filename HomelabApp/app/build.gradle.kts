import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 实例地址不写进仓库：在 local.properties 里加 homarr.url=https://xxx.ts.net 即可作为默认值预填。
// local.properties 已被 .gitignore 排除，每台开发机自己维护。
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val defaultHomarrUrl = localProperties.getProperty("homarr.url").orEmpty().trim()

android {
    namespace = "com.poi.homelab"
    compileSdk {
        version = release(37)
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.poi.homelab"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "DEFAULT_HOMARR_URL", "\"$defaultHomarrUrl\"")
    }

    signingConfigs {
        create("local") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("local")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("local")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Homarr 看板磁贴图标（PNG/SVG），统一走本地 ImageLoader，不依赖 CDN 之外的 WebView
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.adk:google-adk-kotlin-core-android:1.4.0")
    implementation("com.google.adk:google-adk-kotlin-integrations-openai:1.4.0")
    ksp("com.google.adk:google-adk-kotlin-processor:1.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
