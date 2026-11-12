plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "io.github.pdrakeweb.drivesync.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
kotlin {
    explicitApi()
}
dependencies {
    api(project(":core"))
    api(libs.work.runtime)
    implementation(libs.lifecycle.process)

    testImplementation(project(":testing"))
    testImplementation(libs.work.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
