import 'dart:async';
import 'dart:developer' as developer;
import 'package:battery_plus/battery_plus.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import '../models/bluetooth_device.dart';
import '../models/message.dart';
import '../models/sync_status.dart';
import '../routing/peer_summary.dart';
import '../routing/routing_service.dart';
import 'storage_service.dart';
import 'sync_protocol.dart';

/// BLE discovery, connection and routed message exchange.
///
/// Sync with a connected peer has two framed exchanges on the message characteristic:
///   1. hello:  both sides swap a PeerSummary (battery, buffer, encounter row, held/delivered ids)
///   2. bundle: each side sends only the messages its routing policy picked for that peer
/// Nothing is flooded: what is sent is decided hop by hop by [RoutingService].
class BluetoothService {
  static final BluetoothService _instance = BluetoothService._internal();
  factory BluetoothService() => _instance;
  BluetoothService._internal();

  final List<BluetoothDeviceInfo> _discoveredDevices = [];
  final List<BluetoothDeviceInfo> _connectedDevices = [];
  final Map<String, DateTime> _connectedAt = {};

  BluetoothScanStatus _scanStatus = BluetoothScanStatus.idle;
  BluetoothConnectionStatus _connectionStatus = BluetoothConnectionStatus.disconnected;

  bool _isBluetoothEnabled = false;
  StreamSubscription<List<ScanResult>>? _scanSubscription;
  bool _isInitialized = false;
  bool _isAdvertising = false;

  late final StorageService _storage = StorageService();
  late final RoutingService routing = RoutingService(
    storage: _storage,
    batteryLevel: () async {
      try {
        return (await Battery().batteryLevel) / 100.0;
      } catch (_) {
        return 1.0;
      }
    },
  );

  // Custom UUIDs for GazaLink
  static const String SERVICE_UUID = '9fa480e0-4967-4542-9390-d343dc5d04ae';
  static const String MESSAGE_CHAR_UUID = 'af0badb1-5b99-43cd-917a-a77bc549e3cc';
  static const int MTU_REQUEST = 247;
  static const int DEFAULT_CHUNK = 20; // ATT payload at the default 23-byte MTU

  // Sync status
  final _syncStatusController = StreamController<SyncProgress>.broadcast();
  Stream<SyncProgress> get syncStatus => _syncStatusController.stream;

  // Retry configuration
  static const int MAX_RETRY_ATTEMPTS = 3;
  static const Duration RETRY_DELAY = Duration(seconds: 2);
  static const Duration FRAME_TIMEOUT = Duration(seconds: 20);

  List<BluetoothDeviceInfo> get discoveredDevices => List.unmodifiable(_discoveredDevices);
  List<BluetoothDeviceInfo> get connectedDevices => List.unmodifiable(_connectedDevices);
  BluetoothScanStatus get scanStatus => _scanStatus;
  BluetoothConnectionStatus get connectionStatus => _connectionStatus;
  bool get isBluetoothEnabled => _isBluetoothEnabled;
  bool get isInitialized => _isInitialized;
  bool get isAdvertising => _isAdvertising;

  Future<void> initialize() async {
    if (_isInitialized) return;

    try {
      if (await FlutterBluePlus.isSupported == false) {
        throw Exception('Bluetooth not supported');
      }

      FlutterBluePlus.adapterState.listen((state) {
        _isBluetoothEnabled = state == BluetoothAdapterState.on;
        developer.log('Bluetooth state changed: $state', name: 'BluetoothService');
      });

      final initialState = await FlutterBluePlus.adapterState.first;
      _isBluetoothEnabled = initialState == BluetoothAdapterState.on;

      await routing.initialize();

      _isInitialized = true;
      developer.log(
          'Bluetooth Service initialized. Enabled: $_isBluetoothEnabled, '
          'learned policy: ${routing.usingLearnedPolicy}',
          name: 'BluetoothService');
    } catch (e) {
      developer.log('Error initializing Bluetooth: $e', name: 'BluetoothService');
      _isInitialized = false;
      rethrow;
    }
  }

