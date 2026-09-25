package com.badmintontracker.android.scoring

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Where the phone is, as a name a person would say: "Amsterdam". Only ever used to
 * prefill a new match's name, so every way this can fail - no permission, location
 * switched off, no fix, no geocoder, no network - is the same answer, null, and the
 * match is named for the time alone.
 */
fun interface PlaceNames {
    suspend fun current(): String?
}

class AndroidPlaceNames(context: Context) : PlaceNames {

    private val app = context.applicationContext

    override suspend fun current(): String? = withTimeoutOrNull(TIMEOUT_MS) {
        if (!hasPermission(app)) return@withTimeoutOrNull null
        val location = locate() ?: return@withTimeoutOrNull null
        placeName(location)
    }

    @SuppressLint("MissingPermission")   // checked by current(), the only caller
    private suspend fun locate(): Location? {
        val manager = app.getSystemService(LocationManager::class.java) ?: return null
        // Coarse is all a town name needs, and the network provider is the one that
        // answers indoors, which is where badminton is played.
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        if (last != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return last
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            runCatching {
                manager.getCurrentLocation(provider, signal, app.mainExecutor) { continuation.resume(it) }
            }.onFailure { continuation.resume(null) }
        }
    }

    private suspend fun placeName(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(app, Locale.getDefault())
        val addresses: List<Address> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(
                        location.latitude, location.longitude, 1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(results: MutableList<Address>) = continuation.resume(results)
                            override fun onError(errorMessage: String?) = continuation.resume(emptyList())
                        },
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1) }
                        .getOrNull().orEmpty()
                }
            }
        val address = addresses.firstOrNull() ?: return null
        // The town, falling back outwards. Never the street: a match name is synced
        // and shared, and a home address has no business in one.
        return listOf(address.locality, address.subAdminArea, address.adminArea)
            .firstOrNull { !it.isNullOrBlank() }
    }

    companion object {
        const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

        /** Long enough for a cold network fix, short enough that nobody is still on the form. */
        private const val TIMEOUT_MS = 10_000L

        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}
