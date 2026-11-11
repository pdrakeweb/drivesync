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
    // Tink: authenticated encryption (XChaCha20-Poly1305). The tink-android artifact is a plain
    // jar with its protobuf dependency shaded, so it serves JVM tests and Android alike.
    implementation(libs.tink)
    // Bouncy Castle: Argon2id, the memory-hard passphrase derivation. Neither the JDK nor Tink has one.
    implementation(libs.bouncycastle)

    testImplementation(project(":testing"))
    testImplementation(libs.serialization.json)
    testRuntimeOnly(libs.junit.launcher)
}
tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
