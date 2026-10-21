package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktop has no scoped storage: the app can read the whole filesystem, and there is nothing to grant. */
@Composable
actual fun rememberDeviceStorageAccess(): DeviceStorageAccess =
    remember {
        object : DeviceStorageAccess {
            override fun hasFullAccess(): Boolean = true
            override fun openSettings(): Boolean = false
        }
    }
