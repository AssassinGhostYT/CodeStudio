import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

// Published as part of the plugin SPI (see plugin-api): a plugin's `compileOnly` dependency on plugin-api
// pulls platform-core transitively, so both must be resolvable at the same SPI version.
version = "1.0.0"

// platform-core depends on no other module (no domain knowledge). Coroutines back the activity
// engine / dispatchers and are an internal implementation detail — not exposed in the public API —
// so they are `implementation`, not `api`.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
