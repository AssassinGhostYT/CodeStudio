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
    id("kotlin-android")
}

val packageVariant = System.getenv("TERMUX_PACKAGE_VARIANT") ?: "apt-android-7" // Default: "apt-android-7"

// The IDE's actual applicationId (Play Store identity, defined in :ide-android/build.gradle.kts). TermuxConstants
// uses this to compute `/data/data/<pkg>/files/usr/` and friends. Hardcoded rather than referencing a custom
// `BuildConfig.packageName` (which the upstream AndroidIDE defined but this codebase does not); the IDE's
// applicationId is a stable public identifier, so a literal here is fine. If it ever changes, update both spots.
val termuxPackageName = "com.codestudio.ide"

android {
    namespace = "com.termux"

    defaultConfig {
        buildConfigField("String", "TERMUX_PACKAGE_VARIANT", "\"" + packageVariant + "\"") // Used by TermuxApplication class

        manifestPlaceholders["TERMUX_PACKAGE_NAME"] = termuxPackageName
        manifestPlaceholders["TERMUX_APP_NAME"] = "AndroidIDE"
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
    
    implementation(project(":core:projects"))
    implementation(project(":core:common"))
    implementation(project(":core:resources"))
    implementation(project(":termux:view"))
    implementation(project(":termux:shared"))
    implementation(project(":utilities:preferences"))
}

tasks.register("versionName") {
    doLast {
        print(project.rootProject.version)
    }
}