// PermissionUtils - Utility methods for runtime permission handling.
// Created by Siyabonga Popela. Edited by Tasima Hapazari.
// Date: 2025-09-10
package utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Centralizes runtime permission lists so the app can request them consistently.
 */
object PermissionUtils {

    // getLocationPermissions: returns the coarse/fine location set required by Nearby.
fun getLocationPermissions(): Set<String> = setOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    // getBluetoothPermissions: provides appropriate Bluetooth permissions depending on SDK level.
fun getBluetoothPermissions(): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            setOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

    // getNearbyPermissions: aggregates location, Bluetooth, and Nearby Wi-Fi permissions when needed.
fun getNearbyPermissions(): Set<String> {
        val permissions = getLocationPermissions() + getBluetoothPermissions()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions + Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            permissions
        }
    }

    // getCameraPermissions: returns the camera permission set.
fun getCameraPermissions(): Set<String> = setOf(Manifest.permission.CAMERA)

    // getStoragePermissions: builds the correct media/storage permissions respecting Android version.
fun getStoragePermissions(): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            buildSet {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }

    // getMediaAndCameraPermissions: combines camera and media permissions for sharing.
fun getMediaAndCameraPermissions(): Set<String> =
        getCameraPermissions() + getStoragePermissions()

    // getAudioPermissions: returns record-audio permission set.
fun getAudioPermissions(): Set<String> = setOf(Manifest.permission.RECORD_AUDIO)

    // getAllRuntimePermissions: merges all runtime permissions the app may request.
fun getAllRuntimePermissions(): Set<String> =
        (getNearbyPermissions() + getMediaAndCameraPermissions() + getAudioPermissions()).toSet()

    // getMissingPermissions: filters a permission collection to only those not yet granted.
fun getMissingPermissions(context: Context, permissions: Collection<String>): List<String> =
        permissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }

    // getAllMissingPermissions: convenience method returning every missing runtime permission.
fun getAllMissingPermissions(context: Context): List<String> =
        getMissingPermissions(context, getAllRuntimePermissions())

    // hasAllPermissions: checks if all provided permissions are currently granted.
fun hasAllPermissions(context: Context, permissions: Collection<String>): Boolean =
        getMissingPermissions(context, permissions).isEmpty()
}



