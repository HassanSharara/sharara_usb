package com.sharara.usb.sharara_usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.MethodCall
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UsbHandler(
    private val flutterPlugin: FlutterPlugin.FlutterPluginBinding
) {
    private val usbManager: UsbManager =
        flutterPlugin.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val ioScope = CoroutineScope(Dispatchers.IO) + SupervisorJob()
    private val usbMutex = Mutex()

    @Volatile private var usbConnection: UsbDeviceConnection? = null
    @Volatile private var connectedDevice: UsbDevice? = null

    // Variable chunk size to handle large print jobs (e.g. 68KB images)
    private var chunkSize: Int = 4096

    fun setChunkSize(size: Int) {
        this.chunkSize = if (size > 0) size else 4096
    }

    fun call(call: MethodCall, result: Result) {
        when (call.method) {
            "getDevices" -> handleGetDevices(result)
            "connectToDevice" -> handleConnectToDevice(call, result)
            "writeDataTo" -> writeDataTo(call, result)
            "readDataFrom" -> readDataFrom(call, result)
            "isDeviceConnected" -> result.success(isDeviceConnected(call))
            "set_chunk_size" -> {
                val size = call.argument<Int>("size") ?: 4096
                setChunkSize(size)
                result.success(true)
            }
        }
    }

    private fun handleGetDevices(result: Result) {
        // Run on Main thread to ensure Flutter receives the list correctly
        CoroutineScope(Dispatchers.Main).launch {
            val devices = usbManager.deviceList.values.map { it.toMap() }
            result.success(devices)
        }
    }

    private fun isDeviceConnected(call: MethodCall): Boolean {
        val snapshot = connectedDevice ?: return false
        val connection = usbConnection ?: return false
        val data = call.arguments as? Map<*, *> ?: return false
        val productId = data["product_id"] as? Int ?: return false
        val vendorId = data["vendor_id"] as? Int ?: return false
        return snapshot.contains(productId, vendorId) && connection.fileDescriptor != -1
    }

    private fun handleConnectToDevice(call: MethodCall, result: Result) {
        val args = call.arguments as? Map<*, *> ?: run { result.error("10", "Invalid arguments", ""); return }
        val productId = args["product_id"] as? Int ?: run { result.error("11", "Missing product_id", ""); return }
        val vendorId = args["vendor_id"] as? Int ?: run { result.error("12", "Missing vendor_id", ""); return }

        connectToDevice(productId, vendorId) { err ->
            if (err != null) err.parseToResult(result) else result.success(true)
        }
    }

    private fun connectToDevice(productId: Int, vendorId: Int, callback: (ConnectErrorResult?) -> Unit) {
        val targetDevice = usbManager.deviceList.values.find { it.productId == productId && it.vendorId == vendorId }
        if (targetDevice == null) {
            callback(ConnectErrorResult("40", "Device Not Found", "No device matches PID: $productId VID: $vendorId"))
            return
        }
        if (connectedDevice?.isTheSame(targetDevice) == true && usbConnection != null) {
            callback(null)
            return
        }
        if (!usbManager.hasPermission(targetDevice)) {
            requestUsbPermissionIfNeeded(targetDevice, { err -> callback(err) }) {
                openDeviceInternal(targetDevice, callback)
            }
        } else {
            openDeviceInternal(targetDevice, callback)
        }
    }

    private inline fun requestUsbPermissionIfNeeded(device: UsbDevice, crossinline onConnectionError: (ConnectErrorResult?) -> Unit, crossinline callback: () -> Unit) {
        val context = flutterPlugin.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                ctx?.unregisterReceiver(this)
                if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    closeAllConnections()
                    onConnectionError(ConnectErrorResult("50", "Device detached", "USB device detached"))
                    return
                }
                val granted = intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ?: false
                if (!granted) {
                    onConnectionError(ConnectErrorResult("20", "Permission denied", "Grant USB permission first"))
                    return
                }
                callback()
            }
        }
        val filter = IntentFilter().apply {
            addAction(USB_PERMISSION_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(USB_PERMISSION_ACTION), PendingIntent.FLAG_IMMUTABLE)
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun openDeviceInternal(device: UsbDevice, callback: (ConnectErrorResult?) -> Unit) {
        ioScope.launch {
            usbMutex.withLock {
                try {
                    usbConnection?.close()
                    val connection = usbManager.openDevice(device)
                    if (connection != null) {
                        usbConnection = connection
                        connectedDevice = device
                        withContext(Dispatchers.Main) { callback(null) }
                    } else {
                        withContext(Dispatchers.Main) { callback(ConnectErrorResult("41", "Open failed", "Could not open USB")) }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { callback(ConnectErrorResult("42", "Connection error", e.message ?: "")) }
                }
            }
        }
    }

    private fun writeDataTo(call: MethodCall, result: Result) {
        val args = call.arguments as? Map<*, *> ?: run { result.error("11", "Invalid arguments", ""); return }
        val data = (args["data"] as? List<*>)?.map { (it as Number).toByte() }?.toByteArray() ?: run { result.error("11", "Missing data", ""); return }

        val resultOnce = SingleUseResult(result)

        checkConnections(onError = { err ->
            if (resultOnce.tryResolve()) err?.parseToResult(result) ?: result.error("79", "NOT_CONNECTED", "Connect first")
        }) { connection, device ->
            val fallback = findWritableEndpoint(device) ?: run {
                withContext(Dispatchers.Main) { if (resultOnce.tryResolve()) result.error("66", "No endpoint found", "") }
                return@checkConnections
            }
            val iFace = fallback.first
            val endpoint = fallback.second

            try {
                if (connection.claimInterface(iFace, true)) {
                    var offset = 0
                    var success = true

                    // CHUNKED WRITING - Fixes the 68KB issue
                    while (offset < data.size) {
                        val count = Math.min(chunkSize, data.size - offset)
                        val chunk = data.copyOfRange(offset, offset + count)

                        val transfer = connection.bulkTransfer(endpoint, chunk, chunk.size, 10000)
                        if (transfer < 0) {
                            success = false
                            break
                        }
                        offset += count
                        delay(10) // Small breather for printer buffer
                    }

                    withContext(Dispatchers.Main) {
                        if (resultOnce.tryResolve()) {
                            if (success) result.success(true) else result.error("65", "Write failed", "Transfer error")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { if (resultOnce.tryResolve()) result.error("62", "Claim failed", "") }
                }
            } finally {
                try { connection.releaseInterface(iFace) } catch (_: Exception) {}
            }
        }
    }

    private fun findWritableEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iFace = device.getInterface(i)
            for (j in 0 until iFace.endpointCount) {
                val ep = iFace.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) return Pair(iFace, ep)
            }
        }
        return null
    }

    private fun readDataFrom(call: MethodCall, result: Result) {
        val args = call.arguments as? Map<*, *> ?: run { result.error("70", "Invalid args", ""); return }
        val interFaceId = args["interface_id"] as? Int ?: 0
        val endpointAddress = args["endpoint_address"] as? Int ?: 0
        val size = (args["size"] as? Int) ?: 512
        val timeout = (args["timeout"] as? Int) ?: 2000

        val resultOnce = SingleUseResult(result)

        checkConnections(onError = { err ->
            if (resultOnce.tryResolve()) err?.parseToResult(result) ?: result.error("60", "Not connected", "")
        }) { connection, device ->
            val iFace = device.findInterfaceById(interFaceId) ?: return@checkConnections
            if (connection.claimInterface(iFace, true)) {
                val ep = iFace.findEndpointByAddress(endpointAddress) ?: return@checkConnections
                val buffer = ByteArray(size)
                val received = connection.bulkTransfer(ep, buffer, size, timeout)
                withContext(Dispatchers.Main) {
                    if (resultOnce.tryResolve()) {
                        if (received >= 0) result.success(buffer.copyOf(received).toList())
                        else result.error("76", "Read failed", "")
                    }
                }
                connection.releaseInterface(iFace)
            }
        }
    }

    private fun checkConnections(onError: (ConnectErrorResult?) -> Unit, callback: suspend (UsbDeviceConnection, UsbDevice) -> Unit) {
        ioScope.launch {
            usbMutex.withLock {
                val conn = usbConnection
                val dev = connectedDevice
                if (conn == null || dev == null) {
                    withContext(Dispatchers.Main) { onError(ConnectErrorResult("60", "Invalid connection", "")) }
                } else {
                    callback(conn, dev)
                }
            }
        }
    }

    fun cleanUp() {
        closeAllConnections()
    }

    private fun closeAllConnections() {
        ioScope.launch {
            usbMutex.withLock {
                usbConnection?.close()
                usbConnection = null
                connectedDevice = null
            }
        }
    }

    companion object {
        private const val USB_PERMISSION_ACTION = "com.sharara.usb.USB_PERMISSION"
    }
}

