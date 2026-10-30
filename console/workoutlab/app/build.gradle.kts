plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.stride.lab"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.stride.lab"
        minSdk = 22
        targetSdk = 28
        versionCode = 1
        versionName = "0.1-preview"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        // Sideloaded console diagnostic; this is not a Google Play submission.
        disable += "ExpiredTargetSdkVersion"
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
