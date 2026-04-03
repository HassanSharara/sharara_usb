#include "usb_handler.h"

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>
#include <windows.h>
#include <winspool.h>
#include <iostream>
#include <vector>
#include <string>
#include <memory>

namespace sharara_usb {

// Constructor
    UsbHandler::UsbHandler(flutter::PluginRegistrarWindows* registrarWindows)
            : registrar(registrarWindows), hPrinter(NULL), connectedPrinterName(L"") {}

// Destructor
    UsbHandler::~UsbHandler() {
        if (hPrinter) {
            ClosePrinter(hPrinter);
            hPrinter = NULL;
        }
    }

// Main Dispatcher
    void UsbHandler::call(const flutter::MethodCall<flutter::EncodableValue>& method_call,
                          std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
        const std::string& method = method_call.method_name();

        if (method == "getDevices" || method == "getConnectedUsbList") {
            handleGetPrinters(std::move(result));
        } else if (method == "connectToDevice") {
            handleConnect(method_call, std::move(result));
        } else if (method == "writeDataTo") {
            handleWrite(method_call, std::move(result));
        } else if (method == "isDeviceConnected") {
            handleIsDeviceConnected(method_call, std::move(result));
        } else if (method == "dispose_all") {
            if (hPrinter) ClosePrinter(hPrinter);
            hPrinter = NULL;
            connectedPrinterName = L"";
            result->Success(flutter::EncodableValue(true));
        } else {
            result->Error("NotImplemented", "Method not implemented");
        }
    }

    void UsbHandler::handleIsDeviceConnected(const flutter::MethodCall<flutter::EncodableValue>& method_call,
                                             std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
        if (hPrinter == NULL || hPrinter == INVALID_HANDLE_VALUE) {
            result->Success(false);
            return;
        }

        const auto* args = std::get_if<flutter::EncodableMap>(method_call.arguments());
        if (!args) {
            result->Success(flutter::EncodableValue(hPrinter != NULL));
            return;
        }

        auto it = args->find(flutter::EncodableValue("id"));
        if (it != args->end()) {
            std::string requestedName = std::get<std::string>(it->second);
            std::wstring wRequestedName(requestedName.begin(), requestedName.end());
            bool isMatch = (connectedPrinterName == wRequestedName);
            result->Success(flutter::EncodableValue(isMatch));
        } else {
            result->Success(flutter::EncodableValue(hPrinter != NULL));
        }
    }

    void UsbHandler::handleGetPrinters(std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
        flutter::EncodableList devices;
        DWORD dwNeeded, dwReturned;
        DWORD flags = PRINTER_ENUM_LOCAL | PRINTER_ENUM_CONNECTIONS;

        EnumPrinters(flags, NULL, 2, NULL, 0, &dwNeeded, &dwReturned);

        if (dwNeeded > 0) {
            std::vector<BYTE> buffer(dwNeeded);
            if (EnumPrinters(flags, NULL, 2, buffer.data(), dwNeeded, &dwNeeded, &dwReturned)) {
                PRINTER_INFO_2* pInfo = (PRINTER_INFO_2*)buffer.data();
                for (DWORD i = 0; i < dwReturned; i++) {
                    std::string name = WideToUtf8(pInfo[i].pPrinterName);
                    flutter::EncodableMap deviceMap;
                    deviceMap[flutter::EncodableValue("id")] = flutter::EncodableValue(name);
                    deviceMap[flutter::EncodableValue("product_name")] = flutter::EncodableValue(name);

                    flutter::EncodableMap iface;
                    iface[flutter::EncodableValue("interface_class")] = flutter::EncodableValue(7);
                    deviceMap[flutter::EncodableValue("interfaces")] = flutter::EncodableList{flutter::EncodableValue(iface)};

                    devices.push_back(flutter::EncodableValue(deviceMap));
                }
            }
        }
        result->Success(flutter::EncodableValue(devices));
    }

    void UsbHandler::handleConnect(const flutter::MethodCall<flutter::EncodableValue>& method_call,
                                   std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
        const auto* args = std::get_if<flutter::EncodableMap>(method_call.arguments());
        auto it = args->find(flutter::EncodableValue("id"));
        if (it == args->end()) { result->Error("ArgError", "No ID"); return; }

        std::string name = std::get<std::string>(it->second);
        std::wstring wName(name.begin(), name.end());

        if (_connect(wName)) {
            result->Success(flutter::EncodableValue(true));
        } else {
            result->Error("ConnectError", "OpenPrinter failed");
        }
    }

    bool UsbHandler::_connect(std::wstring &ws) {
        if (hPrinter) ClosePrinter(hPrinter);
        if (OpenPrinter((LPWSTR)ws.c_str(), &hPrinter, NULL)) {
            this->connectedPrinterName = ws;
            return true;
        }
        return false;
    }

    void UsbHandler::handleWrite(const flutter::MethodCall<flutter::EncodableValue>& method_call,
                                 std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {

        const auto* args = std::get_if<flutter::EncodableMap>(method_call.arguments());
        auto it = args->find(flutter::EncodableValue("id"));
        if (it == args->end()) { result->Error("ArgError", "No ID"); return; }

        std::string name = std::get<std::string>(it->second);
        std::wstring wName(name.begin(), name.end());

        if (!isConnected(wName)) {
            if (!_connect(wName)) {
                result->Error("60", "Handle is invalid. Re-connect the printer.");
                return;
            }
        }

        auto data_iter = args->find(flutter::EncodableValue("data"));
        if (data_iter == args->end()) {
            result->Error("ArgError", "No data provided");
            return;
        }

        const auto& data_list = std::get<flutter::EncodableList>(data_iter->second);
        std::vector<uint8_t> bytes;
        for (const auto& val : data_list) {
            bytes.push_back(static_cast<uint8_t>(std::get<int32_t>(val)));
        }

        DOC_INFO_1 docInfo;
        docInfo.pDocName = (LPWSTR)L"Sharara POS RAW";
        docInfo.pOutputFile = NULL;
        docInfo.pDatatype = (LPWSTR)L"RAW";

        DWORD jobId = StartDocPrinter(hPrinter, 1, (LPBYTE)&docInfo);
        if (jobId == 0) {
            result->Error("StartDocFailed", "Error: " + std::to_string(GetLastError()));
            return;
        }

        StartPagePrinter(hPrinter);
        DWORD dwWritten = 0;
        BOOL bSuccess = WritePrinter(hPrinter, bytes.data(), (DWORD)bytes.size(), &dwWritten);
        DWORD lastError = bSuccess ? 0 : GetLastError();

        EndPagePrinter(hPrinter);
        EndDocPrinter(hPrinter);

        if (bSuccess) {
            result->Success(flutter::EncodableValue(true));
        } else {
            result->Error("WritePrinterFailed", "Windows Error: " + std::to_string(lastError));
        }
    }

} // namespace sharara_usb