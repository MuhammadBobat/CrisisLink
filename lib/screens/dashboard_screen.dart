import '../models/bluetooth_device.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'dart:async';
import '../routing/node_identity.dart';
import '../services/storage_service.dart';
import '../services/bluetooth_service.dart';
import '../models/message.dart';

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  Map<String, int> _stats = {};
  List<Message> _messages = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);

    try {
      final storageService = context.read<StorageService>();
      final stats = await storageService.getAppStats();
      final messages = await storageService.getSortedMessages();

      setState(() {
        _stats = stats;
        _messages = messages;
        _isLoading = false;
      });
    } catch (e) {
      setState(() => _isLoading = false);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error loading data: $e')),
        );
      }
    }
  }

  Future<void> _refresh() async {
    setState(() => _isLoading = true);
    await _loadData();
    setState(() => _isLoading = false);
  }

  Future<void> _addSampleData() async {
    final storageService = context.read<StorageService>();
    
    final sampleMessages = [
      Message(
        content: 'Emergency: Need medical supplies at location A',
        priority: Priority.urgent,
        timestamp: DateTime.now().subtract(const Duration(minutes: 30)),
      ),
      Message(
        content: 'Status update: All systems operational',
        priority: Priority.normal,
        timestamp: DateTime.now().subtract(const Duration(hours: 2)),
      ),
      Message(
        content: 'Urgent: Power outage in sector B',
        priority: Priority.urgent,
        timestamp: DateTime.now().subtract(const Duration(minutes: 15)),
      ),
      Message(
        content: 'Regular communication check',
        priority: Priority.normal,
        timestamp: DateTime.now().subtract(const Duration(hours: 1)),
      ),
    ];

    for (final message in sampleMessages) {
      await storageService.saveMessage(message);
    }

    await _loadData();

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Sample data added successfully!'),
          backgroundColor: Colors.green,
        ),
      );
    }
  }

  Future<void> _clearAllData() async {
    final storageService = context.read<StorageService>();
    
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Clear All Data'),
        content: const Text(
          'Are you sure you want to delete all messages? This action cannot be undone.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('Delete All'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      await storageService.clearAllMessages();
      await _loadData();

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('All data cleared successfully!'),
            backgroundColor: Colors.orange,
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
          'Dashboard',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        backgroundColor: Theme.of(context).colorScheme.primary,
        foregroundColor: Colors.white,
        elevation: 0,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _refresh,
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refresh,
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    _buildStatsCards(),
                    const SizedBox(height: 24),
                    _buildBluetoothStatus(),
                    const SizedBox(height: 16),
                    _buildRoutingCard(),
                    const SizedBox(height: 24),
                    _buildRecentMessages(),
                    const SizedBox(height: 24),
                    _buildActionButtons(),
                  ],
                ),
              ),
            ),
      bottomNavigationBar: _buildBottomNavigation(),
    );
  }

  Widget _buildStatsCards() {
    return Column(
      children: [
        Row(
          children: [
            Expanded(child: _buildStatCard(
              'Total Messages',
              _stats['totalMessages']?.toString() ?? '0',
              Icons.message,
              Colors.blue,
            )),
            const SizedBox(width: 12),
            Expanded(child: _buildStatCard(
              'Pending',
              _stats['pendingMessages']?.toString() ?? '0',
              Icons.schedule,
              Colors.orange,
            )),
          ],
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            Expanded(child: _buildStatCard(
              'Urgent',
              _stats['urgentMessages']?.toString() ?? '0',
              Icons.priority_high,
              Colors.red,
            )),
            const SizedBox(width: 12),
            Expanded(child: _buildStatCard(
              'Delivered',
              ((_stats['totalMessages'] ?? 0) - (_stats['pendingMessages'] ?? 0)).toString(),
              Icons.check_circle,
              Colors.green,
            )),
          ],
        ),
      ],
    );
  }

  Widget _buildStatCard(String title, String value, IconData icon, Color color) {
    return Card(
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          children: [
            Icon(icon, size: 32, color: color),
            const SizedBox(height: 8),
            Text(
              value,
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.bold,
                color: color,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              title,
              style: const TextStyle(
                fontSize: 14,
                color: Colors.grey,
              ),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildRoutingCard() {
    final routing = context.read<BluetoothService>().routing;
    final ready = routing.isReady;
    return FutureBuilder<String>(
      future: NodeIdentity.load(),
      builder: (context, snapshot) {
        final nodeId = snapshot.data ?? '...';
        return Card(
          elevation: 4,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Routing',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 16),
                _buildStatusRow('Your node ID', nodeId, Icons.badge, Colors.blue),
                _buildStatusRow(
                  'Policy',
                  !ready
                      ? 'Loads with Bluetooth'
                      : routing.usingLearnedPolicy
                          ? 'Deep Q-Learning'
                          : 'PRoPHET fallback',
                  Icons.alt_route,
                  ready && routing.usingLearnedPolicy ? Colors.green : Colors.orange,
                ),
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    onPressed: snapshot.hasData
                        ? () {
                            Clipboard.setData(ClipboardData(text: nodeId));
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(content: Text('Node ID copied')),
                            );
                          }
                        : null,
                    icon: const Icon(Icons.copy, size: 18),
                    label: const Text('Copy ID'),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildBluetoothStatus() {
    final bluetoothService = context.watch<BluetoothService>();
    
    return Card(
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Bluetooth Status',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 16),
            _buildStatusRow(
              'Bluetooth',
              bluetoothService.isBluetoothEnabled ? 'Enabled' : 'Disabled',
              bluetoothService.isBluetoothEnabled ? Icons.bluetooth_connected : Icons.bluetooth_disabled,
              bluetoothService.isBluetoothEnabled ? Colors.green : Colors.red,
            ),
            const SizedBox(height: 8),
            _buildStatusRow(
              'Scan Status',
              bluetoothService.scanStatus.name,
              Icons.search,
              bluetoothService.scanStatus == BluetoothScanStatus.scanning ? Colors.blue : Colors.grey,
            ),
            const SizedBox(height: 8),
            _buildStatusRow(
              'Connection',
              bluetoothService.connectionStatus.name,
              Icons.link,
              bluetoothService.connectionStatus == BluetoothConnectionStatus.connected ? Colors.green : Colors.grey,
            ),
            const SizedBox(height: 8),
            _buildStatusRow(
              'Connected Devices',
              bluetoothService.connectedDevices.length.toString(),
              Icons.devices,
              Colors.blue,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusRow(String label, String value, IconData icon, Color color) {
    return Row(
      children: [
        Icon(icon, size: 20, color: color),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            label,
            style: const TextStyle(fontWeight: FontWeight.w500),
          ),
        ),
        Text(
          value,
          style: TextStyle(
            color: color,
            fontWeight: FontWeight.bold,
          ),
        ),
      ],
    );
  }

  Widget _buildRecentMessages() {
    final recentMessages = _messages.take(3).toList();
    
    return Card(
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Recent Messages',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 16),
            if (recentMessages.isEmpty)
              const Center(
                child: Padding(
                  padding: EdgeInsets.all(20),
                  child: Text(
                    'No messages yet',
                    style: TextStyle(color: Colors.grey),
                  ),
                ),
              )
            else
              ...recentMessages.map((message) => _buildMessageItem(message)),
          ],
        ),
      ),
    );
  }

  Widget _buildMessageItem(Message message) {
    final isUrgent = message.priority == Priority.urgent;
    
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.grey[100],
        borderRadius: BorderRadius.circular(8),
        border: isUrgent
            ? Border.all(color: Colors.red, width: 1)
            : null,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  message.content,
                  style: const TextStyle(fontWeight: FontWeight.w500),
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 6,
                  vertical: 2,
                ),
                decoration: BoxDecoration(
                  color: isUrgent ? Colors.red : Colors.blue,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  message.priority.name.toUpperCase(),
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 10,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            _formatTimestamp(message.timestamp),
            style: const TextStyle(
              fontSize: 12,
              color: Colors.grey,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildActionButtons() {
    return Column(
      children: [
        ElevatedButton.icon(
          onPressed: _addSampleData,
          icon: const Icon(Icons.add),
          label: const Text('Add Sample Data'),
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.blue,
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(vertical: 16),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
            ),
          ),
        ),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          onPressed: _clearAllData,
          icon: const Icon(Icons.delete_forever),
          label: const Text('Clear All Data'),
          style: OutlinedButton.styleFrom(
            foregroundColor: Colors.red,
            padding: const EdgeInsets.symmetric(vertical: 16),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildBottomNavigation() {
    return BottomNavigationBar(
      currentIndex: 2,
      onTap: (index) {
        switch (index) {
          case 0:
            context.go('/');
            break;
          case 1:
            context.go('/create');
            break;
        }
      },
      items: const [
        BottomNavigationBarItem(
          icon: Icon(Icons.queue),
          label: 'Messages',
        ),
        BottomNavigationBarItem(
          icon: Icon(Icons.add),
          label: 'Create',
        ),
        BottomNavigationBarItem(
          icon: Icon(Icons.dashboard),
          label: 'Dashboard',
        ),
      ],
    );
  }

  String _formatTimestamp(DateTime timestamp) {
    final now = DateTime.now();
    final difference = now.difference(timestamp);
    
    if (difference.inMinutes < 1) {
      return 'Just now';
    } else if (difference.inMinutes < 60) {
      return '${difference.inMinutes}m ago';
    } else if (difference.inHours < 24) {
      return '${difference.inHours}h ago';
    } else {
      return '${difference.inDays}d ago';
    }
  }
}
