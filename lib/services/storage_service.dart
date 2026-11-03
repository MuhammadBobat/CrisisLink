import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/message.dart';

/// Result of merging a peer's bundle into the local buffer.
class IngestResult {
  /// Messages stored as relay copies.
  final List<Message> relayed;

  /// Messages addressed to this node.
  final List<Message> delivered;
  final int dropped;

  const IngestResult({this.relayed = const [], this.delivered = const [], this.dropped = 0});
}

class StorageService {
  static const String _messagesKey = 'gazalink_messages';
  static const String _deliveredKey = 'gazalink_delivered_ids';

  /// Buffer size the routing policy was trained with.
  static const int bufferCapacity = 12;

  // Get all messages
  Future<List<Message>> getMessages() async {
    final prefs = await SharedPreferences.getInstance();
    final messagesJson = prefs.getStringList(_messagesKey) ?? [];

    return messagesJson
        .map((json) => Message.fromJson(jsonDecode(json)))
        .toList();
  }

  // Save a locally created message, making room if the buffer is full
  Future<void> saveMessage(Message message) async {
    final messages = await getMessages();

    final existingIndex = messages.indexWhere((m) => m.id == message.id);
    if (existingIndex != -1) {
      messages[existingIndex] = message;
    } else {
      if (!_makeRoom(messages, incomingUrgent: true, force: true)) {
        throw Exception('Buffer is full of urgent messages');
      }
      messages.add(message);
    }

    await _saveMessages(messages);
  }

  Future<void> updateMessage(Message message) async {
    final messages = await getMessages();

    final index = messages.indexWhere((m) => m.id == message.id);
    if (index != -1) {
      messages[index] = message;
      await _saveMessages(messages);
    }
  }

  Future<void> deleteMessage(String messageId) async {
    final messages = await getMessages();
    messages.removeWhere((message) => message.id == messageId);
    await _saveMessages(messages);
  }

