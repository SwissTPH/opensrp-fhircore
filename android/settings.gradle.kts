pluginManagement {
    repositories {
        google()
        maven(url = "https://plugins.gradle.org/m2/")
        maven(url = "https://oss.sonatype.org/content/repositories/snapshots/")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
rootProject.name = "fhircore-android"
include (":engine")
include (":quest")
include(":linting")
include(":macrobenchmark")
