/*
 *  This file is part of CodeStudio.
 *
 *  CodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.utils

import android.content.Context
import android.content.res.Configuration

/**
 * Replacement for the historical {@code com.itsaky.androidide.utils.ResourceUtilsKt}. The vendored
 * Termux {@code ThemeUtils.isNightModeEnabled} calls {@code isSystemInDarkMode(context)}; we surface
 * the device's night-mode flag here so Termux's theme follows the system.
 */
fun Context.isSystemInDarkMode(): Boolean {
    val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return mode == Configuration.UI_MODE_NIGHT_YES
}