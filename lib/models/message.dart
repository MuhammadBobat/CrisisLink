import 'package:uuid/uuid.dart';

enum Priority { urgent, normal }
enum DeliveryStatus { pending, sent, delivered }

/// Default message lifetime; matches the TTL the routing policy was trained with.
const int kDefaultTtlSeconds = 2 * 3600;

class Message {
  final String id;
  final String content;
  final Priority priority;
  final DateTime timestamp;
  final DeliveryStatus deliveryStatus;

  /// Node that created the message ('' for messages created before routing existed).
  final String sourceId;

  /// Recipient node. Null or empty means broadcast (flooded with a small hop limit).
  final String? destinationId;
  final int ttlSeconds;

  /// Relays this copy has travelled through.
  final int hopCount;

  /// Copies this node has handed to other peers. Local bookkeeping: reset on transfer.
  final int copiesMade;

  Message({
    String? id,
    required this.content,
    required this.priority,
    DateTime? timestamp,
    DeliveryStatus? deliveryStatus,
    this.sourceId = '',
    this.destinationId,
    this.ttlSeconds = kDefaultTtlSeconds,
    this.hopCount = 0,
    this.copiesMade = 0,
  })  : id = id ?? const Uuid().v4(),
        timestamp = timestamp ?? DateTime.now(),
        deliveryStatus = deliveryStatus ?? DeliveryStatus.pending;

  bool get isBroadcast => destinationId == null || destinationId!.isEmpty;
  bool get isUrgent => priority == Priority.urgent;
  DateTime get expiresAt => timestamp.add(Duration(seconds: ttlSeconds));
  bool isExpiredAt(DateTime now) => !now.isBefore(expiresAt);

  /// Remaining lifetime as a fraction of the TTL, in [0, 1].
  double ttlLeft(DateTime now) {
    final left = expiresAt.difference(now).inMilliseconds / (ttlSeconds * 1000);
    return left.clamp(0.0, 1.0).toDouble();
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'content': content,
      'priority': priority.name,
      'timestamp': timestamp.toIso8601String(),
      'deliveryStatus': deliveryStatus.name,
      'sourceId': sourceId,
      'destinationId': destinationId,
      'ttlSeconds': ttlSeconds,
      'hopCount': hopCount,
      'copiesMade': copiesMade,
    };
  }

  factory Message.fromJson(Map<String, dynamic> json) {
    return Message(
      id: json['id'],
      content: json['content'],
      priority: Priority.values.firstWhere(
        (e) => e.name == json['priority'],
      ),
      timestamp: DateTime.parse(json['timestamp']),
      deliveryStatus: DeliveryStatus.values.firstWhere(
        (e) => e.name == json['deliveryStatus'],
      ),
      sourceId: json['sourceId'] ?? '',
      destinationId: json['destinationId'],
      ttlSeconds: json['ttlSeconds'] ?? kDefaultTtlSeconds,
      hopCount: json['hopCount'] ?? 0,
      copiesMade: json['copiesMade'] ?? 0,
    );
  }

  Message copyWith({
    String? id,
    String? content,
    Priority? priority,
    DateTime? timestamp,
    DeliveryStatus? deliveryStatus,
    String? sourceId,
    String? destinationId,
    int? ttlSeconds,
    int? hopCount,
    int? copiesMade,
  }) {
    return Message(
      id: id ?? this.id,
      content: content ?? this.content,
      priority: priority ?? this.priority,
      timestamp: timestamp ?? this.timestamp,
      deliveryStatus: deliveryStatus ?? this.deliveryStatus,
      sourceId: sourceId ?? this.sourceId,
      destinationId: destinationId ?? this.destinationId,
      ttlSeconds: ttlSeconds ?? this.ttlSeconds,
      hopCount: hopCount ?? this.hopCount,
      copiesMade: copiesMade ?? this.copiesMade,
    );
  }
}
