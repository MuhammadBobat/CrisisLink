import 'package:flutter_blue_plus/flutter_blue_plus.dart';

class BluetoothDeviceInfo {
  final String id;
  final String name;
  final int rssi;
  final bool isConnectable;
  final bool isConnected;
  final BluetoothDevice? device;
  final bool isGazaLinkDevice;

  BluetoothDeviceInfo({
    required this.id,
    required this.name,
    required this.rssi,
    required this.isConnectable,
    this.isConnected = false,
    this.device,
    this.isGazaLinkDevice = false,
  });

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'rssi': rssi,
      'isConnectable': isConnectable,
      'isConnected': isConnected,
      'isGazaLinkDevice': isGazaLinkDevice,
    };
  }

  factory BluetoothDeviceInfo.fromJson(Map<String, dynamic> json) {
    return BluetoothDeviceInfo(
      id: json['id'],
      name: json['name'],
      rssi: json['rssi'],
      isConnectable: json['isConnectable'],
      isConnected: json['isConnected'] ?? false,
      isGazaLinkDevice: json['isGazaLinkDevice'] ?? false,
    );
  }

  BluetoothDeviceInfo copyWith({
    String? id,
    String? name,
    int? rssi,
    bool? isConnectable,
    bool? isConnected,
    BluetoothDevice? device,
    bool? isGazaLinkDevice,
  }) {
    return BluetoothDeviceInfo(
      id: id ?? this.id,
      name: name ?? this.name,
      rssi: rssi ?? this.rssi,
      isConnectable: isConnectable ?? this.isConnectable,
      isConnected: isConnected ?? this.isConnected,
      device: device ?? this.device,
      isGazaLinkDevice: isGazaLinkDevice ?? this.isGazaLinkDevice,
    );
  }
}

enum BluetoothConnectionStatus {
  disconnected,
  connecting,
  connected,
  disconnecting,
  error,
}

enum BluetoothScanStatus {
  idle,
  scanning,
  stopped,
  error,
}
