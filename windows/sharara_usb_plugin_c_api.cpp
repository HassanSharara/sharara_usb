#include "include/sharara_usb/sharara_usb_plugin_c_api.h"

#include <flutter/plugin_registrar_windows.h>

#include "sharara_usb_plugin.h"

void ShararaUsbPluginCApiRegisterWithRegistrar(
    FlutterDesktopPluginRegistrarRef registrar) {
  sharara_usb::ShararaUsbPlugin::RegisterWithRegistrar(
      flutter::PluginRegistrarManager::GetInstance()
          ->GetRegistrar<flutter::PluginRegistrarWindows>(registrar));
}