  Future<bool> requestPermissions() async {
    try {
      if (!_isInitialized) {
        await initialize();
      }
      await FlutterBluePlus.turnOn();
      return true;
    } catch (e) {
      developer.log('Error requesting permissions: $e', name: 'BluetoothService');
      return false;
    }
  }

  // Advertising needs a native GATT server (flutter_blue_plus is central-only), which is
  // not implemented yet. The flag only reflects that this node is willing to be found.
  Future<void> startAdvertising() async {
    try {
      if (!_isInitialized) await initialize();
      if (!await requestPermissions() || !_isBluetoothEnabled) return;
      _isAdvertising = true;
    } catch (e) {
      developer.log('Error starting advertising: $e', name: 'BluetoothService');
      _isAdvertising = false;
    }
  }

  Future<void> stopAdvertising() async {
    _isAdvertising = false;
  }

  Future<void> startScan() async {
    try {
      if (!_isInitialized) await initialize();

      if (!await requestPermissions() || !_isBluetoothEnabled) {
        developer.log('Bluetooth unavailable for scanning', name: 'BluetoothService');
        _scanStatus = BluetoothScanStatus.error;
        return;
      }

      _scanStatus = BluetoothScanStatus.scanning;
      _discoveredDevices.clear();

      _scanSubscription = FlutterBluePlus.scanResults.listen((results) {
        for (ScanResult result in results) {
          final isGazaLinkDevice = result.advertisementData.serviceUuids.contains(Guid(SERVICE_UUID)) ||
              result.device.platformName.contains('GazaLink');

          final deviceName = result.device.platformName.isEmpty
              ? (isGazaLinkDevice ? 'GazaLink Device' : 'Unknown Device')
              : result.device.platformName;

          final deviceInfo = BluetoothDeviceInfo(
            id: result.device.remoteId.toString(),
            name: deviceName,
            rssi: result.rssi,
            isConnectable: result.advertisementData.connectable,
            device: result.device,
            isGazaLinkDevice: isGazaLinkDevice,
          );

          final index = _discoveredDevices.indexWhere((d) => d.id == deviceInfo.id);
          if (index != -1) {
            _discoveredDevices[index] = deviceInfo;
          } else {
            _discoveredDevices.add(deviceInfo);
          }

          // GazaLink devices first, then by signal strength
          _discoveredDevices.sort((a, b) {
            if (a.isGazaLinkDevice != b.isGazaLinkDevice) {
              return a.isGazaLinkDevice ? -1 : 1;
            }
            return b.rssi.compareTo(a.rssi);
          });
        }
      });

      await FlutterBluePlus.startScan(
        timeout: const Duration(seconds: 10),
        androidScanMode: AndroidScanMode.lowLatency,
      );
    } catch (e) {
      developer.log('Error starting scan: $e', name: 'BluetoothService');
      _scanStatus = BluetoothScanStatus.error;
    }
  }

  Future<void> stopScan() async {
    try {
      _scanStatus = BluetoothScanStatus.stopped;
      await FlutterBluePlus.stopScan();
      await _scanSubscription?.cancel();
      _scanSubscription = null;
    } catch (e) {
      developer.log('Error stopping scan: $e', name: 'BluetoothService');
    }
  }

  Future<void> connectToDevice(String deviceId) async {
    try {
      _connectionStatus = BluetoothConnectionStatus.connecting;

      final deviceInfo = _discoveredDevices.firstWhere(
        (d) => d.id == deviceId,
        orElse: () => throw Exception('Device not found'),
      );
      if (deviceInfo.device == null) {
        throw Exception('Device object not available');
      }

      await deviceInfo.device!.connect(
        timeout: const Duration(seconds: 10),
        license: License.free,
      );

      final updatedDevice = deviceInfo.copyWith(isConnected: true);
      final index = _discoveredDevices.indexWhere((d) => d.id == deviceId);
      if (index != -1) {
        _discoveredDevices[index] = updatedDevice;
      }

      _connectedDevices.add(updatedDevice);
      _connectedAt[deviceId] = DateTime.now();
      _connectionStatus = BluetoothConnectionStatus.connected;

      developer.log('Connected to ${deviceInfo.name}', name: 'BluetoothService');
      await syncMessageQueues();
    } catch (e) {
      developer.log('Error connecting to device: $e', name: 'BluetoothService');
      _connectionStatus = BluetoothConnectionStatus.error;
      rethrow;
    }
  }

