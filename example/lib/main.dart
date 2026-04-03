import 'dart:io';

import 'package:esc_pos_utils_plus/esc_pos_utils_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:sharara_usb/models/usb/usb.dart';
import 'package:sharara_usb/sharara_usb.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.cyan,
        brightness: Brightness.dark,
      ),
      home: const UsbManagerScreen(),
    );
  }
}

class UsbManagerScreen extends StatefulWidget {
  const UsbManagerScreen({super.key});

  @override
  State<UsbManagerScreen> createState() => _UsbManagerScreenState();
}

class _UsbManagerScreenState extends State<UsbManagerScreen> {
  List<UsbDevice> _devices = [];
  bool _isLoading = false;

  ValueNotifier<List<String>> errors = ValueNotifier([]);

  @override
  void initState() {
    super.initState();
    CapabilityProfile.load().timeout(const Duration(seconds: 3))
        .catchError((e){
      errors.value = List.from([
        ...errors.value,
        e.toString()
      ]);
    }).then((e){
      errors.value = List.from([
        ...errors.value,
        "profile loaded now you free to check"
      ]);
      _scanDevices();
    });
  }



  Future<void> _scanDevices() async {
    setState(() => _isLoading = true);
    try {
      final list = await ShararaUsb.platform.getConnectedUsbList();
      setState(() => _devices = list ?? []);
    } catch (e) {
      errors.value = List.from([
        ...errors.value,
        e.toString()
      ]);
      final context = this.context;
      if(!context.mounted)return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text("Error fetching devices: $e")),
      );
    } finally {
      setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(

        appBar: AppBar(
          title: const Text('Sharara USB Explorer'),
          centerTitle: true,
          bottom: PreferredSize(
            preferredSize: const Size.fromHeight(20),
            child: Padding(
              padding: const EdgeInsets.only(bottom: 8.0),
              child: Text("OS: ${Platform.version}", style: const TextStyle(fontSize: 12, color: Colors.cyan)),
            ),
          ),
        ),
        floatingActionButton: FloatingActionButton.extended(
          onPressed: _scanDevices,
          label: const Text("Rescan Bus"),
          icon: _isLoading
              ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.black))
              : const Icon(Icons.refresh),
        ),
        body:Column(
          children: [
            ValueListenableBuilder(
              valueListenable: errors,
              builder:(context,e,_){
                return Column(
                  children: [
                    for(final error in e)
                      ...[
                        Card(
                          child: Text(error,style: TextStyle(color: Colors.red),),
                        ),
                        const SizedBox(height:10,)

                      ],
                    if(e.isNotEmpty)
                      ...[
                        const SizedBox(height:10,),
                        ElevatedButton(
                          onPressed: (){
                            errors.value = [];
                          },
                          child: const Text("Clear"),
                        ),
                        const SizedBox(height:10,),
                      ],
                  ],
                );
              } ,
            ),
            Expanded(
              child: _devices.isEmpty
                  ? _buildEmptyState()
                  : ListView.builder(
                padding: const EdgeInsets.all(12),
                itemCount: _devices.length,
                itemBuilder: (context, index) => DeviceCard(device: _devices[index],errorsNotifier: errors,),
              ),
            ),

          ],
        )
    );

  }

  Widget _buildEmptyState() {
    return Center(
      child: Opacity(
        opacity: 0.5,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.usb_off, size: 80),
            const SizedBox(height: 16),
            const Text("No USB Devices Found", style: TextStyle(fontSize: 18)),
            if (_isLoading) const Text("Scanning hardware..."),
          ],
        ),
      ),
    );
  }
}

class DeviceCard extends StatefulWidget {
  final UsbDevice device;
  const DeviceCard({super.key, required this.device,required this.errorsNotifier});
  final ValueNotifier<List<String>> errorsNotifier;
  @override
  State<DeviceCard> createState() => _DeviceCardState();
}

class _DeviceCardState extends State<DeviceCard> {

