plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.stride.usbprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.stride.usbprobe"
        minSdk = 22
        // Sideloaded on Android 5.1 and 9 consoles; retain the original target
        // so newer USB permission behavior does not change during this port.
        targetSdk = 28
        versionCode = 2
        versionName = "0.2-query-probe"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // This diagnostic is sideloaded, not submitted to Google Play.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Framework-only runtime; JUnit exercises the isolated query/transport policy.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
