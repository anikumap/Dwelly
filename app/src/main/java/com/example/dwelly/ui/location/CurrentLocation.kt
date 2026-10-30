package com.example.dwelly.ui.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/** Outcome of asking the device where it is. */
sealed class CurrentLocationResult {
    data class Found(val lat: Double, val lng: Double) : CurrentLocationResult()
    data object PermissionDenied : CurrentLocationResult()
    data object Unavailable : CurrentLocationResult()
}

/** A short message for a result that isn't a location, or null when one was found. */
fun CurrentLocationResult.problem(): String? = when (this) {
    is CurrentLocationResult.Found -> null
    CurrentLocationResult.PermissionDenied -> "Location permission was denied. Enter the location by hand instead."
    CurrentLocationResult.Unavailable -> "Couldn't get the current location. Check that location is on, or enter it by hand."
}

/**
 * Returns a function that asks for location permission if needed, then reports the device's
 * current location to [onResult]. ARCHITECTURE §9 allows a device location prompt or manual entry.
 */
@Composable
fun rememberCurrentLocationRequest(onResult: (CurrentLocationResult) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onResult)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            fetchCurrentLocation(context) { callback.value(it) }
        } else {
            callback.value(CurrentLocationResult.PermissionDenied)
        }
    }
    return {
        if (hasLocationPermission(context)) {
            fetchCurrentLocation(context) { callback.value(it) }
        } else {
            // Asking for both lets the user choose precise or approximate.
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }
}

private fun hasLocationPermission(context: Context) =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

// Only called after hasLocationPermission() or a granted permission request.
@SuppressLint("MissingPermission")
private fun fetchCurrentLocation(context: Context, onResult: (CurrentLocationResult) -> Unit) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    // Balanced accuracy relies on network location, which may not exist (no Wi-Fi or cell
    // positioning), so use GPS when the user allowed a precise location.
    val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val priority = if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

    // If no fresh fix arrives, fall back to the last known one before giving up.
    val fallBackToLastKnown = {
        client.lastLocation
            .addOnSuccessListener { last ->
                onResult(last?.let { CurrentLocationResult.Found(it.latitude, it.longitude) } ?: CurrentLocationResult.Unavailable)
            }
            .addOnFailureListener { onResult(CurrentLocationResult.Unavailable) }
    }
    client.getCurrentLocation(priority, CancellationTokenSource().token)
        .addOnSuccessListener { location ->
            if (location != null) onResult(CurrentLocationResult.Found(location.latitude, location.longitude))
            else fallBackToLastKnown()
        }
        .addOnFailureListener { fallBackToLastKnown() }
}
