package dev.ide.ui.platform

import androidx.compose.runtime.Composable

/**
 * Whether the running app can read arbitrary paths of the device's shared storage, and how to ask for it.
 *
 * On Android 11+ (API 30) the platform's scoped storage hides the *contents* of shared-storage folders from
 * apps without "All files access" (`MANAGE_EXTERNAL_STORAGE`): the folder itself still lists, but every file
 * inside is filtered out, so a file browser shows empty directories. The terminal already gates on the same
 * permission, and so does the device file explorer — without it the explorer can only see the app's own
 * sandbox.
 */
interface DeviceStorageAccess {
    /**
     * True when the app can list and read arbitrary shared-storage paths. Always true where the platform
     * doesn't restrict filesystem access (desktop, Android ≤ 10).
     */
    fun hasFullAccess(): Boolean

    /**
     * Opens the OS settings page where the user grants "All files access". Returns false when the platform
     * has no such setting (nothing to open) — the caller then hides the affordance.
     */
    fun openSettings(): Boolean
}

/** Resolve the [DeviceStorageAccess] for the current platform. */
@Composable
expect fun rememberDeviceStorageAccess(): DeviceStorageAccess
