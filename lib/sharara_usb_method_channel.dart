import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'models/usb/usb.dart';
import 'sharara_usb_platform_interface.dart';

/// An implementation of [ShararaUsbPlatform] that uses method channels.
class MethodChannelShararaUsb extends ShararaUsbPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('sharara_usb');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }

  @override
  Future<List<Object?>?> getConnectedUsbListAsMapObjects() async {
    final List<Object?>? data = await methodChannel.invokeMethod('getDevices');
    return data;
  }


  @override
  Future<List<UsbDevice>?> getConnectedUsbList()async{
    final l = await getConnectedUsbListAsMapObjects();
    if( l == null ) return null;
    final List<UsbDevice> devices = [];
    for(final m in l ) {
      try {
        final UsbDevice d = UsbDevice.fromMap(Map<String, dynamic>.from(m as Map));
        devices.add(d);
      }catch(_){ continue;}
    }
    return devices;
  }

  @override
  Future<bool> connectToDevice(UsbDevice device) async {
    return await methodChannel.invokeMethod("connectToDevice",device.innerMap);
  }

  @override
  Future readData(UsbDevice device) {
    // TODO: implement readData
    throw UnimplementedError();
  }

  @override
  Future<bool> writeData(UsbDevice device,final List<int> data) async{
    final endpoint = device.findPrinterWriteEndpoint();

    if( endpoint == null ) throw Exception("dose not have printer write endpoint");
    return await methodChannel.invokeMethod("writeDataTo", {
      "data":data,
      "id":device.id,
      "product_id":device.productId,
      "vendor_id":device.vendorId
    });
  }

  @override
  Future<bool> isDeviceConnected(UsbDevice device) async{
    return await methodChannel.invokeMethod("isDeviceConnected",device.innerMap);
  }

  @override
  Future<void> closeAllServices() async {
    await methodChannel.invokeMethod("dispose_all");
  }


}
