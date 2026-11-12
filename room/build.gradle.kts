plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "io.github.pdrakeweb.drivesync.room"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.logging", "stderr") }
        }
    }
}
kotlin {
    explicitApi()
}
dependencies {
    api(project(":core"))
    api(libs.androidx.sqlite)

    testImplementation(project(":testing"))
    testImplementation(libs.androidx.sqlite.framework)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
