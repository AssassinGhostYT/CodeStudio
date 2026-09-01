/*
 *  This file is part of CodeStudio.
 *
 *  CodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.managers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * Tool-binary manager referenced by the vendored Termux setup session. Historical behaviour: resolve a tool
 * binary on disk and return its absolute path. The current IDE doesn't expose this API, so the minimal
 * stub returns {@code null} and {@link com.tom.rv2ide.terminal.IdesetupSession} (now deleted) does not run.
 */
public final class ToolsManager {

    public static final class ToolName {
        public static final String IDF_SETUP = "idesetup";
    }

    private static volatile ToolsManager instance;

    public static ToolsManager getInstance() {
        if (instance == null) {
            synchronized (ToolsManager.class) {
                if (instance == null) instance = new ToolsManager();
            }
        }
        return instance;
    }

    private ToolsManager() {}

    /** No IDE-managed tool binaries are exposed to Termux today. Returns null so callers can short-circuit. */
    @Nullable
    public File getToolExecutable(@NonNull String tool) {
        return null;
    }
}