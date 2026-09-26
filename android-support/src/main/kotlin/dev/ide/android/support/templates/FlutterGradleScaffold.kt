package dev.ide.android.support.templates

import dev.ide.model.template.ProjectScaffold

/**
 * The Gradle skeleton a Flutter Android module needs, written when the project is created.
 *
 * This is the Flutter counterpart of [GradleScaffold], and it exists because of a bug that was invisible
 * until it was not: the template wrote the *modern* manifest at `android/app/src/main/AndroidManifest.xml`
 * but never `android/build.gradle.kts`. The tool decides which manifest to read by layout —
 * `hostAppGradleFile` (android/build.gradle[.kts]) decides `appManifestFile` — so without the root script
 * every project CodeStudio created was classified as the legacy layout, the tool looked for
 * `android/AndroidManifest.xml`, which the template also does not write, and a freshly created project
 * started life classified as v1 embedding. The build could then only be rescued by a repair pass at compile
 * time, which is a terrible place to discover a project is incomplete.
 *
 * So a new project is complete the moment it is created, like the Kotlin/Android/Compose templates already
 * are. These versions are the ones `flutter create` pins for current stable, and the compile-time repair
 * still fills in anything missing, so a project whose SDK disagrees about a version is repaired rather
 * than blocked.
 */
object FlutterGradleScaffold {

    private const val AGP_VERSION = "8.7.3"
    private const val KGP_VERSION = "2.1.0"
    private const val GRADLE_DISTRIBUTION = "8.12"

    /** Write `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` and the wrapper descriptor. */
    fun writeRootFiles(scaffold: ProjectScaffold, androidDir: String, projectName: String) {
        scaffold.writeText(
            "$androidDir/settings.gradle.kts",
            """
            pluginManagement {
                // The Flutter SDK's Gradle plugin is built from source, so it is included as a composite build
                // resolved from local.properties — the same contract `flutter create` writes, which is what
                // lets the tool and this project's scripts agree on the plugin loader.
                val flutterSdkPath = run {
                    val properties = java.util.Properties()
                    file("local.properties").inputStream().use { properties.load(it) }
                    val flutterSdkPath = properties.getProperty("flutter.sdk")
                    require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
                    flutterSdkPath
                }
                includeBuild("${'$'}flutterSdkPath/packages/flutter_tools/gradle")

                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }

            plugins {
                id("dev.flutter.flutter-plugin-loader") version "1.0.0"
                id("com.android.application") version "$AGP_VERSION" apply false
                id("org.jetbrains.kotlin.android") version "$KGP_VERSION" apply false
            }

            include(":app")
            rootProject.name = "$projectName"
            """,
        )

        scaffold.writeText(
            "$androidDir/build.gradle.kts",
            """
            allprojects {
                repositories {
                    google()
                    mavenCentral()
                }
            }

            val newBuildDir: Directory = rootProject.layout.buildDirectory.dir("../../build").get()
            rootProject.layout.buildDirectory.value(newBuildDir)

            subprojects {
                val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
                project.layout.buildDirectory.value(newSubprojectBuildDir)
            }
            """,
        )

        scaffold.writeText(
            "$androidDir/gradle.properties",
            """
            org.gradle.jvmargs=-Xmx4G -XX:MaxMetaspaceSize=2G
            android.useAndroidX=true
            android.enableJetifier=false
            """,
        )

        scaffold.writeText(
            "$androidDir/gradle/wrapper/gradle-wrapper.properties",
            """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-$GRADLE_DISTRIBUTION-bin.zip
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
            """,
        )
    }

    /**
     * Write `app/build.gradle.kts`. The Flutter plugin is what wires the Dart sources into the APK, so this
     * module is not interchangeable with [GradleScaffold.writeAppModule]: it builds the APK by invoking
     * `flutter assemble`, and the Dart entry point is a project property.
     */
    fun writeAppModule(scaffold: ProjectScaffold, androidDir: String, namespace: String, minSdk: Int = 21) {
        scaffold.writeText(
            "$androidDir/app/build.gradle.kts",
            """
            plugins {
                id("com.android.application")
                id("kotlin-android")
                id("dev.flutter.flutter-gradle-plugin")
            }

            android {
                namespace = "$namespace"
                compileSdk = flutter.compileSdkVersion
                ndkVersion = flutter.ndkVersion

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                kotlinOptions {
                    jvmTarget = JavaVersion.VERSION_17.toString()
                }

                defaultConfig {
                    applicationId = "$namespace"
                    minSdk = $minSdk
                    targetSdk = flutter.targetSdkVersion
                    versionCode = flutter.versionCode
                    versionName = flutter.versionName
                }

                buildTypes {
                    release {
                        // A debug-signed release so `flutter build apk --release` produces an installable APK
                        // before the user has set up their own signing config.
                        signingConfig = signingConfigs.getByName("debug")
                    }
                }
            }

            flutter {
                source = "../.."
            }
            """,
        )
    }
}
