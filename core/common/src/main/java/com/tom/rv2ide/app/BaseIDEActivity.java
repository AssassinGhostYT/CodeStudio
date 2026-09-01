/*
 *  This file is part of CodeStudio.
 *
 *  CodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  CodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 */

package com.tom.rv2ide.app;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Minimal stand-in for the historical {@code com.itsaky.androidide.app.BaseIDEActivity} that the vendored
 * Termux sources extend (TermuxActivity, ReportActivity, …). The IDE's current restructure dropped the
 * original base class; rather than rewrite the Termux sources we provide this base so the vendored
 * code compiles. New IDE code should extend {@link AppCompatActivity} directly.
 *
 * <p>The vendored Termux activities override {@link #bindLayout()} to inflate their own layouts; we
 * declare the method here so those {@code @Override} annotations resolve. Callers must invoke
 * {@code setContentView(bindLayout())} themselves — the IDE does not invoke this hook.
 */
public class BaseIDEActivity extends AppCompatActivity {

  @NonNull
  protected View bindLayout() {
    // Default fallback for any subclass that does not override bindLayout(); returning the activity's
    // own content frame is safe (it is never null post-super.onCreate) and keeps the class non-abstract.
    return findViewById(android.R.id.content);
  }
}