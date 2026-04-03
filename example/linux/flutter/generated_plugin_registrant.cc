//
//  Generated file. Do not edit.
//

// clang-format off

#include "generated_plugin_registrant.h"

#include <sharara_usb/sharara_usb_plugin.h>

void fl_register_plugins(FlPluginRegistry* registry) {
  g_autoptr(FlPluginRegistrar) sharara_usb_registrar =
      fl_plugin_registry_get_registrar_for_plugin(registry, "ShararaUsbPlugin");
  sharara_usb_plugin_register_with_registrar(sharara_usb_registrar);
}
