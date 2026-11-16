import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "io.github.pdrakeweb"
    version = "0.1.0-SNAPSHOT"
}

// Publishing (D-009, D-021). Every library module publishes; :sample is an application and does not.
// Project dependencies between modules (for example :crypto's api(project(":core"))) are written
// into the POM and Gradle module metadata as the drivesync-* coordinates of the module they point at.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        // Also names the Kotlin module file (META-INF/io.github.pdrakeweb_drivesync-core.kotlin_module),
        // so it cannot clash with another io.github.pdrakeweb library's module called "core" or "testing".
        extensions.configure<BasePluginExtension> { archivesName.set("drivesync-${project.name}") }

        apply(plugin = "maven-publish")
        extensions.configure<JavaPluginExtension> { withSourcesJar() }
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(components["java"])
                describe(project)
            }
        }
    }

    // The Android libraries publish their release variant as an AAR (Android Archive: classes plus
    // manifest and resources).
    plugins.withId("com.android.library") {
        apply(plugin = "maven-publish")
        extensions.configure<LibraryExtension> {
            publishing { singleVariant("release") { withSourcesJar() } }
        }
        // AGP creates the "release" software component only once the project is evaluated.
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("maven") {
                    from(components["release"])
                    describe(project)
                }
            }
        }
    }
}

/** Coordinates: `io.github.pdrakeweb:drivesync-<module>:<version>`. */
fun MavenPublication.describe(project: Project) {
    artifactId = "drivesync-${project.name}"
    pom {
        name.set("drivesync-${project.name}")
        description.set("drivesync: local-first sync for Android apps through storage the user owns, end-to-end encrypted, no server.")
        url.set("https://github.com/pdrakeweb/drivesync")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
    }
}
