import 'dart:convert';
import 'dart:math';
import 'package:shared_preferences/shared_preferences.dart';

/// Encounter predictability P(self, x): how likely this node is to meet x again soon.
/// PRoPHET-style update with aging and transitivity; constants match SimConfig.
class EncounterTable {
  static const double pInit = 0.75;
  static const double beta = 0.25;
  static const double gamma = 0.98;
  static const double unitSeconds = 600.0;
  static const int maxExported = 64;
  static const String _key = 'gazalink_encounters';

  final Map<String, double> _p = {};
  DateTime _updated;

  EncounterTable({DateTime? now}) : _updated = now ?? DateTime.now();

  static Future<EncounterTable> load({DateTime? now}) async {
    final table = EncounterTable(now: now);
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_key);
    if (raw != null) {
      final json = jsonDecode(raw) as Map<String, dynamic>;
      table._updated = DateTime.parse(json['updated']);
      (json['p'] as Map<String, dynamic>).forEach((k, v) => table._p[k] = (v as num).toDouble());
    }
    return table;
  }

  Future<void> save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, jsonEncode({'updated': _updated.toIso8601String(), 'p': _p}));
  }

  void _age(DateTime now) {
    final elapsed = now.difference(_updated).inMilliseconds / 1000.0;
    if (elapsed <= 0) return;
    final factor = pow(gamma, elapsed / unitSeconds).toDouble();
    _p.updateAll((_, v) => v * factor);
    _p.removeWhere((_, v) => v < 0.001);
    _updated = now;
  }

  double predictability(String nodeId, {DateTime? now}) {
    _age(now ?? DateTime.now());
    return _p[nodeId] ?? 0.0;
  }

  /// This node's row, strongest entries first, for the handshake.
  Map<String, double> export({DateTime? now}) {
    _age(now ?? DateTime.now());
    final entries = _p.entries.toList()..sort((a, b) => b.value.compareTo(a.value));
    return {for (final e in entries.take(maxExported)) e.key: double.parse(e.value.toStringAsFixed(4))};
  }

  /// Update after meeting [peerId], who reported its own row [peerRow].
  void onEncounter(String peerId, Map<String, double> peerRow, {DateTime? now, required String selfId}) {
    _age(now ?? DateTime.now());
    final direct = (_p[peerId] ?? 0.0);
    final pPeer = direct + (1 - direct) * pInit;
    _p[peerId] = pPeer;
    peerRow.forEach((node, pPeerToNode) {
      if (node == selfId || node == peerId) return;
      final cur = _p[node] ?? 0.0;
      _p[node] = cur + (1 - cur) * pPeer * pPeerToNode * beta;
    });
  }
}