// --- HELPERS (Restore Original toMap keys for discovery) ---

private class SingleUseResult(val result: Result) {
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    fun tryResolve(): Boolean = resolved.compareAndSet(false, true)
}

class ConnectErrorResult(val code: String, val message: String, val description: String) {
    fun parseToResult(result: Result) = result.error(code, message, description)
}

fun UsbDevice.isTheSame(other: UsbDevice): Boolean = other.productId == productId && vendorId == other.vendorId
fun UsbDevice.contains(pId: Int, vId: Int): Boolean = productId == pId && vendorId == vId
fun UsbDevice.findInterfaceById(id: Int): UsbInterface? = (0 until interfaceCount).map { getInterface(it) }.firstOrNull { it.id == id }
fun UsbInterface.findEndpointByAddress(addr: Int): UsbEndpoint? = (0 until endpointCount).map { getEndpoint(it) }.firstOrNull { it.address == addr }

fun UsbDevice.toMap(): Map<String, Any?> {
    val interfaces = (0 until interfaceCount).map { getInterface(it).toMap() }

    // Crucial: Use exact keys your Flutter code expects (e.g., 'manufacture_name')
    return mapOf(
        "id" to deviceId.toString(),
        "name" to deviceName,
        "protocol" to deviceProtocol,
        "vendor_id" to vendorId,
        "product_id" to productId,
        "product_name" to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) productName ?: "" else ""),
        "manufacture_name" to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) manufacturerName ?: "" else ""),
        "interfaces" to interfaces
    )
}

fun UsbInterface.toMap(): Map<String, Any?> {
    val endpoints = (0 until endpointCount).map { getEndpoint(it).toMap() }
    return mapOf(
        "id" to id,
        "name" to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) name ?: "" else ""),
        "protocol" to interfaceProtocol,
        "interface_class" to interfaceClass,
        "interface_subclass" to interfaceSubclass,
        "endpoints" to endpoints
    )
}

fun UsbEndpoint.toMap(): Map<String, Any?> {
    return mapOf(
        "address" to address,
        "endpoint_number" to endpointNumber,
        "direction" to direction,
        "type" to type,
        "max_packet_size" to maxPacketSize,
        "interval" to interval
    )
}