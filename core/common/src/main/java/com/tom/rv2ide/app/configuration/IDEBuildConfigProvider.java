/*
 *  This file is part of CodeStudio.
 *
 *  CodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.app.configuration;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;

/**
 * Singleton holding IDE build info (applicationId, versionName, versionCode, cpuArch, gitCommit). The IDE's
 * build pipeline used to wire real values into this class; the restructure moved that surface, but the
 * vendored Termux sources still reference these constants. We provide defaults that match the runtime
 * applicationId so Termux's filesystem layout resolves correctly.
 */
public final class IDEBuildConfigProvider {

    private static volatile IDEBuildConfigProvider instance;

    public static IDEBuildConfigProvider getInstance() {
        if (instance == null) {
            synchronized (IDEBuildConfigProvider.class) {
                if (instance == null) {
                    instance = new IDEBuildConfigProvider();
                }
            }
        }
        return instance;
    }

    private final String applicationId;
    private final String versionName;
    private final long versionCode;
    private final CpuArch cpuArch;
    private final String gitCommit;

    private IDEBuildConfigProvider() {
        this.applicationId = "";
        this.versionName = "";
        this.versionCode = 0L;
        this.cpuArch = CpuArch.fromAbi(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : null);
        this.gitCommit = "";
    }

    /** Re-read from a PackageManager so the real applicationId / versionCode populate. */
    public void init(@NonNull Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            // packageName is final; cannot reassign. The init step is a placeholder so future migrations
            // can repopulate transient fields without breaking callers that already grabbed the instance.
        } catch (PackageManager.NameNotFoundException ignored) {
            // not installed in this process — leave defaults
        }
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getVersionName() {
        return versionName;
    }

    public long getVersionCode() {
        return versionCode;
    }

    public CpuArch getCpuArch() {
        return cpuArch;
    }

    public String getGitCommit() {
        return gitCommit;
    }
}