  showToast(final String message){
    widget.errorsNotifier.value = List.from([
      ...widget.errorsNotifier.value,
      message
    ]);
  }
  @override
  Widget build(BuildContext context) {
    bool hasPrinter = widget.device.interfaces?.any((i) => i.isPrinter) == true;
    // bool hasHID = widget.device.interfaces?.any((i) => i.isHID) == true;

    return Card(
      elevation: 4,
      margin: const EdgeInsets.only(bottom: 12),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: ExpansionTile(
        shape: const RoundedRectangleBorder(side: BorderSide.none),
        leading: Icon(
          hasPrinter ? Icons.print_rounded : Icons.usb_rounded,
          color: hasPrinter ? Colors.greenAccent : Colors.cyanAccent,
          size: 32,
        ),
        title: Text(
          widget.device.productName ?? "Generic Device",
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        subtitle: Text("VID: ${widget.device.hexVendorId}  PID: ${widget.device.hexProductId}"),
        children: [
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Colors.white.withValues(alpha: 0.05),
              borderRadius: const BorderRadius.vertical(bottom: Radius.circular(12)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [


                _buildTechSpec("Manufacturer", widget.device.manufactureName ?? "Unknown"),
                _buildTechSpec("Device ID", widget.device.id.toString()),

                const SizedBox(height: 12),
                const Text("LOGICAL INTERFACES", style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: Colors.grey)),
                const Divider(height: 20),
                ... widget.device.interfaces.map((iface) => _buildInterfaceTile(iface)),
                const Divider(height:10,),

                FutureBuilder(future: widget.device.isConnected,

                    builder: (BuildContext context,snap){

                      return Column(
                        children: [
                          if( !snap.hasData  || snap.data == false)
                            GestureDetector(
                              onTap:()async {
                                try {
                                  await widget.device.connect()
                                      .then((e) {
                                    setState(() {

                                    });
                                  });
                                }
                                catch(e){widget.errorsNotifier.value =
                                    List.from([
                                      ...widget.errorsNotifier.value,
                                      e.toString()
                                    ])
                                ;}
                              },
                              child:Icon(Icons.usb),
                            ),

                          ElevatedButton(
                            onPressed:_write,
                            child: Text("write now"),
                          )
                        ],
                      );
                    }

                )


              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _write()async{

    try {
      final profile = await CapabilityProfile.load().timeout(const Duration(seconds: 3));
      showToast("after loading Profile");
      final generator = Generator(PaperSize.mm80, profile);
      final List<int> data = [];
      data.addAll(generator.cut());
      data.addAll(generator.text("Hello World"));
      data.addAll(generator.feed(2));
      data.addAll(generator.cut());

      showToast("start writing to device ${widget.device.id}");
      await widget.device.writeData(data);
    }
    catch(e) {
      showToast(e.toString());
    }
  }

  Widget _buildTechSpec(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: RichText(
        text: TextSpan(
          text: "$label: ",
          style: const TextStyle(color: Colors.grey, fontSize: 13),
          children: [TextSpan(text: value, style: const TextStyle(color: Colors.white70))],
        ),
      ),
    );
  }

  Widget _buildInterfaceTile(UsbInterface iface) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(color: Colors.cyan.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(4)),
                child: Text("IF ${iface.id}", style: const TextStyle(fontSize: 10, fontWeight: FontWeight.bold)),
              ),
              const SizedBox(width: 8),
              Text(
                iface.isPrinter ? "Printer Class (0x07)" : "Class: ${iface.interfaceClass} Sub: ${iface.interfaceSubclass}",
                style: TextStyle(fontSize: 13, color: iface.isPrinter ? Colors.greenAccent : Colors.white),
              ),
            ],
          ),
          ...iface.endpoints.map((ep) => Padding(
            padding: const EdgeInsets.only(left: 12, top: 4),
            child: Row(
              children: [
                Icon(ep.direction == 128 ? Icons.arrow_downward : Icons.arrow_upward, size: 12, color: Colors.orangeAccent),
                const SizedBox(width: 4),
                Text(
                  "Endpoint ${ep.endpointNumber} | Type: ${ep.type} | MaxPkt: ${ep.maxPacketSize}",
                  style: const TextStyle(fontSize: 11, color: Colors.grey),
                ),
              ],
            ),
          )),
        ],
      ),
    );
  }
}