import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

// The SPI is versioned on its own, independently of the IDE's release: a plugin compiled against it has to
// keep resolving when the IDE moves on. Keep in sync with PLUGIN_SPI_VERSION in PluginManifest.kt.
version = "1.0.0"

// plugin-api — the SPI for UI-contributed plugin features.
//
// The lean action model (IdeAction / ActionGroup + named places) and the data-driven UI extension points,
// so a toolbar button, a menu item, or a command-palette command is a registration against an extension
// point rather than an edit to the host UI. Pure Kotlin: no Compose, no engine, no project model. The
// engine side (ide-core) registers built-ins here; the Compose UI renders them through neutral DTOs over
// the IdeBackend port, exactly like the settings framework.
dependencies {
    // ExtensionPoint/ExtensionRegistry, PluginId, Disposable — the EPs and the ids are part of the SPI.
    api(project(":platform-core"))

    // IdeAction.perform is suspend; tests drive it with runBlocking.
    testImplementation(libs.kotlinx.coroutines.core)
}

// `./gradlew :plugin-api:publishToMavenLocal` puts the SPI in the local Maven repository so a scaffolded
// plugin project resolves `dev.ide:plugin-api:1.0.0`. platform-core is an `api` dependency, so its own
// publication (below) has to be present too; its POM points plugin consumers at the matching coordinate.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
