plugins {
    alias(libs.plugins.android.application)
}

import java.util.Properties

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "edu.northeastern.agent_a"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "edu.northeastern.agent_a"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val minimaxApiKey = localProperties.getProperty("MINIMAX_API_KEY", "")
        val minimaxBaseUrl = localProperties.getProperty("MINIMAX_BASE_URL", "https://api.minimaxi.com/v1")
        val minimaxModel = localProperties.getProperty("MINIMAX_MODEL", "MiniMax-M2.1")

        buildConfigField("String", "MINIMAX_API_KEY", "\"$minimaxApiKey\"")
        buildConfigField("String", "MINIMAX_BASE_URL", "\"$minimaxBaseUrl\"")
        buildConfigField("String", "MINIMAX_MODEL", "\"$minimaxModel\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
