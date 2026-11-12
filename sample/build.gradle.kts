plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "io.github.pdrakeweb.drivesync.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "io.github.pdrakeweb.drivesync.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":room"))
    implementation(project(":android"))
}
