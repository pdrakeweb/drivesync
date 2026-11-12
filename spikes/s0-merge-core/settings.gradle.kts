// S0 spike: standalone build so JitPack never enters the library build.
// Run from the repo root:  ./gradlew -p spikes/s0-merge-core test
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.[Cc]harlie[Tt]ap.*") }
        }
    }
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
rootProject.name = "s0-merge-core"
