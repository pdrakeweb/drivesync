plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
    explicitApi()
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.serialization.json)

    testImplementation(project(":testing"))
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.launcher)
}
tasks.test {
    useJUnitPlatform()
    // Opt-in run against a real Google account: -PdriveToken=<OAuth access token with drive.appdata>.
    systemProperty("drivesync.driveToken", providers.gradleProperty("driveToken").getOrElse(""))
    testLogging { showStandardStreams = true }
}