  Future<void> clearAllMessages() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_messagesKey);
    await prefs.remove(_deliveredKey);
  }

  // Get sorted messages (urgent first, then by timestamp)
  Future<List<Message>> getSortedMessages() async {
    final messages = await getMessages();

    messages.sort((a, b) {
      if (a.priority != b.priority) {
        return a.priority == Priority.urgent ? -1 : 1;
      }
      return a.timestamp.compareTo(b.timestamp);
    });

    return messages;
  }

  Future<void> _saveMessages(List<Message> messages) async {
    final prefs = await SharedPreferences.getInstance();
    final messagesJson = messages
        .map((message) => jsonEncode(message.toJson()))
        .toList();

    await prefs.setStringList(_messagesKey, messagesJson);
  }

  Future<Map<String, int>> getAppStats() async {
    final messages = await getMessages();

    return {
      'totalMessages': messages.length,
      'pendingMessages': messages
          .where((m) => m.deliveryStatus == DeliveryStatus.pending)
          .length,
      'urgentMessages': messages
          .where((m) => m.priority == Priority.urgent)
          .length,
    };
  }

  // ---------------------------------------------------------------- routing

  /// Ids of messages known to have reached their destination (anti-packets).
  /// Exchanged during the handshake so nodes stop carrying delivered messages.
  Future<Set<String>> getDeliveredIds() async {
    final prefs = await SharedPreferences.getInstance();
    return (prefs.getStringList(_deliveredKey) ?? []).toSet();
  }

  Future<void> addDeliveredIds(Iterable<String> ids) async {
    if (ids.isEmpty) return;
    final prefs = await SharedPreferences.getInstance();
    final all = (prefs.getStringList(_deliveredKey) ?? []).toSet()..addAll(ids);
    await prefs.setStringList(_deliveredKey, all.toList());
  }

  /// Drop expired messages and relay copies of messages known to be delivered.
  Future<void> purge({DateTime? now}) async {
    final at = now ?? DateTime.now();
    final delivered = await getDeliveredIds();
    final messages = await getMessages();
    final kept = messages.where((m) {
      if (m.isExpiredAt(at)) return false;
      final isRelayCopy = m.deliveryStatus != DeliveryStatus.delivered && delivered.contains(m.id);
      return !isRelayCopy;
    }).toList();
    if (kept.length != messages.length) await _saveMessages(kept);
  }

  /// Messages this node still carries for others or itself (not yet delivered, not expired).
  Future<List<Message>> getForwardable({DateTime? now}) async {
    final at = now ?? DateTime.now();
    final delivered = await getDeliveredIds();
    return (await getMessages())
        .where((m) =>
            m.deliveryStatus != DeliveryStatus.delivered &&
            !m.isExpiredAt(at) &&
            !delivered.contains(m.id))
        .toList();
  }

  /// Merge a peer's bundle. Duplicates, expired and already-delivered messages are skipped.
  /// Messages addressed to [myNodeId] are stored as delivered; the rest become relay copies.
  /// A full buffer only admits urgent messages, by evicting the oldest normal one.
  Future<IngestResult> ingestMessages(List<Message> incoming, String myNodeId, {DateTime? now}) async {
    final at = now ?? DateTime.now();
    final messages = await getMessages();
    final delivered = await getDeliveredIds();
    final relayed = <Message>[];
    final reached = <Message>[];
    final newlyDelivered = <String>[];
    var dropped = 0;

    for (final msg in incoming) {
      final known = messages.any((m) => m.id == msg.id);
      if (known || msg.isExpiredAt(at) || delivered.contains(msg.id)) {
        dropped++;
        continue;
      }
      if (msg.destinationId == myNodeId) {
        final stored = msg.copyWith(deliveryStatus: DeliveryStatus.delivered);
        messages.add(stored);
        reached.add(stored);
        newlyDelivered.add(msg.id);
        continue;
      }
      if (!_makeRoom(messages, incomingUrgent: msg.isUrgent)) {
        dropped++;
        continue;
      }
      final stored = msg.copyWith(deliveryStatus: DeliveryStatus.sent);
      messages.add(stored);
      relayed.add(stored);
    }

    await _saveMessages(messages);
    await addDeliveredIds(newlyDelivered);
    return IngestResult(relayed: relayed, delivered: reached, dropped: dropped);
  }

  /// Record that [message] was handed to a peer. Relay copies are dropped once the
  /// destination has it; our own messages stay visible as delivered.
  Future<void> markForwarded(Message message, {required bool toDestination}) async {
    if (toDestination) {
      await addDeliveredIds([message.id]);
      await updateDeliveryStatus(message.id, DeliveryStatus.delivered);
      return;
    }
    final messages = await getMessages();
    final index = messages.indexWhere((m) => m.id == message.id);
    if (index == -1) return;
    final current = messages[index];
    messages[index] = current.copyWith(
      copiesMade: current.copiesMade + 1,
      deliveryStatus: current.deliveryStatus == DeliveryStatus.pending ? DeliveryStatus.sent : null,
    );
    await _saveMessages(messages);
  }

  /// Make room for one more message. Only urgent messages may evict, and only a
  /// normal one (the oldest). [force] lets locally created messages evict too.
  bool _makeRoom(List<Message> messages, {required bool incomingUrgent, bool force = false}) {
    final live = messages.where((m) => m.deliveryStatus != DeliveryStatus.delivered).length;
    if (live < bufferCapacity) return true;
    if (!incomingUrgent && !force) return false;
    final victims = messages
        .where((m) => m.deliveryStatus != DeliveryStatus.delivered && !m.isUrgent)
        .toList()
      ..sort((a, b) => a.timestamp.compareTo(b.timestamp));
    if (victims.isEmpty) return false;
    messages.removeWhere((m) => m.id == victims.first.id);
    return true;
  }

  Future<void> updateDeliveryStatus(String messageId, DeliveryStatus status) async {
    try {
      final messages = await getMessages();
      final index = messages.indexWhere((m) => m.id == messageId);

      if (index != -1) {
        messages[index] = messages[index].copyWith(deliveryStatus: status);
        await _saveMessages(messages);
      }
    } catch (e) {
      throw Exception('Failed to update delivery status: $e');
    }
  }
}
