/*
 *  This file is part of CodeStudio.
 *
 *  CodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.projects.internal;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * Minimal stub of the historical {@code ProjectManagerImpl} that the vendored
 * {@code com.termux.app.TerminalFragment} calls to {@code cd} the new shell into the open project. The IDE
 * no longer has a singleton by this name; without it the termux shell would fail to compile. Returning
 * {@code null} from {@link #getProjectDir()} makes TerminalFragment skip the {@code cd} injection (the
 * shell just starts in the Termux default $HOME), which is a sane default — the user can still {@code cd}
 * into their project once the shell is up.
 */
public final class ProjectManagerImpl {

    private static final ProjectManagerImpl INSTANCE = new ProjectManagerImpl();

    public static ProjectManagerImpl getInstance() {
        return INSTANCE;
    }

    private ProjectManagerImpl() {}

    @Nullable
    public File getProjectDir() {
        return null;
    }
}