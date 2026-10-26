package com.xblackbytesx.slopstream.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Android 17 (API 37) asks before an app talks to the home network ("Nearby devices"): the sync
 * server on a LAN address, or the LAN paste page another device opens. Asked only when the user
 * starts one of those, never at launch. Earlier versions (the Shield's 11) need nothing.
 */
fun needsLocalNetwork(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 37 &&
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

/** Returns a function that asks for the permission when needed, then calls [onResult] (granted?). */
@Composable
fun rememberLocalNetworkRequest(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { callback.value(it) }
    return { if (needsLocalNetwork(context)) launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) else callback.value(true) }
}

/** The app's page in system settings, to allow a permission that was refused for good. */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
