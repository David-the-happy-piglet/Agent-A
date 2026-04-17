import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "edu.northeastern.agent_a"
    compileSdk = 36

    defaultConfig {
        applicationId = "edu.northeastern.agent_a"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val minimaxApiKey = localProperties.getProperty("MINIMAX_API_KEY", "")
        val minimaxBaseUrl = localProperties.getProperty("MINIMAX_BASE_URL", "https://api.minimax.io/v1/text/chatcompletion_v2")
        val minimaxModel = localProperties.getProperty("MINIMAX_MODEL", "M2-her")
        val spotifyClientId = localProperties.getProperty("SPOTIFY_CLIENT_ID", "")
        val spotifyRedirectUri = localProperties.getProperty("SPOTIFY_REDIRECT_URI", "agenta://spotify-auth")
        val googleClientId = localProperties.getProperty("GOOGLE_CLIENT_ID", "")

        buildConfigField("String", "MINIMAX_API_KEY", "\"$minimaxApiKey\"")
        buildConfigField("String", "MINIMAX_BASE_URL", "\"$minimaxBaseUrl\"")
        buildConfigField("String", "MINIMAX_MODEL", "\"$minimaxModel\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProperties.getProperty("GEMINI_API_KEY", "")}\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"$spotifyRedirectUri\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClientId\"")
    }

    buildFeatures {
        buildConfig = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.biometric)
    implementation(libs.glide)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Google Services & APIs
    implementation(libs.play.services.auth)
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.gmail)
    implementation(libs.google.http.client.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
