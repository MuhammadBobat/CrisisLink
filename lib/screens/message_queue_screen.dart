import 'package:go_router/go_router.dart';
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../services/bluetooth_service.dart';
import '../services/storage_service.dart';
import '../models/message.dart';
import '../models/bluetooth_device.dart';
import '../widgets/bluetooth_device_list.dart';
import '../widgets/sync_progress_indicator.dart';

class MessageQueueScreen extends StatefulWidget {
  const MessageQueueScreen({super.key});

  @override
  State<MessageQueueScreen> createState() => _MessageQueueScreenState();
}

class _MessageQueueScreenState extends State<MessageQueueScreen>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;
  List<Message> _messages = [];
  List<BluetoothDeviceInfo> _bluetoothDevices = [];
  bool _isLoading = true;
  late BluetoothService _bluetoothService;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    _bluetoothService = context.read<BluetoothService>();
    _loadData();
    _initializeBluetooth();
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    
    final storageService = context.read<StorageService>();
    final messages = await storageService.getSortedMessages();
    
    setState(() {
      _messages = messages;
      _isLoading = false;
    });
  }

  Future<void> _initializeBluetooth() async {
    try {
      await _bluetoothService.initialize();
      
      // Start advertising automatically so other devices can find this one
      await _bluetoothService.startAdvertising();
      
      // Update UI periodically
      Timer.periodic(const Duration(seconds: 2), (timer) {
        if (mounted) {
          setState(() {
            _bluetoothDevices = _bluetoothService.discoveredDevices;
          });
        }
      });
    } catch (e) {
      // Don't crash the app if Bluetooth fails to initialize
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Bluetooth initialization failed: $e'),
            backgroundColor: Colors.orange,
          ),
        );
      }
    }
  }

  Future<void> _refresh() async {
    setState(() => _isLoading = true);
    
    if (_tabController.index == 0) {
      await _loadData();
    } else {
      setState(() {
        _bluetoothDevices = _bluetoothService.discoveredDevices;
      });
    }
    
    setState(() => _isLoading = false);
  }

  Future<void> _deleteMessage(String messageId) async {
    final storageService = context.read<StorageService>();
    await storageService.deleteMessage(messageId);
    await _loadData();
  }

  Future<void> _startBluetoothScan() async {
    try {
      await _bluetoothService.startScan();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error starting Bluetooth scan: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  Future<void> _stopBluetoothScan() async {
    try {
      await _bluetoothService.stopScan();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error stopping Bluetooth scan: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  Future<void> _connectToDevice(String deviceId) async {
    try {
      await _bluetoothService.connectToDevice(deviceId);
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Connected successfully!'),
            backgroundColor: Colors.green,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error connecting to device: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  Future<void> _disconnectFromDevice(String deviceId) async {
    try {
      await _bluetoothService.disconnectFromDevice(deviceId);
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Disconnected successfully!'),
            backgroundColor: Colors.green,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error disconnecting from device: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'GazaLink',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        backgroundColor: Theme.of(context).colorScheme.primary,
        foregroundColor: Colors.white,
        elevation: 0,
        actions: [
          IconButton(
            icon: const Icon(Icons.add),
            onPressed: () => context.go('/create'),
          ),
          IconButton(
            icon: const Icon(Icons.dashboard),
            onPressed: () => context.go('/dashboard'),
          ),
        ],
        bottom: TabBar(
          controller: _tabController,
          labelColor: Colors.white,
          unselectedLabelColor: Colors.white70,
          indicatorColor: Colors.white,
          tabs: const [
            Tab(text: 'Messages'),
            Tab(text: 'Bluetooth'),
          ],
        ),
      ),
      body: Column(
        children: [
          const SyncProgressIndicator(), // Add sync progress indicator
          Expanded(
            child: TabBarView(
              controller: _tabController,
              children: [
                // Messages Tab
                RefreshIndicator(
                  onRefresh: _refresh,
                  child: _isLoading
                      ? const Center(child: CircularProgressIndicator())
                      : _messages.isEmpty
                          ? const Center(
                              child: Text('No messages yet'),
                            )
                          : ListView.builder(
                              itemCount: _messages.length,
                              itemBuilder: (context, index) {
                                final message = _messages[index];
                                return Dismissible(
                                  key: Key(message.id),
                                  direction: DismissDirection.endToStart,
                                  background: Container(
                                    color: Colors.red,
                                    alignment: Alignment.centerRight,
                                    padding: const EdgeInsets.only(right: 16),
                                    child: const Icon(
                                      Icons.delete,
                                      color: Colors.white,
                                    ),
                                  ),
                                  onDismissed: (_) => _deleteMessage(message.id),
                                  child: Card(
                                    margin: const EdgeInsets.symmetric(
                                      horizontal: 16,
                                      vertical: 8,
                                    ),
                                    child: ListTile(
                                      title: Text(message.content),
                                      subtitle: Text(
                                        'Priority: ${message.priority.name}, '
                                        'Status: ${message.deliveryStatus.name}\n'
                                        '${message.isBroadcast ? 'Broadcast' : 'To ${message.destinationId}'}'
                                        '${message.sourceId.isEmpty ? '' : ' · from ${message.sourceId}'}'
                                        '${message.hopCount > 0 ? ' · ${message.hopCount} hop(s)' : ''}',
                                      ),
                                      isThreeLine: true,
                                      leading: Icon(
                                        message.priority == Priority.urgent
                                            ? Icons.warning
                                            : Icons.message,
                                        color: message.priority == Priority.urgent
                                            ? Colors.red
                                            : Colors.blue,
                                      ),
                                      trailing: Icon(
                                        _getStatusIcon(message.deliveryStatus),
                                        color: _getStatusColor(message.deliveryStatus),
                                      ),
                                    ),
                                  ),
                                );
                              },
                            ),
                ),
                // Bluetooth Tab
                RefreshIndicator(
                  onRefresh: _refresh,
                  child: Column(
                    children: [
                      Padding(
                        padding: const EdgeInsets.all(16),
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                          children: [
                            ElevatedButton.icon(
                              onPressed: _startBluetoothScan,
                              icon: const Icon(Icons.search),
                              label: const Text('Start Scan'),
                              style: ElevatedButton.styleFrom(
                                backgroundColor: Theme.of(context).colorScheme.primary,
                                foregroundColor: Colors.white,
                              ),
                            ),
                            ElevatedButton.icon(
                              onPressed: _stopBluetoothScan,
                              icon: const Icon(Icons.stop),
                              label: const Text('Stop Scan'),
                              style: ElevatedButton.styleFrom(
                                backgroundColor: Colors.red,
                                foregroundColor: Colors.white,
                              ),
                            ),
                          ],
                        ),
                      ),
                      Expanded(
                        child: BluetoothDeviceList(
                          devices: _bluetoothDevices,
                          scanStatus: _bluetoothService.scanStatus,
                          connectionStatus: _bluetoothService.connectionStatus,
                          onConnect: _connectToDevice,
                          onDisconnect: _disconnectFromDevice,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  IconData _getStatusIcon(DeliveryStatus status) {
    switch (status) {
      case DeliveryStatus.pending:
        return Icons.schedule;
      case DeliveryStatus.sent:
        return Icons.check_circle_outline;
      case DeliveryStatus.delivered:
        return Icons.check_circle;
    }
  }

  Color _getStatusColor(DeliveryStatus status) {
    switch (status) {
      case DeliveryStatus.pending:
        return Colors.orange;
      case DeliveryStatus.sent:
        return Colors.blue;
      case DeliveryStatus.delivered:
        return Colors.green;
    }
  }
}