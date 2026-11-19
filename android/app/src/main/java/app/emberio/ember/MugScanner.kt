package app.emberio.ember

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** An Ember device seen while scanning during setup. */
data class FoundMug(
    val address: String,
    val name: String,
    val model: MugModel,
    val rssi: Int,
)

/**
 * Scans for Ember devices for the setup screen. A mug that's never been paired only shows
 * up while in pairing mode (hold the button on the bottom until the light flashes blue).
 */
@SuppressLint("MissingPermission") // The setup screen asks for BLUETOOTH_SCAN first.
class MugScanner(private val context: Context) {

    fun scan(): Flow<FoundMug> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is off"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val manufacturer = record?.getManufacturerSpecificData(EmberProtocol.MANUFACTURER_ID)
                val services = record?.serviceUuids?.map { it.uuid }.orEmpty()
                val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
                if (!MugModel.isEmber(manufacturer, services, name)) return
                val model = MugModel.detect(manufacturer, services, name)
                trySend(
                    FoundMug(
                        address = result.device.address,
                        name = name?.takeIf { it.isNotBlank() } ?: model.displayName,
                        model = model,
                        rssi = result.rssi,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w("MugScanner", "scan failed: $errorCode")
                close(IllegalStateException("Scan failed ($errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }
        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }
}
