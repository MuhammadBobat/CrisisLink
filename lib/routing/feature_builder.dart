/// State features for one forwarding decision: message [m] at this node, candidate peer.
///
/// Order and definitions mirror simulation/crisislink/features.py. Every feature is in
/// [0, 1]. test/feature_parity_test.dart checks this against vectors produced by the
/// simulator.
const List<String> kFeatureNames = [
  'urgent',
  'ttl_left',
  'hops',
  'p_a_dst',
  'p_b_dst',
  'p_delta',
  'b_free',
  'a_free',
  'b_battery',
  'a_battery',
  'link_quality',
  'contact_age',
  'neighbours',
  'is_source',
  'b_is_dst',
  'copies_made',
];

const double kContactAgeCapSeconds = 300.0;
const double kNeighbourCap = 5.0;
const double kCopiesCap = 8.0;
const int kMaxHops = 10;

/// Raw, observable inputs for a decision (all taken from local state or the peer handshake).
class DecisionInputs {
  final bool urgent;
  final double ttlLeft;
  final int hops;
  final double pSelfToDst;
  final double pPeerToDst;
  final double peerFree;
  final double selfFree;
  final double peerBattery;
  final double selfBattery;
  final double linkQuality;
  final double contactAgeSeconds;
  final int neighbours;
  final bool isSource;
  final bool peerIsDestination;

  /// Copies of this message this node has already handed to other peers.
  final int copiesMade;

  const DecisionInputs({
    required this.urgent,
    required this.ttlLeft,
    required this.hops,
    required this.pSelfToDst,
    required this.pPeerToDst,
    required this.peerFree,
    required this.selfFree,
    required this.peerBattery,
    required this.selfBattery,
    required this.linkQuality,
    required this.contactAgeSeconds,
    required this.neighbours,
    required this.isSource,
    required this.peerIsDestination,
    required this.copiesMade,
  });
}

double _unit(double v) => v.clamp(0.0, 1.0).toDouble();

List<double> buildFeatures(DecisionInputs x) {
  return [
    x.urgent ? 1.0 : 0.0,
    _unit(x.ttlLeft),
    _unit(x.hops / kMaxHops),
    _unit(x.pSelfToDst),
    _unit(x.pPeerToDst),
    _unit((x.pPeerToDst - x.pSelfToDst + 1) / 2),
    _unit(x.peerFree),
    _unit(x.selfFree),
    _unit(x.peerBattery),
    _unit(x.selfBattery),
    _unit(x.linkQuality),
    _unit(x.contactAgeSeconds / kContactAgeCapSeconds),
    _unit(x.neighbours / kNeighbourCap),
    x.isSource ? 1.0 : 0.0,
    x.peerIsDestination ? 1.0 : 0.0,
    _unit(x.copiesMade / kCopiesCap),
  ];
}

/// Map BLE RSSI (dBm) to the simulator's link-quality scale. -100 dBm is the edge of
/// usable range, -40 dBm is adjacent.
double linkQualityFromRssi(int rssi) => _unit((rssi + 100) / 60);
