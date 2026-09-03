/*
 * :terminal-proot — in-IDE proot engine compiled from ReTerminal's MIT-licensed source
 * (https://github.com/RohitKushvaha01/ReTerminal, core/proot/src/main/cpp/).
 *
 * ReTerminal is the same author's MIT fork of proot-me/proot (upstream GPL-2.0). The fork is
 * packaged under MIT here (per LICENSE in this directory) and ships as source — we compile it
 * ourselves with NDK + CMake instead of bundling a binary. The build produces:
 *
 *   - libproot.so      — the proot executable itself (renamed from the `proot` CMake target)
 *   - libloader.so     — the static 64-bit host loader (loader/loader.c + loader/assembly.S)
 *   - libloader32.so   — the static 32-bit cross-arch loader (only built on hosts that can target
 *                        armv7a from arm64; absent on aarch64 Linux build hosts when M32 cross isn't
 *                        available — checked via HAS_LOADER_32BIT in the upstream CMakeLists)
 *   - libtalloc.so     — bundled inside libproot.so's target_link_libraries; we expose it as a
 *                        standalone .so via the ReTerminal consumer pattern (LocalSymlink at runtime)
 *
 * proot is `exec()`ed by TerminalEngine.kt out of nativeLibraryDir, so useLegacyPackaging=true
 * keeps the .so uncompressed in the APK — the only path that ART allows exec from on a non-rooted
 * device under the SELinux `untrusted_app` domain.
 */
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.ide.android.terminalproot"
    compileSdk = 36

    // Proot MUST reach nativeLibraryDir uncompressed so TerminalEngine.kt can `exec()` it via
    // `Os.execv` — same constraint as the previous Termux-built libproot.so. AGP's default
    // packaging path keeps pages uncompressed only for the legacy `useLegacyPackaging` extractor.
    packaging.jniLibs.useLegacyPackaging = true
    // Match the repo-wide NDK version used by :termux:emulator. ReTerminal upstream pins NDK 29
    // (newer) but the toolchain parts of CMakeLists.txt only consume the standard API and don't
    // benefit from a bump — staying on 27.1.12297006 keeps the module compatible with the rest of
    // the build without forcing an upgrade elsewhere.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 26
        // Only ABIs we actually ship to. Adding x86_64 here costs ~5 MB per build variant but
        // is required so the IDE works on emulators (CI + dev machines). ReTerminal upstream
        // builds all three; we mirror that.
        externalNativeBuild {
            cmake {
                cppFlags("")
                arguments("-DANDROID_STL=none")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The .so files are post-build stripped by the CMake POST_BUILD command in the
            // upstream CMakeLists (libproot.so --strip-unneeded, libloader.so plain strip).
            // We do NOT add proguardFiles here because there's no Java/Kotlin code to obfuscate.
        }
    }
}

dependencies {
    // No public API surface — this module is consumed only via JNI/jniLibs. The downstream
    // TerminalEngine.kt reads `applicationInfo.nativeLibraryDir` directly and invokes the
    // binaries through ProcessBuilder, so no Java/Kotlin bridge is needed here.
}
