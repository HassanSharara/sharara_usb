# sharara_usb 🚀

[![Pub Version](https://img.shields.io/pub/v/sharara_usb?style=for-the-badge&logo=dart)](https://pub.dev/packages/sharara_usb)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Windows-blue?style=for-the-badge)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg?style=for-the-badge)](https://opensource.org/licenses/MIT)

**sharara_usb** is a high-performance Flutter plugin designed for direct hardware interaction with USB peripherals. Engineered specifically for POS systems and thermal printers, it provides a unified, low-latency interface for device discovery and raw data transmission.

---

## ✨ Features

* **🔍 Device Discovery:** Scan and identify all connected USB peripherals with detailed hardware IDs (PID/VID).
* **🔗 Seamless Connection:** Robust handshake management for stable hardware communication.
* **⚡ Raw Data Streaming:** Optimized for sending `List<int>` byte arrays (ESC/POS, TSPL, ZPL).
* **🖥️ Native Performance:** Leverages C++/Win32 for Windows and NDK/Java for Android.
* **🛠️ Clean Architecture:** Built on the `PlatformInterface` pattern for predictable cross-platform behavior.

---

## 📦 Installation

Add the following to your `pubspec.yaml`:

```yaml
dependencies:
  sharara_usb: ^1.0.0
```

Then fetch the package:

```bash
flutter pub get
```

---

## 🚀 Quick Start

### 1. Retrieve Connected Devices

Get a list of all available USB devices as structured `UsbDevice` objects.

```dart
import 'package:sharara_usb/sharara_usb.dart';

final List<UsbDevice>? devices =
    await ShararaUsbPlatform.instance.getConnectedUsbList();

if (devices != null) {
  for (var device in devices) {
    print('Device Name: ${device.name}');
    print('Vendor ID: ${device.vendorId}');
  }
}
```

---

### 2. Establish Connection

Connect to a specific device before initiating a print job or data transfer.

```dart
bool success =
    await ShararaUsbPlatform.instance.connectToDevice(selectedDevice);

if (success) {
  print("Connected to ${selectedDevice.name} successfully.");
}
```

---

### 3. Send Raw Bytes (Printing)

Send raw ESC/POS commands directly to your thermal printer.

```dart
// Example: ESC/POS Initialize + Print 'Hello World'
final profile = await CapabilityProfile.load().timeout(const Duration(seconds: 3));
final generator = Generator(PaperSize.mm80, profile);
final List<int> data = [];
data.addAll(generator.cut());
data.addAll(generator.text("Hello World"));
data.addAll(generator.feed(2));
data.addAll(generator.cut());
await widget.device.writeData(data);
```

---

## 🛠 Platform Support

| Feature        | Android | Windows | iOS |
|---------------|--------|--------|-----|
| USB Discovery | ✅     | ✅     | ❌  |
| Data Writing  | ✅     | ✅     | ❌  |
| Build Core    | JNI / USB-Host | C++ / Win32 | N/A |

---

## ⚠️ Note on iOS

iOS restricts raw USB communication to MFi-certified hardware only via the ExternalAccessory framework.

For iOS compatibility, we recommend utilizing:
- Network (TCP/IP)
- BLE (Bluetooth Low Energy)

---

## 🔌 Technical Model: UsbDevice

The plugin returns a standardized model for easy hardware filtering:

| Property   | Type   | Description |
|------------|--------|-------------|
| name       | String | Manufacturer-defined device name |
| vendorId   | int    | Unique identifier for the manufacturer |
| productId  | int    | Unique identifier for the specific product |
| deviceId   | String | System-level unique path/ID |

---

## 🤝 Contributing

We welcome optimizations, especially regarding low-level buffer management and additional platform support.

1. Fork the project
2. Create your Feature Branch (`git checkout -b feature/NewHardwareSupport`)
3. Commit your changes (`git commit -m 'Add support for X'`)
4. Push to the branch (`git push origin feature/NewHardwareSupport`)
5. Open a Pull Request

---

## 📄 License

Distributed under the MIT License.

---

## 👨‍💻 Author

**Hassan Sharara**
Specialist in High-Performance Systems & Systems Programming
Developed with a focus on speed and reliability for modern POS ecosystems.