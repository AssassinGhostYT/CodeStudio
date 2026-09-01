/*
 *  This file is part of CodeStudio.
 *
 *  CodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.window;

import androidx.annotation.NonNull;

/**
 * FQN wrapper around the platform {@link android.view.WindowMetrics} so the vendored Termux
 * {@code com.termux.shared.view.ViewUtils} can import an IDE-namespaced type. We forward to the platform
 * type and expose the same {@code getBounds()} / density / DP accessors Termux uses.
 */
public class WindowMetrics {

    private final android.view.WindowMetrics delegate;

    public WindowMetrics(@NonNull android.view.WindowMetrics delegate) {
        this.delegate = delegate;
    }

    @NonNull
    public android.graphics.Rect getBounds() {
        return delegate.getBounds();
    }
}