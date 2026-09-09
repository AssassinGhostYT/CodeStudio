package dev.ide.core

import dev.ide.testkit.withTempDir
import dev.ide.model.LanguageLevel
import dev.ide.model.template.TemplateArgs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reproduces the on-device "create a new project" flow for the Gradle-scaffolded templates (android-app and
 * its siblings): everything `createProjectAt` writes to disk must carry real content — especially the Gradle
 * files, which are the source of truth. A blank `settings.gradle.kts` / `build.gradle.kts` is a bug in
 * creation, not in the user's project.
 */
class GradleCreationTest {

    @Test
    fun androidAppWritesRealGradleContentToDisk() = withTempDir("gradle-app") { dir ->
        IdeServices.createProjectAt(
            dir,
            "android-app",
            mapOf(TemplateArgs.NAME to "MyApp", TemplateArgs.PACKAGE to "com.example.app"),
            IdeServices.defaultDesktopSdk(),
            LanguageLevel.JAVA_17,
        ).use {
            fun read(rel: String): String = Files.readString(dir.resolve(rel))
            val settings = read("settings.gradle.kts")
            assertTrue(settings.contains("include(\":app\")"), "settings.gradle.kts must be real content, got: ${settings.take(120)}")
            val rootBuild = read("build.gradle.kts")
            assertTrue(rootBuild.contains("com.android.application") && rootBuild.contains("8.2.2"), "root build.gradle.kts must pin AGP 8.2.2")
            val props = read("gradle.properties")
            assertTrue(props.contains("android.useAndroidX=true"), "gradle.properties must be populated")
            val wrapper = read("gradle/wrapper/gradle-wrapper.properties")
            assertTrue(wrapper.contains("gradle-8.5"), "wrapper must point at the distribution")
            val appBuild = read("app/build.gradle.kts")
            assertTrue(appBuild.contains("dependencies {"), "app/build.gradle.kts must have dependencies {}")
            assertTrue(appBuild.contains("implementation(\"androidx.appcompat:appcompat"), "app deps expected")
            assertTrue(appBuild.contains("compileSdk = 36"), "app must pin compileSdk")
            assertTrue(Files.exists(dir.resolve("app/src/main/AndroidManifest.xml")), "manifest required")
            assertTrue(Files.exists(dir.resolve("app/src/main/java/com/example/app/MainActivity.java")), "activity required")
        }
    }

    @Test
    fun composeAppCarriesTheComposeCoordinates() = withTempDir("gradle-compose") { dir ->
        IdeServices.createProjectAt(
            dir,
            "compose-app",
            mapOf(TemplateArgs.NAME to "ComposeApp", TemplateArgs.PACKAGE to "com.example.app"),
            IdeServices.defaultDesktopSdk(),
            LanguageLevel.JAVA_17,
        ).use {
            val appBuild = Files.readString(dir.resolve("app/build.gradle.kts"))
            assertTrue(appBuild.contains("compose-bom:2024.05.00"), "compose BOM expected, got: ${appBuild.take(200)}")
            assertTrue(appBuild.contains("androidx.activity:activity-compose:1.9.0"), "activity-compose expected")
            assertTrue(appBuild.contains("composeOptions"), "composeOptions block expected")
        }
    }
}