#ifndef FLUTTER_PLUGIN_SHARARA_USB_HANDLER_H_
#define FLUTTER_PLUGIN_SHARARA_USB_HANDLER_H_

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>

#include <windows.h>
#include <winspool.h>
#include <iostream>
#include <vector>
#include <string>
#include <memory>

#pragma comment(lib, "winspool.lib")

namespace sharara_usb {

    // Helper to convert Windows Wide strings to UTF-8
    inline std::string WideToUtf8(const std::wstring& wstr) {
        if (wstr.empty()) return "";
        int size_needed = WideCharToMultiByte(CP_UTF8, 0, &wstr[0], (int)wstr.size(), NULL, 0, NULL, NULL);
        std::string strTo(size_needed, 0);
        WideCharToMultiByte(CP_UTF8, 0, &wstr[0], (int)wstr.size(), &strTo[0], size_needed, NULL, NULL);
        return strTo;
    }

    class UsbHandler {
    public:
        explicit UsbHandler(flutter::PluginRegistrarWindows* registrarWindows);
        ~UsbHandler();

        void call(const flutter::MethodCall<flutter::EncodableValue>& method_call,
                  std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

        HANDLE hPrinter = NULL;
        std::wstring connectedPrinterName = L"";
        flutter::PluginRegistrarWindows* registrar;

    private:
        void handleIsDeviceConnected(const flutter::MethodCall<flutter::EncodableValue>& method_call,
                                     std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
        void handleGetPrinters(std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
        void handleConnect(const flutter::MethodCall<flutter::EncodableValue>& method_call,
                           std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
        void handleWrite(const flutter::MethodCall<flutter::EncodableValue>& method_call,
                         std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

        bool inline isConnected(const std::wstring& id) {
            return hPrinter != NULL && id == connectedPrinterName;
        }

        bool _connect(std::wstring& ws);
    };

}  // namespace sharara_usb

#endif  // FLUTTER_PLUGIN_SHARARA_USB_HANDLER_H_