import java.util.Base64

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

// The signing key is stored as text and decoded at build time, so every build is signed the
// same way and a new APK can install over the old one (this is what makes in-app updates work).
val debugKeystoreFile = File(layout.buildDirectory.get().asFile, "debug.keystore")
File(rootDir, "debug.keystore.b64").takeIf { it.exists() }?.let {
    debugKeystoreFile.parentFile.mkdirs()
    debugKeystoreFile.writeBytes(Base64.getMimeDecoder().decode(it.readText().trim()))
}
// The release number comes from the GitHub run number (-Pvc=...), so it always goes up.
val vc = (project.findProperty("vc") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.rigen.gallery"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rigen.gallery"
        minSdk = 29
        targetSdk = 35
        versionCode = vc
        versionName = "0.1.$vc"
        buildConfigField("String", "UPDATE_REPO", "\"${project.findProperty("updateRepo") ?: ""}\"")
    }
    signingConfigs {
        getByName("debug") {
            if (debugKeystoreFile.exists()) {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
