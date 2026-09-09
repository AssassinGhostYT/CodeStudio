package dev.ide.android.support.templates

import dev.ide.model.template.ProjectScaffold

/**
 * The classic Gradle project skeleton every real Android project carries — `settings.gradle.kts`,
 * a root `build.gradle.kts`, `gradle.properties` and the Gradle wrapper descriptor. Written by the
 * app templates so a project created in CodeStudio is a normal Gradle folder (the same shape Android
 * Studio / AndroidIDE produce), where the scripts are the source of truth and the importer derives
 * the model from them.
 *
 * The generated scripts are intentionally plain: repositories are Google + Maven Central, the AGP/KGP
 * versions come from the single root `plugins` block, and the Gradle wrapper points at a recent
 * distribution. Everything is readable and editable like a normal Android project.
 */
internal object GradleScaffold {

    const val AGP_VERSION = "8.2.2"
    const val KGP_VERSION = "1.9.24"
    const val COMPOSE_COMPILER_EXTENSION = "1.5.14"
    const val GRADLE_DISTRIBUTION = "8.5"

    private const val DEFAULT_JDK = "17"

    /** Options for the generated app module's `build.gradle.kts`. */
    data class AppOptions(
        val module: String = "app",
        val namespace: String,
        val minSdk: Int,
        val targetSdk: Int,
        val compileSdk: Int,
        val kotlin: Boolean,
        val compose: Boolean,
    )

    /** Write the root-level Gradle files: settings, build, properties, and the wrapper descriptor. */
    fun writeRootFiles(scaffold: ProjectScaffold, projectName: String) {
        scaffold.writeText(
            "settings.gradle.kts",
            """
            import org.gradle.api.initialization.resolve.RepositoriesMode

            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }

            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }

            rootProject.name = "$projectName"
            include(":app")
            """,
        )

        scaffold.writeText(
            "build.gradle.kts",
            """
            // Top-level build file where you can add configuration options common to all sub-projects/modules.
            plugins {
                id("com.android.application") version "$AGP_VERSION" apply false
                id("com.android.library") version "$AGP_VERSION" apply false
                id("org.jetbrains.kotlin.android") version "$KGP_VERSION" apply false
            }

            tasks.register<Delete>("clean") {
                delete(rootProject.layout.buildDirectory)
            }
            """,
        )

        scaffold.writeText(
            "gradle.properties",
            """
            # Project-wide Gradle settings.
            org.gradle.jvmargs=-Xmx512m -Dfile.encoding=UTF-8
            # When configured, Gradle will run in incubating parallel mode.
            org.gradle.parallel=true
            android.useAndroidX=true
            android.nonTransitiveRClass=true
            kotlin.code.style=official
            """,
        )

        scaffold.writeText(
            "gradle/wrapper/gradle-wrapper.properties",
            """
            # Gradle wrapper configuration — the distribution the wrapper would download.
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\\://services.gradle.org/distributions/gradle-$GRADLE_DISTRIBUTION-bin.zip
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
            """,
        )
    }

    /** Write `app/build.gradle.kts` for the generated module, with coordinates in `dependencies {}`. */
    fun writeAppModule(scaffold: ProjectScaffold, app: AppOptions) {
        val sb = StringBuilder()

        fun line(indent: Int, text: String) {
            sb.append(" ".repeat(indent)).append(text).append('\n')
        }
        fun blank() = sb.append('\n')

        line(0, "plugins {")
        line(4, "id(\"com.android.application\")")
        if (app.kotlin) line(4, "id(\"org.jetbrains.kotlin.android\")")
        line(0, "}")
        blank()

        line(0, "android {")
        line(4, "namespace = \"${app.namespace}\"")
        line(4, "compileSdk = ${app.compileSdk}")
        blank()
        line(4, "defaultConfig {")
        line(8, "applicationId = \"${app.namespace}\"")
        line(8, "minSdk = ${app.minSdk}")
        line(8, "targetSdk = ${app.targetSdk}")
        line(8, "versionCode = 1")
        line(8, "versionName = \"1.0\"")
        line(4, "}")
        blank()
        line(4, "buildTypes {")
        line(8, "release {")
        line(12, "isMinifyEnabled = false")
        line(12, "proguardFiles(")
        line(16, "getDefaultProguardFile(\"proguard-android-optimize.txt\"),")
        line(16, "\"proguard-rules.pro\",")
        line(12, ")")
        line(8, "}")
        line(4, "}")
        blank()
        line(4, "compileOptions {")
        line(8, "sourceCompatibility = JavaVersion.VERSION_$DEFAULT_JDK")
        line(8, "targetCompatibility = JavaVersion.VERSION_$DEFAULT_JDK")
        line(4, "}")
        if (app.kotlin) {
            line(4, "kotlinOptions {")
            line(8, "jvmTarget = \"$DEFAULT_JDK\"")
            line(4, "}")
        }
        line(4, "buildFeatures {")
        if (app.compose) {
            line(8, "compose = true")
        } else {
            line(8, "viewBinding = true")
        }
        line(4, "}")
        if (app.compose) {
            line(4, "composeOptions {")
            line(8, "kotlinCompilerExtensionVersion = \"$COMPOSE_COMPILER_EXTENSION\"")
            line(4, "}")
        }
        line(0, "}")
        blank()

        line(0, "dependencies {")
        if (app.compose) {
            line(4, "val composeBom = platform(\"androidx.compose:compose-bom:2024.05.00\")")
            line(4, "implementation(composeBom)")
            line(4, "implementation(\"androidx.compose.ui:ui\")")
            line(4, "implementation(\"androidx.compose.ui:ui-graphics\")")
            line(4, "implementation(\"androidx.compose.ui:ui-tooling-preview\")")
            line(4, "implementation(\"androidx.compose.material3:material3\")")
            line(4, "implementation(\"androidx.activity:activity-compose:1.9.0\")")
            line(4, "debugImplementation(\"androidx.compose.ui:ui-tooling\")")
        } else {
            line(4, "implementation(\"androidx.core:core-ktx:1.12.0\")")
            line(4, "implementation(\"androidx.appcompat:appcompat:1.6.1\")")
            line(4, "implementation(\"com.google.android.material:material:1.12.0\")")
            line(4, "implementation(\"androidx.constraintlayout:constraintlayout:2.1.4\")")
        }
        line(4, "testImplementation(\"junit:junit:4.13.2\")")
        line(4, "androidTestImplementation(\"androidx.test.ext:junit:1.1.5\")")
        line(4, "androidTestImplementation(\"androidx.test.espresso:espresso-core:3.5.1\")")
        line(0, "}")
        blank()

        scaffold.writeText("${app.module}/build.gradle.kts", sb.toString())
    }
}