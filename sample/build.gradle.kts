plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":room"))
    implementation(project(":android"))
    implementation(project(":backend-local"))
    implementation(project(":crypto"))
    implementation(project(":backend-drive"))
    // Google Identity Services: asks the person to let this app use its own hidden Drive folder.
    implementation(libs.play.services.auth)
    implementation(libs.play.services.code.scanner)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
}
