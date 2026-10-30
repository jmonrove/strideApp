plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.stride.lab"
    compileSdk = 34
    useLibrary("android.test.runner", false)
    useLibrary("android.test.base", false)
    useLibrary("android.test.mock", false)
    defaultConfig {
        applicationId = "dev.stride.lab"
        minSdk = 22
        targetSdk = 28
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        versionCode = 3
        versionName = "0.2.1-console-preview"
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
