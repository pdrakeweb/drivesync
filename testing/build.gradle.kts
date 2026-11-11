plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.serialization.json)
    // The contract suites are abstract JUnit classes that other modules extend.
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)

    testRuntimeOnly(libs.junit.launcher)
}
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // 500 simulated runs per build; the nightly job and the S1 proof pass -PsimRuns=10000.
    systemProperty("drivesync.simRuns", providers.gradleProperty("simRuns").getOrElse("500"))
    testLogging { showStandardStreams = true }
}
