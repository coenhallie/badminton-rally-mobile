package com.badmintontracker.android.invites

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Launches Google Play Services' zero-permission QR scanner.
 * Silently ignores cancellation and invokes [onUnavailable] if Play Services
 * code scanning cannot run on the device.
 */
fun launchQrCodeScan(
    context: Context,
    onScanned: (String) -> Unit,
    onUnavailable: () -> Unit,
) {
    runCatching {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.let(onScanned)
            }
            .addOnFailureListener {
                onUnavailable()
            }
    }.onFailure {
        onUnavailable()
    }
}
