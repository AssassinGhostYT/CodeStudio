/*
 *  This file is part of CodeStudio.
 *
 *  CodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.app.configuration;

/**
 * Architecture identifier surfaced by {@link IDEBuildConfigProvider}. The vendored Termux code references
 * this when building shell command lines; we only ever compile for arm64-v8a / armeabi-v7a so the value is
 * informational and never actually branched on inside :termux:* at runtime.
 */
public enum CpuArch {
    ARM64_V8A("arm64-v8a"),
    ARMEABI_V7A("armeabi-v7a"),
    X86_64("x86_64"),
    UNKNOWN("unknown");

    private final String abi;

    CpuArch(String abi) {
        this.abi = abi;
    }

    public String getAbiName() {
        return abi;
    }

    /** Best-effort match against {@code Build.SUPPORTED_ABIS[0]}; falls back to {@link #UNKNOWN}. */
    public static CpuArch fromAbi(String abi) {
        if (abi == null) return UNKNOWN;
        switch (abi) {
            case "arm64-v8a": return ARM64_V8A;
            case "armeabi-v7a": return ARMEABI_V7A;
            case "x86_64": return X86_64;
            default: return UNKNOWN;
        }
    }
}