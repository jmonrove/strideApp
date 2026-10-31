plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.stride.motorbench"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.stride.motorbench"
        minSdk = 22
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-simulator"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { disable += "ExpiredTargetSdkVersion" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
