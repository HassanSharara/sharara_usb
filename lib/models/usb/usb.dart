import 'package:sharara_usb/sharara_usb.dart';

class UsbDevice {
 // Change ID to String to support Windows Printer Names
 final String id;
 final List<UsbInterface> interfaces;
 final int? productId;
 final int? vendorId;
 final String? productName;
 final String? manufactureName;
 final int? protocol;
 Map? innerMap;

 UsbDevice({
  required this.id,
  required this.interfaces,
  this.productId,
  this.vendorId,
  this.productName,
  this.manufactureName,
  this.protocol,
 });

 String get hexVendorId =>
     vendorId != null
         ? '0x${vendorId!.toRadixString(16).padLeft(4, '0').toUpperCase()}'
         : 'N/A';

 String get hexProductId =>
     productId != null
         ? '0x${productId!.toRadixString(16).padLeft(4, '0').toUpperCase()}'
         : 'N/A';

 factory UsbDevice.fromMap(Map<String, dynamic> map) {
  return UsbDevice(
   // Use .toString() directly because 'id' is now the Printer Name
   id: map['id']?.toString() ?? '',
   productId: _toIntNullable(map['product_id']),
   vendorId: _toIntNullable(map['vendor_id']),
   productName: map['product_name']?.toString(),
   manufactureName: map['manufacture_name']?.toString(),
   protocol: _toIntNullable(map['protocol']),
   interfaces: _toList(map['interfaces'])
       .map((e) => UsbInterface.fromMap(_safeMap(e)))
       .toList(),
  )..innerMap = map;
 }

 /// Since Spooler API handles endpoints internally, we check if
 /// the device is a Printer class and return a "Virtual" endpoint
 /// so the existing logic doesn't break.
 UsbEndpoint? findPrinterWriteEndpoint() {
  for (var interface in interfaces) {
   if (interface.interfaceClass == 7) {
    // Return first endpoint or a mock one if the list is empty
    if (interface.endpoints.isNotEmpty) {
     return interface.endpoints.first;
    }
    // Return a mock endpoint so writeData doesn't return false early
    return UsbEndpoint(
        address: 1,
        direction: 0,
        endpointNumber: 1,
        interval: 0,
        maxPacketSize: 64,
        type: 2
    );
   }
  }
  return null;
 }

 Future<bool> get isConnected async =>
     await ShararaUsb.platform.isDeviceConnected(this);

 Future<bool> writeData(final List<int> data) async {
  return await ShararaUsb.platform.writeData(this, data);
 }

 Future<bool> connect() async =>
     await ShararaUsb.platform.connectToDevice(this);
}

class UsbInterface {
 final int id;
 final int interfaceClass;
 final int interfaceSubclass;
 final String? name;
 final int protocol;
 final List<UsbEndpoint> endpoints;

 UsbInterface({
  required this.id,
  required this.interfaceClass,
  required this.interfaceSubclass,
  this.name,
  required this.protocol,
  required this.endpoints,
 });

 bool get isPrinter => interfaceClass == 7;

 factory UsbInterface.fromMap(Map<String, dynamic> map) {
  return UsbInterface(
   id: _toInt(map['id']),
   interfaceClass: _toInt(map['interface_class']),
   interfaceSubclass: _toInt(map['interface_subclass'] ?? 0),
   name: map['name']?.toString(),
   protocol: _toInt(map['protocol'] ?? 0),
   endpoints: _toList(map['endpoints'])
       .map((e) => UsbEndpoint.fromMap(_safeMap(e)))
       .toList(),
  );
 }
}

class UsbEndpoint {
 final int address;
 final int direction;
 final int endpointNumber;
 final int interval;
 final int maxPacketSize;
 final int type;

 UsbEndpoint({
  required this.address,
  required this.direction,
  required this.endpointNumber,
  required this.interval,
  required this.maxPacketSize,
  required this.type,
 });

 factory UsbEndpoint.fromMap(Map<String, dynamic> map) {
  return UsbEndpoint(
   address: _toInt(map['address']),
   direction: _toInt(map['direction']),
   endpointNumber: _toInt(map['endpoint_number']),
   interval: _toInt(map['interval']),
   maxPacketSize: _toInt(map['max_packet_size']),
   type: _toInt(map['type']),
  );
 }
}

// --- Helpers ---

int _toInt(dynamic value) {
 if (value is int) return value;
 return int.tryParse(value?.toString() ?? '') ?? 0;
}

int? _toIntNullable(dynamic value) {
 if (value is int) return value;
 return int.tryParse(value?.toString() ?? '');
}

List<dynamic> _toList(dynamic value) {
 if (value is List) return value;
 return [];
}

Map<String, dynamic> _safeMap(dynamic value) {
 if (value is Map) {
  return value.map((key, val) => MapEntry(key.toString(), val));
 }
 return {};
}