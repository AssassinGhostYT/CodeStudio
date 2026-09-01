/*
 *  This file is part of CodeStudio.
 *
 *  CodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.preferences.internal;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Minimal stub of the historical GeneralPreferences singleton the vendored
 * {@code com.termux.shared.theme.ThemeUtils.shouldEnableDarkTheme} calls. We surface the system's
 * night-mode setting so Termux's theme follows the device, matching the IDE's behaviour.
 */
public enum GeneralPreferences {
    INSTANCE;

    /** Returns the current system UI mode (AppCompatDelegate.MODE_NIGHT_*). */
    public int getUiMode() {
        return AppCompatDelegate.getDefaultNightMode();
    }
}