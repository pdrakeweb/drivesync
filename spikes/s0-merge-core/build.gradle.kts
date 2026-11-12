plugins {
    alias(libs.plugins.kotlin.jvm)
}

// -PforceDatetime=<version> resolves a newer kotlinx-datetime, as a consuming app might.
val forceDatetime: String? = providers.gradleProperty("forceDatetime").orNull
configurations.all {
    if (forceDatetime != null) {
        resolutionStrategy.force(
            "org.jetbrains.kotlinx:kotlinx-datetime:$forceDatetime",
            "org.jetbrains.kotlinx:kotlinx-datetime-jvm:$forceDatetime",
        )
    }
}

dependencies {
    testImplementation("com.github.CharlieTap.synk:synk-jvm:0.38")
    testImplementation("com.squareup.okio:okio-fakefilesystem:3.4.0")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    systemProperty("s0.seeds", providers.gradleProperty("seeds").getOrElse("10000"))
    systemProperty("s0.report", layout.buildDirectory.file("s0-measurements.txt").get().asFile.path)
    testLogging {
        showStandardStreams = true
        events("failed")
    }
    outputs.upToDateWhen { false }
}

// Prints the runtime jars Synk brings with it (test tooling excluded).
tasks.register("footprint") {
    val classpath = configurations.named("testRuntimeClasspath")
    doLast {
        val tooling = listOf("org.junit", "org.opentest4j", "org.apiguardian", "org.jspecify")
        val jars = classpath.get().resolvedConfiguration.resolvedArtifacts.filterNot { a ->
            tooling.any { a.moduleVersion.id.group.startsWith(it) } || a.name.contains("fakefilesystem")
        }
        jars.sortedBy { it.moduleVersion.id.toString() }
            .forEach { println("${it.moduleVersion.id}  ${it.file.length() / 1024} KB") }
        println("TOTAL ${jars.size} jars, ${jars.sumOf { it.file.length() } / 1024} KB")
    }
}
