/*
 *  This file is part of CodeStudio.
 *
 *  CodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.app;

import android.app.Application;

import com.tom.rv2ide.utils.Environment;

/**
 * Minimal stand-in for the historical {@code com.itsaky.androidide.app.BaseApplication} that the vendored
 * Termux's {@code TermuxApplication} extends. Registers the application Context with
 * {@link Environment} at process start so the vendored Termux bootstrap (HOME, BIN_DIR) resolves to the
 * real {@code files/} and {@code files/usr/bin/} paths the on-device shell expects.
 */
public class BaseApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Environment.setApplicationContext(this);
    }
}