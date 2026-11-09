import java.util.Properties
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningFile = rootProject.file("../BassPlayer-release-signing.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningFile.exists()) releaseSigningFile.inputStream().use(::load)
}

android {
    namespace = "com.bassplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bassplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
    }

    signingConfigs {
        create("release") {
            val keyPath = releaseSigningProperties.getProperty("storeFile")
            if (!keyPath.isNullOrBlank()) {
                val configuredFile = File(keyPath)
                storeFile = if (configuredFile.isAbsolute) configuredFile
                else File(releaseSigningFile.parentFile, keyPath)
            }
            storePassword = releaseSigningProperties.getProperty("storePassword", "")
            keyAlias = releaseSigningProperties.getProperty("keyAlias", "BassPlayerRelease")
            keyPassword = releaseSigningProperties.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            if (releaseSigningFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")

    testImplementation("junit:junit:4.13.2")
}

