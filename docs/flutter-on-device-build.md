# Building Flutter apps on-device — maintainer plan

This document is the actionable plan for making CodeStudio build **Flutter** apps on the
Android device "the same way it builds Java/Kotlin": by **bundling the toolchain inside the
APK**, so the user needs to install nothing (matching how `aapt2`, the Java/Kotlin compilers,
`android.jar`, `D8`/`R8`, and the debug keystore already ship).

It is written as a checklist for the **repository maintainers** because it changes the app's
build/CI packaging (`ide-android/build.gradle.kts` + `.github/workflows/*`) and the APK's
size/signature — decisions that must not be made from a plugin module like `:lang-dart`.

> Current status (Aug 2026): a **Dart** SDK is already auto-downloaded on first use by
> `:lang-dart`'s `FlutterSdkManager` (`.zip`, in-process `ZipInputStream`, reusing the
> resumable `SdkNetFetcher` — the exact "download on first use like JDK/Android sources"
> mechanism). That already makes **`dart-console`** modules really run (`dart run`). This
> document is the *further* step that makes **`flutter-app`** modules build a runnable APK.

---

## 1. The hard constraints (read first)

A Flutter app's `MainActivity` extends `io.flutter.embedding.android.FlutterActivity`, which
requires the **Flutter engine** — a native library (`libflutter.so`) plus the Dart **AOT**
snapshot of the app code. Building it on-device needs three things that do not exist today:

1. **Dart AOT compiler.** Flutter-release builds `dart compile aot-snapshot` a precompiled
   snapshot (`app.so`) from `main.dart`, then packages it with the engine. A debug
   `flutter run` instead *interprets* via the JIT Dart VM and needs the Dart SDK + VM —
   which `FlutterSdkManager` already provisions. So:
   - **Debug path** is closest to achievable: `dart` (already downloaded) interprets the
     app over the engine. But debug mode also needs `libflutter.so`.
   - **Release path** needs the Dart AOT compiler (`dartaotruntime`/`gen_snapshot`) — the
     Dart SDK builds for Android (`dartsdk-linux-arm64` is the *host* tool; the on-device
     `gen_snapshot` must target `arm64-android`). Toolchain cross-compilation is the risky part.

2. **The Flutter engine (`libflutter.so`)**, per ABI (`arm64-v8a`, `armeabi-v7a`, `x86`,
   `x86_64`). The engine is a prebuilt native library from
   `https://storage.googleapis.com/flutter_infra_release/flutter/<engine-hash>/android-arm64-release/…`
   (a `flutter_engine` artifact). It **must** ship inside the app in `nativeLibraryDir` (the
   only place ART permits `exec`/`dlopen`), exactly like `libaapt2.so` today.

