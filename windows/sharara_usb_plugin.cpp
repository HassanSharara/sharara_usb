#include "sharara_usb_plugin.h"

// This must be included before many other Windows headers.
#include <windows.h>
#include <VersionHelpers.h>

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>

// FIX 1: Include the header, not the .cpp
#include "src/usb_handler.h"

#include <memory>
#include <sstream>

namespace sharara_usb {

    void ShararaUsbPlugin::RegisterWithRegistrar(
            flutter::PluginRegistrarWindows *registrar) {
        auto channel =
                std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
                        registrar->messenger(), "sharara_usb",
                                &flutter::StandardMethodCodec::GetInstance());

        auto plugin = std::make_unique<ShararaUsbPlugin>(registrar);

        channel->SetMethodCallHandler(
                [plugin_ptr = plugin.get()](const auto &call, auto result) {
                    // MATCHING NAME: usbHandler (as defined in your .h)
                    plugin_ptr->usbHandler->call(call, std::move(result));
                });

        registrar->AddPlugin(std::move(plugin));
    }

// FIX 2: Ensure the initializer name 'usbHandler' matches the header EXACTLY
    ShararaUsbPlugin::ShararaUsbPlugin(flutter::PluginRegistrarWindows *registrar)
            : usbHandler(std::make_unique<UsbHandler>(registrar)) {}

    ShararaUsbPlugin::~ShararaUsbPlugin() {}

// FIX 3: You declared this in the header, so you MUST define it here
// to avoid Linker Error (LNK2019)
    void ShararaUsbPlugin::HandleMethodCall(
            const flutter::MethodCall<flutter::EncodableValue> &method_call,
            std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
             result->NotImplemented();
    }

}  // namespace sharara_usb  <-- FIX 4: Added this missing closing brace