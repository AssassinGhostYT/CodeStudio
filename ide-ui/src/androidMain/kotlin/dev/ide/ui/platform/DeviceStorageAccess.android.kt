package dev.ide.ui.platform

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android's "All files access" (`MANAGE_EXTERNAL_STORAGE`). Below API 30 there's no scoped storage, so the
 * app can read the shared storage already and there's nothing to ask for.
 */
@Composable
actual fun rememberDeviceStorageAccess(): DeviceStorageAccess {
    val context = LocalContext.current
    return remember(context) {
        object : DeviceStorageAccess {
            override fun hasFullAccess(): Boolean =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

            override fun openSettings(): Boolean {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
                val perApp = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(perApp); true }.getOrDefault(false)) return true
                // Some ROMs don't ship the per-app page — fall back to the global list.
                val global = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return runCatching { context.startActivity(global); true }.getOrDefault(false)
            }
        }
    }
}
