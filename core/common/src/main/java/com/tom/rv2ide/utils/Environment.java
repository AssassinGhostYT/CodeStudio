/*
 *  This file is part of CodeStudio.
 *
 *  CodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.utils;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.Map;

/**
 * Minimal stand-in for the historical {@code com.itsaky.androidide.utils.Environment} that the vendored
 * Termux sources reference (BIN_DIR, HOME, TMP_DIR, ANDROID_HOME, putEnvironment). The real
 * IDE had this wired to {@code /data/data/<applicationId>/files/...}; we replicate the path layout the
 * Termux bootstrap expects so shell init / pkg install work.
 *
 * <p>Path constants are populated lazily from a {@link Context} via {@link #setApplicationContext(Context)};
 * callers (e.g. {@code TermuxApplication.onCreate}) should invoke that once at process start. Defaults
 * are reasonable enough to compile-link without the call, but Termux's bootstrap will fail until they're
 * resolved to the real app data directory.
 */
public final class Environment {

    /** {@code $ANDROID_HOME} expected by the Termux shell; the on-device SDK is at {@code /system}. */
    public static final File ANDROID_HOME = new File("/system");

    /** Termux's tmpdir matches the platform default. */
    public static final File TMP_DIR = new File("/data/local/tmp");

    /**
     * HOME and BIN_DIR are NOT final — they are populated by {@link #setApplicationContext(Context)} with
     * the app's private data directory (where Termux's bootstrap installs). Until then they point at
     * sensible compile-time defaults so that the static-initializer phase doesn't NPE.
     */
    public static File HOME = new File("/data/data/com.codestudio.ide/files");
    public static File BIN_DIR = new File("/data/data/com.codestudio.ide/files/usr/bin");

    private Environment() {}

    /** Called from the application onCreate (or any first {@link Context}-touching path). Idempotent. */
    public static synchronized void setApplicationContext(@NonNull Context context) {
        File dataDir = context.getFilesDir();
        HOME = dataDir;
        BIN_DIR = new File(dataDir, "usr/bin");
    }

    public static void mkdirIfNotExits(@NonNull File dir) {
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    /**
     * Populate {@code env} with the Android-specific variables the Termux shell init expects. Matches what
     * the historical AndroidShellEnvironment injected (ANDROID_HOME / ANDROID_DATA / ANDROID_ROOT / etc.).
     */
    public static void putEnvironment(@NonNull Map<String, String> env, boolean isFailSafe) {
        env.put("ANDROID_HOME", ANDROID_HOME.getAbsolutePath());
        env.put("ANDROID_DATA", "/data");
        env.put("ANDROID_ROOT", "/system");
        env.put("ANDROID_ASSETS", "/system");
        env.put("ANDROID_STORAGE", "/storage");
        env.put("ANDROID_PRIVATE", "/data/private");
        env.put("ANDROID_PROPERTY_WORKSPACE", "/dev/socket/genyd");
        env.put("CLASSPATH", System.getenv("CLASSPATH"));
        env.put("HOME", HOME.getAbsolutePath());
        if (isFailSafe) {
            env.put("TERMUX_Failsafe", "1");
        }
    }
}