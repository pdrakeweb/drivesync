plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "io.github.pdrakeweb.drivesync.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
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
    // The Keystore-backed key storage implements :crypto's KeyStorage.
    api(project(":crypto"))
    // QR rendering for the join code. Pure Java, no Android or network code.
    implementation(libs.zxing.core)
    // Credential Manager: saving and fetching the passphrase through the person's password manager.
    api(libs.androidx.credentials)
    // Lets Credential Manager reach Google Password Manager on Android 13 and older.
    implementation(libs.androidx.credentials.play)

    testImplementation(project(":testing"))
    testImplementation(libs.work.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
