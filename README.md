# drivesync

Local-first sync for Android apps through storage the user owns. Every device works offline;
changes merge deterministically; the cloud copy is end-to-end encrypted; there is no server.

**Status:** in development, not released. S0 to S4 are built and proven, including sync through a real Google Drive account; see `docs/ROADMAP.md`. Since S4: identity reset for a restored database, change of passphrase, join by code in the sample. Not built: key rotation, attachments, schema annotations, the 0.1.0 release.

Build and test: `./gradlew build` (JDK 17+). The long simulation: `./gradlew :testing:test -PsimRuns=10000`.

## Use it from another project

Not on Maven Central yet. Publish to Maven Local (the `~/.m2/repository` folder on your machine)
from a checkout of this repository, and again after pulling newer changes:

```bash
./gradlew publishToMavenLocal
```

In the consuming project's `settings.gradle.kts`, add Maven Local for this group only:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("io.github.pdrakeweb") } }
        google()
        mavenCentral()
    }
}
```

Then depend on the modules you need, for example in `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.pdrakeweb:drivesync-core:0.1.0-SNAPSHOT")
    implementation("io.github.pdrakeweb:drivesync-room:0.1.0-SNAPSHOT")
    implementation("io.github.pdrakeweb:drivesync-android:0.1.0-SNAPSHOT")       // also brings drivesync-crypto
    implementation("io.github.pdrakeweb:drivesync-backend-drive:0.1.0-SNAPSHOT")

    testImplementation("io.github.pdrakeweb:drivesync-testing:0.1.0-SNAPSHOT")
}
```

| Artifact (`io.github.pdrakeweb:…:0.1.0-SNAPSHOT`) | Packaging | What it is |
|---|---|---|
| `drivesync-core` | jar | Pure Kotlin: clock, change model, merge engine, sync cycle, `BlobStore`/`LocalStore`/`KeyManager` interfaces |
| `drivesync-crypto` | jar | Encryption (Tink) and passphrase derivation (Argon2id); the `KeyManager` implementation |
| `drivesync-room` | AAR (Android library) | `LocalStore` for Room/SQLite: trigger-based change capture and apply |
| `drivesync-android` | AAR | WorkManager scheduler, network monitor, Keystore key storage, QR join code, password manager vault |
| `drivesync-backend-drive` | jar | `BlobStore` over the Google Drive app-data folder; the app supplies the OAuth token |
| `drivesync-backend-local` | jar | `BlobStore` over a directory (tests, manual transfer, development) |
| `drivesync-testing` | jar | In-memory `BlobStore` with fault injection, simulator, JUnit 5 contract suites |

Each artifact ships a sources jar and Gradle module metadata, so dependencies between the modules
and on third-party libraries come through transitively. The Android modules publish their release
variant. `:sample` is not published.

| Doc | What it answers |
|---|---|
| [`docs/DESIGN.md`](docs/DESIGN.md) | The problem, goals, prior art (including Property Manager), merge rules, storage layout, keys |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Modules, interfaces, wire formats, Drive specifics, testing, release |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | Milestones S0–S4 and what proves each |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | What is settled and who settled it |
| [`docs/handoff/IMPLEMENT.md`](docs/handoff/IMPLEMENT.md) | Instructions for the implementation session |
| [`docs/PASSPHRASE_AND_PASSWORD_MANAGERS.md`](docs/PASSPHRASE_AND_PASSWORD_MANAGERS.md) | How an app should let a password manager suggest, save and restore the sync passphrase |
| [`tests/README.md`](tests/README.md) | Device scenarios: two emulators through a local folder and through Google Drive |

Apache 2.0.
