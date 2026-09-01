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

import androidx.appcompat.app.AppCompatActivity;

/**
 * Minimal stand-in for the historical {@code com.itsaky.androidide.app.BaseIDEActivity} that the vendored
 * Termux sources extend (TermuxActivity, ReportActivity, …). The IDE's current restructure dropped the
 * original base class; rather than rewrite the Termux sources we provide this no-op base so the vendored
 * code compiles. New IDE code should extend {@link AppCompatActivity} directly.
 */
public class BaseIDEActivity extends AppCompatActivity {}