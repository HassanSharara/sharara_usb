import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:sharara_usb/models/usb/usb.dart';

import 'sharara_usb_method_channel.dart';

abstract class ShararaUsbPlatform extends PlatformInterface {
  /// Constructs a ShararaUsbPlatform.
  ShararaUsbPlatform() : super(token: _token);

  static final Object _token = Object();

  static ShararaUsbPlatform _instance = MethodChannelShararaUsb();

  /// The default instance of [ShararaUsbPlatform] to use.
  ///
  /// Defaults to [MethodChannelShararaUsb].
  static ShararaUsbPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [ShararaUsbPlatform] when
  /// they register themselves.
  static set instance(ShararaUsbPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
  Future<List<Object?>?> getConnectedUsbListAsMapObjects() {
    throw UnimplementedError('getConnectedUsbList() has not been implemented.');
  }

  Future<List<Object?>?> getConnectedUsbListAsMapObject() {
    throw UnimplementedError('getConnectedUsbList() has not been implemented.');
  }
  Future<List<UsbDevice>?> getConnectedUsbList() {
    throw UnimplementedError('getConnectedUsbList() has not been implemented.');
  }


  Future<bool> connectToDevice(final UsbDevice device);
  Future<bool> isDeviceConnected(final UsbDevice device);
  Future<bool> writeData(final UsbDevice device,List<int> data);
  Future<dynamic> readData(final UsbDevice device);

  Future<void> closeAllServices();

}
