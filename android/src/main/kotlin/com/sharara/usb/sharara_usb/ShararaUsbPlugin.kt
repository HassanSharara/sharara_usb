package com.sharara.usb.sharara_usb

import android.os.Build
import androidx.annotation.RequiresApi
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result



@RequiresApi(Build.VERSION_CODES.TIRAMISU)
/** ShararaUsbPlugin */
class ShararaUsbPlugin: FlutterPlugin, MethodCallHandler {
    private lateinit var channel : MethodChannel
    private lateinit var flutterPluginBinding:FlutterPlugin.FlutterPluginBinding
    private var _usbHandler: UsbHandler? = null


    private var usbHandler: UsbHandler
        get() {
            _usbHandler=_usbHandler?:UsbHandler(flutterPluginBinding)
            return _usbHandler as UsbHandler
        }
        set(value)  { _usbHandler = value }


    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        this.flutterPluginBinding = flutterPluginBinding
        _usbHandler = UsbHandler(flutterPluginBinding)
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "sharara_usb")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {

        when  (call.method) {
            "dispose_all"->{
                cleanUp()
            }
            else -> usbHandler.call(call,result)
        }
    }

    private fun cleanUp(){
        _usbHandler?.cleanUp()
        _usbHandler = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        cleanUp()
        channel.setMethodCallHandler(null)
    }
}
