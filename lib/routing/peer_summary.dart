/// Handshake a node sends when two nodes connect. Small enough for a low-rate BLE link.
class PeerSummary {
  static const int version = 1;

  final String nodeId;
  final double battery; // 0..1
  final double bufferFree; // 0..1
  final Map<String, double> predictability;
  final Set<String> messageIds;
  final Set<String> deliveredIds;

  const PeerSummary({
    required this.nodeId,
    required this.battery,
    required this.bufferFree,
    required this.predictability,
    required this.messageIds,
    required this.deliveredIds,
  });

  Map<String, dynamic> toJson() => {
        'v': version,
        'nodeId': nodeId,
        'battery': battery,
        'bufferFree': bufferFree,
        'p': predictability,
        'have': messageIds.toList(),
        'delivered': deliveredIds.toList(),
      };

  factory PeerSummary.fromJson(Map<String, dynamic> json) {
    if (json['v'] != version) {
      throw FormatException('Unsupported handshake version ${json['v']}');
    }
    return PeerSummary(
      nodeId: json['nodeId'] as String,
      battery: (json['battery'] as num).toDouble().clamp(0.0, 1.0).toDouble(),
      bufferFree: (json['bufferFree'] as num).toDouble().clamp(0.0, 1.0).toDouble(),
      predictability: (json['p'] as Map<String, dynamic>).map((k, v) => MapEntry(k, (v as num).toDouble())),
      messageIds: (json['have'] as List).cast<String>().toSet(),
      deliveredIds: (json['delivered'] as List).cast<String>().toSet(),
    );
  }
}
