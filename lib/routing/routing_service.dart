import 'dart:developer' as developer;
import '../models/message.dart';
import '../services/storage_service.dart';
import 'dqn_policy.dart';
import 'encounter_table.dart';
import 'feature_builder.dart';
import 'node_identity.dart';
import 'peer_summary.dart';

/// Live conditions of the link to one peer.
class LinkContext {
  final int rssi;
  final Duration connectedFor;
  final int neighbours;

  const LinkContext({required this.rssi, this.connectedFor = Duration.zero, this.neighbours = 0});
}

/// Hop-by-hop forwarding from the local view: for each message we hold, decide whether to
/// hand a copy to the peer in front of us. No path is computed; the only inputs are our own
/// state and the peer's handshake.
class RoutingService {
  /// Messages sent per connection. The simulator allowed 2 per 10 s step on the same link.
  static const int maxPerExchange = 6;

  /// Hop limit for broadcasts (no destination), which cannot be routed.
  static const int maxBroadcastHops = 3;

  final StorageService storage;
  final Future<double> Function() batteryLevel;
  DqnPolicy? policy;
  late String nodeId;
  late EncounterTable encounters;
  bool _ready = false;

  RoutingService({required this.storage, required this.batteryLevel, this.policy});

  bool get isReady => _ready;
  bool get usingLearnedPolicy => policy != null;

  Future<void> initialize({DqnPolicy? override, bool loadAsset = true}) async {
    if (_ready) return;
    nodeId = await NodeIdentity.load();
    encounters = await EncounterTable.load();
    if (override != null) {
      policy = override;
    } else if (policy == null && loadAsset) {
      try {
        policy = await DqnPolicy.loadAsset();
      } catch (e) {
        developer.log('Learned policy unavailable, falling back to PRoPHET rule: $e', name: 'RoutingService');
      }
    }
    _ready = true;
  }

  Future<PeerSummary> buildSummary({DateTime? now}) async {
    final at = now ?? DateTime.now();
    final held = await storage.getForwardable(now: at);
    return PeerSummary(
      nodeId: nodeId,
      battery: (await batteryLevel()).clamp(0.0, 1.0).toDouble(),
      bufferFree: 1.0 - held.length / StorageService.bufferCapacity,
      predictability: encounters.export(now: at),
      messageIds: held.map((m) => m.id).toSet(),
      deliveredIds: await storage.getDeliveredIds(),
    );
  }

  /// Record the encounter and absorb the peer's delivered ids (anti-packets).
  Future<void> onHandshake(PeerSummary peer, {DateTime? now}) async {
    encounters.onEncounter(peer.nodeId, peer.predictability, now: now, selfId: nodeId);
    await encounters.save();
    await storage.addDeliveredIds(peer.deliveredIds);
    await storage.purge(now: now);
  }

  /// Choose which local messages to send to [peer], best first, capped at [maxPerExchange].
  /// Order: messages for the peer itself, then urgent before normal, then by Q-advantage.
  Future<List<Message>> selectForPeer(PeerSummary peer, LinkContext link, {DateTime? now}) async {
    final at = now ?? DateTime.now();
    final local = await storage.getForwardable(now: at);
    final selfBattery = (await batteryLevel()).clamp(0.0, 1.0).toDouble();
    final selfFree = 1.0 - local.length / StorageService.bufferCapacity;
    final delivered = await storage.getDeliveredIds();

    final scored = <_Candidate>[];
    for (final m in local) {
      if (peer.messageIds.contains(m.id) || peer.deliveredIds.contains(m.id) || delivered.contains(m.id)) continue;
      if (m.hopCount >= kMaxHops) continue;

      if (m.destinationId == peer.nodeId) {
        scored.add(_Candidate(m, 0, 1e9));
        continue;
      }
      if (m.isBroadcast) {
        if (m.hopCount < maxBroadcastHops) scored.add(_Candidate(m, m.isUrgent ? 1 : 2, 0));
        continue;
      }

      final dst = m.destinationId!;
      final pSelf = encounters.predictability(dst, now: at);
      final pPeer = peer.predictability[dst] ?? 0.0;
      final features = buildFeatures(DecisionInputs(
        urgent: m.isUrgent,
        ttlLeft: m.ttlLeft(at),
        hops: m.hopCount,
        pSelfToDst: pSelf,
        pPeerToDst: pPeer,
        peerFree: peer.bufferFree,
        selfFree: selfFree,
        peerBattery: peer.battery,
        selfBattery: selfBattery,
        linkQuality: linkQualityFromRssi(link.rssi),
        contactAgeSeconds: link.connectedFor.inMilliseconds / 1000.0,
        neighbours: link.neighbours,
        isSource: m.sourceId == nodeId,
        peerIsDestination: false,
        copiesMade: m.copiesMade,
      ));

      final double score;
      final bool forward;
      final p = policy;
      if (p != null) {
        score = p.advantage(features);
        forward = score > p.margin;
      } else {
        score = pPeer - pSelf; // PRoPHET fallback
        forward = score > 0;
      }
      if (forward) scored.add(_Candidate(m, m.isUrgent ? 1 : 2, score));
    }

    scored.sort((a, b) {
      final byClass = a.rank.compareTo(b.rank);
      return byClass != 0 ? byClass : b.score.compareTo(a.score);
    });
    return scored.take(maxPerExchange).map((c) => c.message).toList();
  }

  /// Copy that travels to the peer: one more hop, and the receiver starts with no copies made.
  Message prepareForTransfer(Message m) => m.copyWith(hopCount: m.hopCount + 1, copiesMade: 0);
}

class _Candidate {
  final Message message;
  final int rank; // 0 = for the peer, 1 = urgent, 2 = normal
  final double score;
  _Candidate(this.message, this.rank, this.score);
}
