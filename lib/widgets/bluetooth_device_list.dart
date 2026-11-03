import 'package:flutter/material.dart';
import '../models/bluetooth_device.dart';

class BluetoothDeviceList extends StatelessWidget {
  final List<BluetoothDeviceInfo> devices;
  final BluetoothScanStatus scanStatus;
  final BluetoothConnectionStatus connectionStatus;
  final Function(String) onConnect;
  final Function(String) onDisconnect;

  const BluetoothDeviceList({
    super.key,
    required this.devices,
    required this.scanStatus,
    required this.connectionStatus,
    required this.onConnect,
    required this.onDisconnect,
  });

  @override
  Widget build(BuildContext context) {
    if (scanStatus == BluetoothScanStatus.scanning && devices.isEmpty) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Scanning for devices...'),
          ],
        ),
      );
    }

    if (devices.isEmpty) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.bluetooth_disabled, size: 64, color: Colors.grey),
            SizedBox(height: 16),
            Text(
              'No devices found',
              style: TextStyle(fontSize: 18, color: Colors.grey),
            ),
            SizedBox(height: 8),
            Text(
              'Start scanning to discover nearby devices',
              style: TextStyle(color: Colors.grey),
            ),
          ],
        ),
      );
    }

    // Separate GazaLink devices from other devices
    final gazalinkDevices = devices.where((d) => d.isGazaLinkDevice).toList();
    final otherDevices = devices.where((d) => !d.isGazaLinkDevice).toList();

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // GazaLink Devices Section
        if (gazalinkDevices.isNotEmpty) ...[
          _buildSectionHeader('GazaLink Peers', Icons.bluetooth_connected, Colors.blue),
          const SizedBox(height: 8),
          ...gazalinkDevices.map((device) => _buildDeviceCard(device)),
          const SizedBox(height: 16),
        ],
        
        // Other Devices Section
        if (otherDevices.isNotEmpty) ...[
          _buildSectionHeader('Other Devices', Icons.bluetooth, Colors.grey),
          const SizedBox(height: 8),
          ...otherDevices.map((device) => _buildDeviceCard(device)),
        ],
        
        // No devices found message
        if (devices.isEmpty) ...[
          const Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(Icons.bluetooth_disabled, size: 64, color: Colors.grey),
                SizedBox(height: 16),
                Text(
                  'No devices found',
                  style: TextStyle(fontSize: 18, color: Colors.grey),
                ),
                SizedBox(height: 8),
                Text(
                  'Start scanning to discover nearby devices',
                  style: TextStyle(color: Colors.grey),
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildSectionHeader(String title, IconData icon, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        color: color.withOpacity(0.1),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withOpacity(0.3)),
      ),
      child: Row(
        children: [
          Icon(icon, color: color, size: 20),
          const SizedBox(width: 8),
          Text(
            title,
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildDeviceCard(BluetoothDeviceInfo device) {
    final signalStrength = _getSignalStrength(device.rssi);
    final signalColor = _getSignalColor(device.rssi);

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      elevation: device.isGazaLinkDevice ? 4 : 2,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: device.isConnected
            ? BorderSide(color: Colors.green, width: 2)
            : device.isGazaLinkDevice
                ? BorderSide(color: Colors.blue, width: 1)
                : BorderSide.none,
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Text(
                            device.name,
                            style: const TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          if (device.isGazaLinkDevice) ...[
                            const SizedBox(width: 8),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                              decoration: BoxDecoration(
                                color: Colors.blue,
                                borderRadius: BorderRadius.circular(4),
                              ),
                              child: const Text(
                                'GazaLink',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 10,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ],
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'ID: ${device.id}',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Colors.grey,
                        ),
                      ),
                    ],
                  ),
                ),
                Column(
                  children: [
                    Icon(
                      signalStrength,
                      color: signalColor,
                      size: 20,
                    ),
                    Text(
                      '${device.rssi} dBm',
                      style: TextStyle(
                        fontSize: 12,
                        color: signalColor,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: device.isConnectable ? Colors.green : Colors.red,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    device.isConnectable ? 'CONNECTABLE' : 'NOT CONNECTABLE',
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 10,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                const Spacer(),
                if (device.isConnected)
                  ElevatedButton(
                    onPressed: () => onDisconnect(device.id),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.red,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 8,
                      ),
                    ),
                    child: const Text('Disconnect'),
                  )
                else
                  ElevatedButton(
                    onPressed: device.isConnectable
                        ? () => onConnect(device.id)
                        : null,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.blue,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 8,
                      ),
                    ),
                    child: const Text('Connect'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  IconData _getSignalStrength(int rssi) {
    if (rssi >= -50) return Icons.bluetooth_connected;
    if (rssi >= -60) return Icons.bluetooth;
    if (rssi >= -70) return Icons.bluetooth_searching;
    if (rssi >= -80) return Icons.bluetooth_disabled;
    return Icons.bluetooth_disabled;
  }

  Color _getSignalColor(int rssi) {
    if (rssi >= -50) return Colors.green;
    if (rssi >= -60) return Colors.blue;
    if (rssi >= -70) return Colors.orange;
    if (rssi >= -80) return Colors.red;
    return Colors.grey;
  }
}
