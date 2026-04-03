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
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.MethodCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class UsbHandler(
    private val flutterPlugin: FlutterPlugin.FlutterPluginBinding
) {

    private val usbManager: UsbManager =
        flutterPlugin.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val ioScope = CoroutineScope(Dispatchers.IO) + SupervisorJob()

    // Guards usbConnection and connectedDevice
    private val usbMutex = Mutex()

    @Volatile private var usbConnection: UsbDeviceConnection? = null
    @Volatile private var connectedDevice: UsbDevice? = null

    // -------------------------------------------------------------------------
    // Public entry point — always called on the main thread by Flutter
    // -------------------------------------------------------------------------

    fun call(call: MethodCall, result: Result) {
        when (call.method) {
            "getDevices"       -> handleGetDevices(result)
            "connectToDevice"  -> handleConnectToDevice(call, result)
            "writeDataTo"      -> writeDataTo(call, result)
            "readDataFrom"     -> readDataFrom(call, result)
            "isDeviceConnected"-> result.success(isDeviceConnected(call))
        }
    }

    // -------------------------------------------------------------------------
    // getDevices
    // -------------------------------------------------------------------------

    private fun handleGetDevices(result: Result) {
        result.success(usbManager.deviceList.values.map { it.toMap() })
    }

    // -------------------------------------------------------------------------
    // isDeviceConnected  — safe snapshot read
    // -------------------------------------------------------------------------

    private fun isDeviceConnected(call: MethodCall): Boolean {
        // Take a stable snapshot under the mutex on the calling (main) thread.
        // Because Mutex is a coroutine construct we use runBlocking only for
        // the tiny snapshot; no IO happens here.
        val snapshot = connectedDevice ?: return false
        val connection = usbConnection ?: return false

        val data = call.arguments as? Map<*, *> ?: return false
        val productId = data["product_id"] as? Int ?: return false
        val vendorId  = data["vendor_id"]  as? Int ?: return false

        return snapshot.contains(productId, vendorId) && connection.fileDescriptor != -1
    }

    // -------------------------------------------------------------------------
    // connectToDevice
    // -------------------------------------------------------------------------

    private fun handleConnectToDevice(call: MethodCall, result: Result) {
        val args      = call.arguments as? Map<*, *> ?: run { result.error("10", "Invalid arguments", ""); return }
        val productId = args["product_id"] as? Int   ?: run { result.error("11", "Missing product_id", ""); return }
        val vendorId  = args["vendor_id"]  as? Int   ?: run { result.error("12", "Missing vendor_id",  ""); return }

        connectToDevice(productId, vendorId) { err ->
            // connectToDevice guarantees this is called on the main thread
            if (err != null) err.parseToResult(result) else result.success(true)
        }
    }

    /**
     * [callback] is always invoked on the **main** thread exactly once.
     */
    private fun connectToDevice(
        productId: Int,
        vendorId: Int,
        callback: (ConnectErrorResult?) -> Unit
    ) {
        val targetDevice = usbManager.deviceList.values
            .find { it.productId == productId && it.vendorId == vendorId }

        if (targetDevice == null) {
            callback(ConnectErrorResult("40", "Device Not Found",
                "No device matches PID: $productId VID: $vendorId"))
            return
        }

        // Already connected to the same device?
        if (connectedDevice?.isTheSame(targetDevice) == true && usbConnection != null) {
            callback(null)
            return
        }

        if (!usbManager.hasPermission(targetDevice)) {
            // requestUsbPermissionIfNeeded must be called on the main thread — we are there.
            requestUsbPermissionIfNeeded(
                device = targetDevice,
                onConnectionError = { err -> callback(err) }
            ) {
                openDeviceInternal(targetDevice, callback)
            }
        } else {
            openDeviceInternal(targetDevice, callback)
        }
    }

    // -------------------------------------------------------------------------
    // Permission helper — called & broadcasts received on the main thread
    // -------------------------------------------------------------------------

    private inline fun requestUsbPermissionIfNeeded(
        device: UsbDevice,
        crossinline onConnectionError: (ConnectErrorResult?) -> Unit,
        crossinline callback: () -> Unit
    ) {
        val context = flutterPlugin.applicationContext

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                ctx?.unregisterReceiver(this)

                if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    closeAllConnections()
                    onConnectionError(ConnectErrorResult("50", "Device detached",
                        "USB device was detached before permission was granted"))
                    return
                }

                val granted = intent?.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false) ?: false
                if (!granted) {
                    onConnectionError(ConnectErrorResult("20", "Permission denied",
                        "You need to grant USB permission first"))
                    return
                }

                callback()
            }
        }

        val filter = IntentFilter().apply {
            addAction(USB_PERMISSION_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)

        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(USB_PERMISSION_ACTION), PendingIntent.FLAG_IMMUTABLE)
        usbManager.requestPermission(device, pendingIntent)
    }

    // -------------------------------------------------------------------------
    // Open device — runs on IO, reports back on main
    // -------------------------------------------------------------------------

    private fun openDeviceInternal(
        device: UsbDevice,
        callback: (ConnectErrorResult?) -> Unit
    ) {
        ioScope.launch {
            usbMutex.withLock {
                try {
                    usbConnection?.close()
                    usbConnection = null
                    connectedDevice = null

                    val connection = usbManager.openDevice(device)
                    if (connection != null) {
                        usbConnection = connection
                        connectedDevice = device
                        mainThread { callback(null) }
                    } else {
                        mainThread {
                            callback(ConnectErrorResult("41", "Open failed",
                                "Could not open USB connection"))
                        }
                    }
                } catch (e: Exception) {
                    mainThread {
                        callback(ConnectErrorResult("42", "Connection error",
                            e.message ?: "Unknown error"))
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // writeDataTo
    // -------------------------------------------------------------------------

    private fun writeDataTo(call: MethodCall, result: Result) {
        val args = call.arguments as? Map<*, *> ?: run {
            result.error("11", "Invalid arguments", "Expected a map"); return
        }

        val data      = (args["data"] as? List<*>)?.map { (it as Number).toByte() }?.toByteArray()
            ?: run { result.error("11", "Missing data", ""); return }
        val productId = args["product_id"] as? Int
            ?: run { result.error("11", "Missing product_id", ""); return }
        val vendorId  = args["vendor_id"]  as? Int
            ?: run { result.error("11", "Missing vendor_id", ""); return }

        val requestedInterfaceId    = args["interface_id"]     as? Int
        val requestedEndpointAddr   = args["endpoint_address"] as? Int

        // Single-use flag so result is resolved exactly once
        val resultOnce = SingleUseResult(result)

        checkConnections(
            onError = { err ->
                // called on main thread
                if (resultOnce.tryResolve()) {
                    if (err != null) err.parseToResult(result)
                    else result.error("79", "NOT_CONNECTED",
                        "connect to the device first and then try to write data")
                }
            }
        ) { connection, device ->
            // IO thread — no Flutter result calls here without mainThread { }

            if (!device.contains(productId, vendorId)) {
                mainThread {
                    if (resultOnce.tryResolve()) {
                        result.error("79", "NOT_CONNECTED",
                            "Connected device does not match requested device")
                    }
                }
                return@checkConnections
            }

            var sIFace: UsbInterface? = null
            var endpoint: UsbEndpoint? = null

            if (requestedInterfaceId != null && requestedEndpointAddr != null) {
                sIFace   = device.findInterfaceById(requestedInterfaceId)
                endpoint = sIFace?.findEndpointByAddress(requestedEndpointAddr)
            }

            if (sIFace == null || endpoint == null) {
                val fallback = findWritableEndpoint(device)
                if (fallback != null) {
                    sIFace   = fallback.first
                    endpoint = fallback.second
                }
            }

            if (sIFace == null || endpoint == null) {
                mainThread {
                    if (resultOnce.tryResolve())
                        result.error("66", "No writable endpoint found",
                            "Could not find a valid USB interface for writing.")
                }
                return@checkConnections
            }

            val capturedIFace    = sIFace
            val capturedEndpoint = endpoint

            try {
                if (connection.claimInterface(capturedIFace, true)) {
                    val transfer = connection.bulkTransfer(
                        capturedEndpoint, data, data.size, 10_000)
                    mainThread {
                        if (resultOnce.tryResolve()) {
                            if (transfer >= 0) result.success(true)
                            else result.error("65", "Write failed",
                                "Transfer returned code: $transfer")
                        }
                    }
                } else {
                    mainThread {
                        if (resultOnce.tryResolve())
                            result.error("62", "Claim failed",
                                "Could not claim interface ${capturedIFace.id}")
                    }
                }
            } catch (e: Exception) {
                mainThread {
                    if (resultOnce.tryResolve())
                        result.error("80", e.message ?: "Unknown error", e.toString())
                }
            } finally {
                try { connection.releaseInterface(capturedIFace) } catch (_: Exception) {}
            }
        }
    }

    private fun UsbDevice.findInterfaceById(interfaceId: Int): UsbInterface? =
        (0 until interfaceCount).map { getInterface(it) }.firstOrNull { it.id == interfaceId }

    private fun UsbInterface.findEndpointByAddress(address: Int): UsbEndpoint? =
        (0 until endpointCount).map { getEndpoint(it) }.firstOrNull { it.address == address }

    private fun findWritableEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iFace = device.getInterface(i)
            for (j in 0 until iFace.endpointCount) {
                val ep = iFace.getEndpoint(j)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT) {
                    return Pair(iFace, ep)
                }
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // readDataFrom
    // -------------------------------------------------------------------------

    private fun readDataFrom(call: MethodCall, result: Result) {
        val args = call.arguments as? Map<*, *> ?: run {
            result.error("70", "Invalid arguments", "Expected a map of arguments"); return
        }

        val interFaceId     = args["interface_id"]     as? Int ?: run {
            result.error("71", "Missing interface_id", "interface_id must be provided"); return
        }
        val endpointAddress = args["endpoint_address"] as? Int ?: run {
            result.error("72", "Missing endpoint_address", "endpoint_address must be provided"); return
        }

        val size           = (args["size"]          as? Int)?.takeIf { it > 0 } ?: 512
        val timeout        = (args["timeout"]        as? Int)?.coerceAtLeast(0)  ?: 2000
        val transferType   = (args["transfer_type"]  as? String)?.lowercase()    ?: "auto"
        val controlParams  =  args["control_params"] as? Map<*, *>

        val resultOnce = SingleUseResult(result)

        checkConnections(
            onError = { err ->
                if (resultOnce.tryResolve()) {
                    err?.parseToResult(result)
                        ?: result.error("60", "Not connected", "No active USB connection")
                }
            }
        ) { connection, device ->
            // IO thread

            val selectedInterface = device.findInterfaceById(interFaceId)
            if (selectedInterface == null) {
                mainThread {
                    if (resultOnce.tryResolve())
                        result.error("73", "Invalid interface id", "No such interface")
                }
                return@checkConnections
            }

            if (!connection.claimInterface(selectedInterface, true)) {
                mainThread {
                    if (resultOnce.tryResolve())
                        result.error("74", "Could not claim interface", "Please retry")
                }
                return@checkConnections
            }

            val endpoint = selectedInterface.findEndpointByAddress(endpointAddress)
            if (endpoint == null) {
                try { connection.releaseInterface(selectedInterface) } catch (_: Exception) {}
                mainThread {
                    if (resultOnce.tryResolve())
                        result.error("75", "Invalid endpoint address", "No such endpoint")
                }
                return@checkConnections
            }

            try {
                when (val readResult = performTransfer(
                    connection    = connection,
                    endpoint      = endpoint,
                    transferType  = transferType,
                    controlParams = controlParams,
                    bufferSize    = size,
                    timeout       = timeout
                )) {
                    is TransferResult.Success -> mainThread {
                        if (resultOnce.tryResolve()) result.success(readResult.data.toList())
                    }
                    is TransferResult.Failure -> mainThread {
                        if (resultOnce.tryResolve())
                            result.error("76", "Read failed", readResult.reason)
                    }
                }
            } finally {
                try { connection.releaseInterface(selectedInterface) } catch (_: Exception) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Transfer helpers
    // -------------------------------------------------------------------------

    private sealed class TransferResult {
        data class Success(val data: ByteArray) : TransferResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                return (other as Success).data.contentEquals(data)
            }
            override fun hashCode() = data.contentHashCode()
        }
        data class Failure(val reason: String) : TransferResult()
    }

    private fun performTransfer(
        connection:    UsbDeviceConnection,
        endpoint:      UsbEndpoint,
        transferType:  String,
        controlParams: Map<*, *>?,
        bufferSize:    Int,
        timeout:       Int
    ): TransferResult {
        val buffer = ByteArray(bufferSize)

        return when {
            transferType == "control" && controlParams != null -> {
                val requestType = controlParams["requestType"] as? Int ?: 0
                val request     = controlParams["request"]     as? Int ?: 0
                val value       = controlParams["value"]       as? Int ?: 0
                val index       = controlParams["index"]       as? Int ?: 0
                val received    = connection.controlTransfer(
                    requestType, request, value, index, buffer, bufferSize, timeout)
                if (received >= 0) TransferResult.Success(buffer.copyOf(received))
                else TransferResult.Failure("Control transfer failed with code $received")
            }
            transferType == "bulk" ||
                    (transferType == "auto" && endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) -> {
                val received = connection.bulkTransfer(endpoint, buffer, bufferSize, timeout)
                handleTransferResult(received, buffer)
            }
            transferType == "interrupt" ||
                    (transferType == "auto" && endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) -> {
                val received = connection.bulkTransfer(endpoint, buffer, bufferSize, timeout)
                handleTransferResult(received, buffer)
            }
            else -> TransferResult.Failure("Unsupported transfer type: $transferType")
        }
    }

    private fun handleTransferResult(received: Int, buffer: ByteArray): TransferResult =
        if (received >= 0) TransferResult.Success(buffer.copyOf(received))
        else TransferResult.Failure("Transfer failed with code $received")

    // -------------------------------------------------------------------------
    // checkConnections — mutex held only for snapshot; callback runs under lock
    // on IO thread.  onError is always dispatched to main.
    // -------------------------------------------------------------------------

    private fun checkConnections(
        onError:  (ConnectErrorResult?) -> Unit,
        callback: suspend (UsbDeviceConnection, UsbDevice) -> Unit
    ) {
        ioScope.launch {
            usbMutex.withLock {
                val connection = usbConnection
                val device     = connectedDevice
                if (connection == null || device == null) {
                    mainThread {
                        onError(ConnectErrorResult("60", "Invalid connection",
                            "USB connection is no longer valid. Please reconnect."))
                    }
                    return@withLock
                }
                callback(connection, device)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Suspend helper — switch to main thread
    // -------------------------------------------------------------------------

    private suspend inline fun mainThread(crossinline cb: () -> Unit) {
        withContext(Dispatchers.Main) { cb() }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    fun cleanUp() {
        closeAllConnections()
    }

    private fun closeAllConnections() {
        ioScope.launch {
            usbMutex.withLock {
                usbConnection?.close()
                usbConnection  = null
                connectedDevice = null
            }
        }
    }

    companion object {
        private const val USB_PERMISSION_ACTION = "com.sharara.usb.USB_PERMISSION"
    }
}

// ---------------------------------------------------------------------------------
// SingleUseResult — prevents double-resolve of a Flutter Result
// ---------------------------------------------------------------------------------

private class SingleUseResult(val result: Result) {
    private val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    fun tryResolve(): Boolean = resolved.compareAndSet(false, true)
}

// ---------------------------------------------------------------------------------
// Extension functions — unchanged so Flutter mapping stays identical
// ---------------------------------------------------------------------------------

fun UsbDevice.isTheSame(other: UsbDevice): Boolean =
    other.productId == productId && vendorId == other.vendorId

fun UsbDevice.contains(pId: Int, vId: Int): Boolean =
    productId == pId && vendorId == vId

fun UsbDevice.toMap(): Map<String, *> {
    val interfaces = (0 until interfaceCount).map { getInterface(it).toMap() }
    return mapOf(
        "id"               to deviceId.toString(),
        "name"             to deviceName,
        "protocol"         to deviceProtocol,
        "manufacture_name" to manufacturerName,
        "product_name"     to productName,
        "vendor_id"        to vendorId,
        "product_id"       to productId,
        "interfaces"       to interfaces
    )
}

fun UsbEndpoint.toMap(): Map<String, *> = mapOf(
    "type"            to type,
    "direction"       to direction,
    "endpoint_number" to endpointNumber,
    "address"         to address,
    "interval"        to interval,
    "max_packet_size" to maxPacketSize
)

fun UsbInterface.toMap(): Map<String, *> {
    val endpoints = (0 until endpointCount).map { getEndpoint(it).toMap() }
    return mapOf(
        "id"                  to id,
        "name"                to name,
        "protocol"            to interfaceProtocol,
        "interface_class"     to interfaceClass,
        "interface_subclass"  to interfaceSubclass,
        "endpoints"           to endpoints
    )
}

class ConnectErrorResult(
    private val code:        String,
    private val message:     String,
    private val description: String
) {
    fun parseToResult(result: Result) {
        result.error(code, message, description)
    }
}