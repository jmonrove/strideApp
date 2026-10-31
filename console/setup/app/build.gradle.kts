plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.stride.setup"
    compileSdk = 34
    defaultConfig { applicationId = "dev.stride.setup"; minSdk = 22; targetSdk = 28; versionCode = 1; versionName = "0.1.0" }
    flavorDimensions += "payload"
    productFlavors {
        create("core") { dimension = "payload"; versionNameSuffix = "-core" }
        create("bundle") { dimension = "payload"; versionNameSuffix = "-bundle" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/setupAssets"))
    sourceSets["bundle"].assets.srcDir(rootProject.file("local/assets"))
    androidResources { noCompress += listOf("apk", "bin") }
    lint { disable += "ExpiredTargetSdkVersion" }
}
val setupAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../../tools/android5")) { include("README.md", "enable-wifi-adb.sh") }
    into(layout.buildDirectory.dir("generated/setupAssets"))
}
tasks.named("preBuild").configure { dependsOn(setupAssets) }
tasks.matching { it.name == "preBundleDebugBuild" || it.name == "preBundleReleaseBuild" }.configureEach {
    doFirst {
        for (path in listOf("payloads.json", "payloads/termux.apk", "payloads/termux-source.bin", "TERMUX-NOTICES.txt", "TERMUX-LICENSE.md", "GPL-3.0.txt")) {
            check(rootProject.file("local/assets/$path").isFile) { "Missing $path: run prepare_bundle.py before building the bundle" }
        }
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