  Future<void> disconnectFromDevice(String deviceId) async {
    try {
      _connectionStatus = BluetoothConnectionStatus.disconnecting;

      final deviceInfo = _connectedDevices.firstWhere(
        (d) => d.id == deviceId,
        orElse: () => throw Exception('Device not connected'),
      );

      if (deviceInfo.device != null) {
        await deviceInfo.device!.disconnect();
      }

      final updatedDevice = deviceInfo.copyWith(isConnected: false);
      final index = _discoveredDevices.indexWhere((d) => d.id == deviceId);
      if (index != -1) {
        _discoveredDevices[index] = updatedDevice;
      }

      _connectedDevices.removeWhere((d) => d.id == deviceId);
      _connectedAt.remove(deviceId);
      _connectionStatus = BluetoothConnectionStatus.disconnected;
    } catch (e) {
      developer.log('Error disconnecting from device: $e', name: 'BluetoothService');
      _connectionStatus = BluetoothConnectionStatus.error;
      rethrow;
    }
  }

  /// Exchange routed message bundles with every connected peer.
  Future<void> syncMessageQueues() async {
    if (_connectedDevices.isEmpty) {
      developer.log('No connected devices to sync with', name: 'BluetoothService');
      return;
    }
    if (!routing.isReady) await routing.initialize();

    for (final peer in List.of(_connectedDevices)) {
      try {
        await _syncWithPeer(peer);
      } catch (e) {
        developer.log('Error syncing with ${peer.name}: $e', name: 'BluetoothService');
        _updateSyncStatus(SyncStatus.error, 0.0, 'Error syncing with ${peer.name}', error: e.toString());
      }
    }
  }

  Future<void> _syncWithPeer(BluetoothDeviceInfo peer) async {
    final device = peer.device!;
    _updateSyncStatus(SyncStatus.inProgress, 0.05, 'Connecting to ${peer.name}...');

    if (device.mtuNow < MTU_REQUEST) {
      try {
        await device.requestMtu(MTU_REQUEST);
      } catch (_) {
        // A refused MTU request just means smaller chunks.
      }
    }
    final chunkSize = (device.mtuNow - 3).clamp(DEFAULT_CHUNK, 512);

    final services = await device.discoverServices();
    final service = services.firstWhere(
      (s) => s.serviceUuid.toString() == SERVICE_UUID,
      orElse: () => throw Exception('GazaLink service not found'),
    );
    final characteristic = service.characteristics.firstWhere(
      (c) => c.characteristicUuid.toString() == MESSAGE_CHAR_UUID,
      orElse: () => throw Exception('GazaLink message characteristic not found'),
    );

    // 1. hello
    _updateSyncStatus(SyncStatus.inProgress, 0.2, 'Exchanging routing state with ${peer.name}...');
    final mine = await routing.buildSummary();
    await _sendFrame(characteristic, {'t': 'hello', 'summary': mine.toJson()}, chunkSize);
    final helloIn = await _receiveFrame(characteristic) as Map<String, dynamic>;
    if (helloIn['t'] != 'hello') throw Exception('Unexpected frame from peer: ${helloIn['t']}');
    final theirs = PeerSummary.fromJson(helloIn['summary'] as Map<String, dynamic>);
    await routing.onHandshake(theirs);

    // 2. bundle: only what the routing policy picks for this peer
    final link = LinkContext(
      rssi: peer.rssi,
      connectedFor: DateTime.now().difference(_connectedAt[peer.id] ?? DateTime.now()),
      neighbours: _discoveredDevices.where((d) => d.isGazaLinkDevice).length,
    );
    final selected = await routing.selectForPeer(theirs, link);
    _updateSyncStatus(SyncStatus.inProgress, 0.5,
        'Sending ${selected.length} routed message(s) to ${peer.name}...');
    await _sendFrame(characteristic, {
      't': 'bundle',
      'messages': selected.map((m) => routing.prepareForTransfer(m).toJson()).toList(),
    }, chunkSize);

    _updateSyncStatus(SyncStatus.inProgress, 0.75, 'Receiving messages from ${peer.name}...');
    final bundleIn = await _receiveFrame(characteristic) as Map<String, dynamic>;
    if (bundleIn['t'] != 'bundle') throw Exception('Unexpected frame from peer: ${bundleIn['t']}');

    for (final m in selected) {
      await _storage.markForwarded(m, toDestination: m.destinationId == theirs.nodeId);
    }
    final incoming = (bundleIn['messages'] as List)
        .map((j) => Message.fromJson(j as Map<String, dynamic>))
        .toList();
    final result = await _storage.ingestMessages(incoming, routing.nodeId);

    _updateSyncStatus(
      SyncStatus.completed,
      1.0,
      'Synced with ${peer.name}: sent ${selected.length}, received ${result.relayed.length + result.delivered.length}'
      '${result.delivered.isNotEmpty ? ' (${result.delivered.length} for you)' : ''}',
    );
  }

