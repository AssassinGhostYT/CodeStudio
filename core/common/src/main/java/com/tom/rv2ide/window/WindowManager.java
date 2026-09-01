/*
 *  This file is part of CodeStudio.
 *
 *  CodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.window;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Lightweight FQN wrapper around {@link android.view.WindowManager} so the vendored Termux
 * {@code com.termux.shared.view.ViewUtils} can import the IDE's historical class name. We don't add
 * behaviour — just defer the Context → WindowManager resolution Termux does to obtain display metrics.
 *
 * <p>Note: this intentionally does NOT extend or shadow {@code android.view.WindowManager}. Android's
 * framework requires that name for the system service and a class with the same FQN would break that
 * lookup. Keep them distinct via the {@code com.tom.rv2ide.window} package.
 */
public class WindowManager {

    private final Context appContext;

    public WindowManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    @NonNull
    public WindowMetrics getCurrentWindowMetrics() {
        android.view.WindowManager wm = appContext.getSystemService(android.view.WindowManager.class);
        if (wm == null) {
            throw new IllegalStateException("android.view.WindowManager not available");
        }
        return new WindowMetrics(wm.getCurrentWindowMetrics());
    }

    @NonNull
    public WindowMetrics getMaximumWindowMetrics() {
        android.view.WindowManager wm = appContext.getSystemService(android.view.WindowManager.class);
        if (wm == null) {
            throw new IllegalStateException("android.view.WindowManager not available");
        }
        return new WindowMetrics(wm.getMaximumWindowMetrics());
    }
}