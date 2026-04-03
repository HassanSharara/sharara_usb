#ifndef FLUTTER_PLUGIN_SHARARA_USB_PLUGIN_H_
#define FLUTTER_PLUGIN_SHARARA_USB_PLUGIN_H_

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <memory>
#include "src/usb_handler.h"

namespace sharara_usb {

class ShararaUsbPlugin : public flutter::Plugin {


 public:
 static void RegisterWithRegistrar(flutter::PluginRegistrarWindows *registrar);

    std::unique_ptr<UsbHandler> usbHandler;
    ShararaUsbPlugin(flutter::PluginRegistrarWindows* registrar);
   virtual ~ShararaUsbPlugin();

  // Disallow copy and assign.
  ShararaUsbPlugin(const ShararaUsbPlugin&) = delete;
  ShararaUsbPlugin& operator=(const ShararaUsbPlugin&) = delete;

  // Called when a method is called on this plugin's channel from Dart.
  void HandleMethodCall(
      const flutter::MethodCall<flutter::EncodableValue> &method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
};

}  // namespace sharara_usb

#endif  // FLUTTER_PLUGIN_SHARARA_USB_PLUGIN_H_