  Future<void> _sendFrame(BluetoothCharacteristic c, Object json, int chunkSize) async {
    for (final chunk in FrameCodec.encode(json, chunkSize: chunkSize)) {
      await _withRetry(() => c.write(chunk, withoutResponse: false), 'write');
    }
  }

  Future<Object> _receiveFrame(BluetoothCharacteristic c) async {
    final assembler = FrameAssembler();
    final deadline = DateTime.now().add(FRAME_TIMEOUT);
    while (DateTime.now().isBefore(deadline)) {
      final chunk = await _withRetry(() => c.read(), 'read');
      if (chunk.isEmpty) {
        await Future.delayed(const Duration(milliseconds: 200));
        continue;
      }
      final frame = assembler.add(chunk);
      if (frame != null) return frame;
    }
    throw TimeoutException('Peer did not complete a frame', FRAME_TIMEOUT);
  }

  Future<T> _withRetry<T>(Future<T> Function() op, String what) async {
    for (var attempt = 1;; attempt++) {
      try {
        return await op();
      } catch (e) {
        if (attempt >= MAX_RETRY_ATTEMPTS) {
          throw Exception('Failed to $what after $MAX_RETRY_ATTEMPTS attempts: $e');
        }
        developer.log('Retry $attempt for $what', name: 'BluetoothService');
        await Future.delayed(RETRY_DELAY);
      }
    }
  }

  void _updateSyncStatus(SyncStatus status, double progress, String message, {String? error}) {
    _syncStatusController.add(SyncProgress(
      status: status,
      progress: progress,
      message: message,
      error: error,
    ));
  }

  Map<String, dynamic> getNetworkTopologyData() {
    return {
      'timestamp': DateTime.now().toIso8601String(),
      'discoveredDevices': _discoveredDevices.map((d) => d.toJson()).toList(),
      'connectedDevices': _connectedDevices.length,
      'scanStatus': _scanStatus.name,
      'connectionStatus': _connectionStatus.name,
      'bluetoothEnabled': _isBluetoothEnabled,
    };
  }

  void dispose() {
    _syncStatusController.close();
    _scanSubscription?.cancel();
    stopAdvertising();
    _discoveredDevices.clear();
    _connectedDevices.clear();
    _connectedAt.clear();
    _scanStatus = BluetoothScanStatus.idle;
    _connectionStatus = BluetoothConnectionStatus.disconnected;
  }
}
