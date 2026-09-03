# :terminal-proot

Native proot engine for the in-IDE terminal panel. Compiles from source
via NDK + CMake — no prebuilt binaries shipped.

## Source

`src/main/cpp/` is an unmodified copy of
[`RohitKushvaha01/ReTerminal/core/proot/src/main/cpp/`](https://github.com/RohitKushvaha01/ReTerminal/tree/main/core/proot/src/main/cpp),
MIT-licensed. See `LICENSE` in this directory for the full text and
copyright notice. To refresh to a newer upstream release:

```bash
cd /tmp && rm -rf reterminal-src && \
  git clone --depth 1 --filter=blob:none --s sparse \
    https://github.com/RohitKushvaha01/ReTerminal.git reterminal-src && \
  cd reterminal-src && git sparse-checkout set core/proot/src/main/cpp && \
  rsync -a --delete core/proot/src/main/cpp/ \
    <repo-root>/ide-android/terminal-proot/src/main/cpp/
```

The ReTerminal upstream is itself a fork of
[`proot-me/proot`](https://github.com/proot-me/proot) (GPL-2.0); the MIT
relicensing applies only to the modifications made by
[Rohit Kushvaha](https://github.com/RohitKushvaha01/) — and the
`extension/fake_id0/` source tree in particular is original work.
Talloc is bundled via `talloc/talloc.c` from the
[Samba project](https://gitlab.com/samba-team/salloc/talloc) (LGPL-3.0);
the LGPL permits static linking into a MIT-licensed binary as long as
the source remains available (it does — this directory).

## Build outputs

After `./gradlew :terminal-proot:assembleRelease`, the following
artifacts land in `build/intermediates/cxx/RelWithDebInfo/<obj>/obj/<abi>/`:

| File | Purpose |
| --- | --- |
| `libproot.so` | The proot executable. `exec()`ed via `ProcessBuilder` from `TerminalEngine.kt`. |
| `libloader.so` | Static host loader for 64-bit tracees. `PROOT_LOADER=$NATIVE_LIB_DIR/libloader.so`. |
| `libloader32.so` | Static 32-bit cross-arch loader (only present when the build host can target 32-bit). Optional. |
| `libtalloc.so` | Bundled inside `libproot.so`; consumers don't reference it directly. |

All four (when present) are packaged under
`build/outputs/aar/.../jni/<abi>/` with `useLegacyPackaging=true`, so
they land in `applicationInfo.nativeLibraryDir` at install — the only
dir from which ART lets an `untrusted_app` `execve()`.
