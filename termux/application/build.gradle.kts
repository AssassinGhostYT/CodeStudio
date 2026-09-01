/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.library")
    // AGP 9.0 has built-in Kotlin support; the legacy `kotlin-android` plugin (which applied
    // `org.jetbrains.kotlin.android`) is rejected on apply — see https://kotl.in/gradle/agp-built-in-kotlin.
}

val packageVariant = System.getenv("TERMUX_PACKAGE_VARIANT") ?: "apt-android-7" // Default: "apt-android-7"

// The IDE's actual applicationId (Play Store identity, defined in :ide-android/build.gradle.kts). TermuxConstants
// uses this to compute `/data/data/<pkg>/files/usr/` and friends. Hardcoded rather than referencing a custom
// `BuildConfig.packageName` (which the upstream AndroidIDE defined but this codebase does not); the IDE's
// applicationId is a stable public identifier, so a literal here is fine. If it ever changes, update both spots.
val termuxPackageName = "com.codestudio.ide"

android {
    namespace = "com.termux"
    compileSdk = 36

    defaultConfig {
        buildConfigField("String", "TERMUX_PACKAGE_VARIANT", "\"" + packageVariant + "\"") // Used by TermuxApplication class

        manifestPlaceholders["TERMUX_PACKAGE_NAME"] = termuxPackageName
        manifestPlaceholders["TERMUX_APP_NAME"] = "AndroidIDE"
    }

    buildFeatures {
        // AGP 9.x requires explicit opt-in for BuildConfig generation; the vendored TermuxApplication
        // reads BuildConfig.TERMUX_PACKAGE_VARIANT.
        buildConfig = true
    }

    lint.disable += "ProtectedPermissions"
    packaging.jniLibs.useLegacyPackaging = true
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.drawer)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.viewpager)
    implementation(libs.google.material)
    implementation(libs.google.guava)
    implementation(libs.common.markwon.core)
    implementation(libs.common.markwon.extStrikethrough)
    implementation(libs.common.markwon.linkify)
    implementation(libs.common.markwon.recycler)
    
    implementation(project(":core:common"))
    // Use api() on :termux:view so IDE-side code can import com.termux.view.TerminalView without
    // needing :ide-android to declare :termux:view as a direct dependency. Keep :termux:shared as
    // implementation() so its resources flow through :termux:application's R.txt exactly once — if
    // both modules expose :termux:shared, AGP's runtime_symbol_list sees the symbol twice and fails
    // with "Duplicate key Theme_AppCompat_Light_Dialog".
    api(project(":termux:view"))
    implementation(project(":termux:shared"))
}

tasks.register("versionName") {
    doLast {
        print(project.rootProject.version)
    }
}