3. **Android exec constraints.** Engine native libs are loaded by `dlopen` from
   `nativeLibraryDir` (fine — that's the aapt2 pattern). But CodeStudio's current approach
   shells *out* to a `flutter` script via `ProcessBuilder`; a Kotlin/Java side cannot fork
   a Dart engine directly in-process like the JVM compilers do. The realistic integration is
   **in-process**: load the engine JNI (`io.flutter.embedding.engine.FlutterEngine`), which
   is loaded via the app's classloader + `libflutter.so` from `nativeLibraryDir`. This is a
   **runtime integration**, not outsourcing to a CLI.

**Bottom line:** this is feasible but is a real engineering project (engine artifact
versioning + host cross-toolchain + in-process runtime host), not a one-line config change.
The steps below are ordered so each one is independently shippable.

---

## 2. Package the Flutter engine into the APK

Mirror the existing `fetchAndroidBuildTools` task in `ide-android/build.gradle.kts`
(lines 687-727) and the `jniLibs` block (lines 545-548).

### 2a. Fetch the engine per ABI

Add a `fetchFlutterEngine` task (same shape as `fetchAndroidBuildTools`): for each ABI
`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, download
`.../android-<abi>-release/<arch>/libflutter.so` from the pinned Flutter engine hash and
write it to `ide-android/src/main/jniLibs/<abi>/libflutter.so`, guarding the magic bytes
(`\x7fELF`) and a minimum size, with a `.flutter-engine-source` marker so it stays offline
once populated (exact copy of the `.aapt2-source` marker pattern).

Concretely:

```kotlin
val flutterEngineHash = "…"           // pin the Flutter stable engine hash here
val flutterEngineSource = "hash-$flutterEngineHash"
val flutterAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

val fetchFlutterEngine = tasks.register("fetchFlutterEngine") {
    group = "build setup"
    val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
    doLast {
        for (abi in flutterAbis) {
            val abiDir = File(jniLibsDir, abi)
            val so = File(abiDir, "libflutter.so")
            val marker = File(abiDir, ".flutter-engine-source")
            if (so.exists() && so.length() > 0L && marker.takeIf { it.exists() }?.readText()?.trim() == flutterEngineSource) continue
            abiDir.mkdirs(); so.delete()
            // ABI subfolder differs per engine build; confirm the layout for the pinned hash.
            val url = "https://storage.googleapis.com/flutter_infra_release/flutter/$flutterEngineHash/android-$abi-release/$abi/libflutter.so"
            logger.lifecycle("Fetching Flutter engine ($abi) from $url")
            URL(url).openStream().use { input -> so.outputStream().use { input.copyTo(it) } }
            check(inputStream-magic-is-ELF && size > threshold)      // guard, like aapt2
            so.setExecutable(true)
            marker.writeText(flutterEngineSource)
        }
    }
}
```

Hook it into `preBuild` next to the other fetch/stage tasks (line 731-733).

### 2b. Keep legacy packaging + no strip

The engine is a true shared object on Android (not an app-only `lib*.so` like `aapt2`), so:

- Keep `jniLibs.useLegacyPackaging = true` — ART extracts libs to `nativeLibraryDir`.
- `keepDebugSymbols += setOf("**/libflutter.so")` so AGP's NDK strip does not corrupt it.
- This **grows the APK by ~30–60 MB per ABI** (the engine is a large binary), i.e. ~150–250 MB
  total across four ABIs. **Decision needed:** ship all four ABIs, or follow the device's ABI
  only (an APK-per-ABI split for size). Recommend `splits { abi { … } }` or bundling the single
  ABI you target first, to keep the download reasonable.

### 2c. Engine versioning

Pin `flutterEngineHash` to the **same stable Flutter release** the Dart SDK downloader uses,
so the engine and the Dart VM in `dart-sdk` agree on the snapshot/app format. The engine hash
is the digest in `releases_*.json` (the `"hash"` next to the stable version) — the same
artifact `FlutterSdkManager` could publish, so the app always uses a matched pair.

---

## 3. Host toolchain for release builds (Dart AOT)

For **release** builds CodeStudio must compile `main.dart` → an AOT snapshot on-device. This
is the hardest part and the main open risk.

Options, ordered by feasibility:

1. **Debug/JIT first (recommended).** Ship only the Dart SDK (already done) + engine and
   implement a debug-mode run (`flutter run`-equivalent) where the Dart VM interprets the app
   against `libflutter.so`. This gets *something running* with the smallest toolchain (no
   `gen_snapshot`). Probe this before investing in AOT.
2. **AOT snapshot via the Dart SDK's AOT compiler.** The Dart SDK for Android ships
   `gen_snapshot`/`dartaotruntime` that can emit `app.so` for `arm64-android` and `arm-android`
   when run on a compatible host. This is a cross-compile: the emitted snapshot is then packed
   with the engine. **Risk:** the on-device snapshot path + AOT flags (`--snapshot-kind=app-aot-elf`,
   `--elf`, `--vm-isolate-snapshot-data`, …) must match the pinned engine exactly; Flutter's
   own `flutter assemble` handles all this, and re-implementing it in-process is substantial.
3. **Derive the produced-artifact format from a real `flutter build apk` run**, then replicate
   the minimal transform: AOT snapshot + `assets/flutter_assets/` + `lib/*/libapp.so` + engine +
   the Flutter embedding classes (dexed). The APK assembly can reuse the existing
   `AndroidBuildSystem` (D8/R8/apksig in-process) once the Flutter artifacts are produced.

Because this crosses several toolchain versions, **recommend option 1 first**, then generalize.

---

## 4. Runtime integration (in-process, not a subprocess)

- Load `libflutter.so` from `nativeLibraryDir` (resolve exactly like
  `AndroidSdk.forDevice(t.androidJar, t.nativeLibDir)` → `nativeLibDir.resolve("libflutter.so")`).
- Instantiate `io.flutter.embedding.engine.FlutterEngine` (the embedding classes must be
  dexed into the app — either from the Flutter embedding AAR's `classes.jar`, staged like the
  `vmSpikeComposeRuntimeAsset` pattern, or authored in `:lang-dart` as a thin host).
- `FlutterSdkManager` already provides the `dart` VM for debug/JIT. The `FlutterBuildSystem`
  tasks should drive the engine host rather than `ProcessBuilder("flutter")` (a shell script
  that ART cannot exec from an app dir).

This is a new runtime host (a Flutter engine host in the IDE), analogous to `SwingAwareProgramInterpreter`
but for Flutter. It replaces the current `flutterMissingMessage()` fall-through for the engine-present case.

---

## 5. Where `FlutterBuildSystem` changes land

All runtime/business changes stay in `:lang-dart` (`FlutterBuildSystem.kt`, `FlutterSdkManager.kt`)
so nothing Java/Kotlin/XML is touched. Only the *packaging* (engine `.so` fetch + `jniLibs`
config + CI) lives in `ide-android`/`.github/workflows`.

Summary of the split:

| Concern | Where | Status |
|---|---|---|
| Dart SDK auto-download (debug toolchain) | `:lang-dart` `FlutterSdkManager` | ✅ done (commit `6162f8c`) |
| `dart-console` runs via managed `dart` | `:lang-dart` `FlutterBuildSystem` | ✅ done |
| Engine `libflutter.so` bundled per ABI | `ide-android/build.gradle.kts` (fetch + jniLibs) | 🔲 maintainer task (this doc) |
| Debug/JIT Flutter run host | `:lang-dart` + engine embedding | 🔲 next |
| Release AOT `app.so` on-device | toolchain (Dart AOT) + `AndroidBuildSystem` | 🔲 hardest / last |
| APK size/ABI split decision | `ide-android` packaging | 🔲 decision |

---

## 6. Open decisions for the maintainer

1. **Scope of first milestone** — Debug/JIT-only Flutter run, or go straight for release AOT?
   (Recommend debug/JIT first; it needs only the Dart SDK already added + the engine.)
2. **ABI scope & APK size** — ship one ABI (fastest, smallest, device-specific) or all four.
   Recommend `splits { abi { … } }` so each installed APK carries only its own engine.
3. **Engine embedding source** — bundle the Flutter `engine` AAR's embedding classes or author
   a minimal host in `:lang-dart`. Recommend the former (fewer moving parts, API-stable).
4. **Which Flutter release to pin** — must match both the Dart SDK downloader and the engine
   hash. Centralize the version in one constant `FlutterSdkManager` exposes.
