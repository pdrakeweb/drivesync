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
    api(libs.coroutines.core)
    api(libs.serialization.cbor)
    implementation(libs.serialization.json)

    testImplementation(project(":testing"))
    testImplementation(libs.kotest.property)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
tasks.test { useJUnitPlatform() }

// Guard (CLAUDE.md working rules): :core stays free of Android, Room and network types.
// Checks both what :core depends on and what its compiled classes reference.
val checkCorePurity = tasks.register("checkCorePurity") {
    group = "verification"
    description = "Fails if :core depends on or references Android, Room or network types."
    dependsOn(tasks.named("classes"))
    val classDirs = sourceSets.main.get().output.classesDirs
    val runtime = configurations.runtimeClasspath
    inputs.files(classDirs)
    doLast {
        val allowedGroups = setOf("org.jetbrains", "org.jetbrains.kotlin", "org.jetbrains.kotlinx")
        val forbidden = listOf(
            "android/", "androidx/", "java/net/", "javax/net/", "java/sql/",
            "okhttp3/", "retrofit2/", "io/ktor/", "com/google/",
        )
        val problems = ArrayList<String>()
        runtime.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .filter { it.group !in allowedGroups }
            .forEach { problems += "dependency ${it.group}:${it.name}:${it.version}" }
        classDirs.asFileTree.matching { include("**/*.class") }.forEach { file ->
            // Type references appear in the constant pool as L<name>; or as bare internal names.
            val text = file.readBytes().toString(Charsets.ISO_8859_1)
            forbidden.filter { p -> Regex("(^|[^A-Za-z0-9_/$])L?" + Regex.escape(p)).containsMatchIn(text) }
                .forEach { problems += "${file.name} references $it" }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(":core must stay pure Kotlin/JVM:\n  " + problems.distinct().joinToString("\n  "))
        }
    }
}
tasks.named("check") { dependsOn(checkCorePurity) }
