import 'package:flutter_test/flutter_test.dart';
import 'package:gazalink/models/message.dart';
import 'package:gazalink/routing/dqn_policy.dart';
import 'package:gazalink/routing/encounter_table.dart';
import 'package:gazalink/routing/peer_summary.dart';
import 'package:gazalink/routing/routing_service.dart';
import 'package:gazalink/services/storage_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Policy stub: forwards only when the peer is more likely to meet the destination.
DqnPolicy _policy({double bias = 0}) {
  // Single linear layer: advantage = (p_b_dst - p_a_dst) * 2 via p_delta feature (index 5).
  final w = List.generate(16, (i) => i == 5 ? [0.0, 4.0] : [0.0, 0.0]);
  return DqnPolicy.fromJson({
    'feature_names': [
      'urgent', 'ttl_left', 'hops', 'p_a_dst', 'p_b_dst', 'p_delta', 'b_free', 'a_free',
      'b_battery', 'a_battery', 'link_quality', 'contact_age', 'neighbours', 'is_source', 'b_is_dst',
      'copies_made',
    ],
    'layers': [
      {'w': w, 'b': [0.0, -2.0 + bias], 'act': 'linear'}
    ],
  });
}

PeerSummary _peer(String id, {Map<String, double> p = const {}, Set<String> have = const {}, Set<String> delivered = const {}}) =>
    PeerSummary(nodeId: id, battery: 1, bufferFree: 1, predictability: p, messageIds: have, deliveredIds: delivered);

void main() {
  late StorageService storage;
  late RoutingService routing;
  final now = DateTime(2026, 1, 1, 12);

  setUp(() async {
    SharedPreferences.setMockInitialValues({'gazalink_node_id': 'AAAAAAAA'});
    storage = StorageService();
    routing = RoutingService(storage: storage, batteryLevel: () async => 1.0);
    await routing.initialize(override: _policy(), loadAsset: false);
  });

  Message msg(String id, {String? dst, Priority pr = Priority.normal, int hops = 0, DateTime? at}) => Message(
        id: id,
        content: 'hello $id',
        priority: pr,
        timestamp: at ?? now,
        sourceId: 'AAAAAAAA',
        destinationId: dst,
        hopCount: hops,
      );

  test('delivers to the destination first, then urgent, regardless of policy', () async {
    await storage.saveMessage(msg('normal', dst: 'CCCCCCCC'));
    await storage.saveMessage(msg('urgent', dst: 'CCCCCCCC', pr: Priority.urgent));
    await storage.saveMessage(msg('direct', dst: 'BBBBBBBB'));
    final peer = _peer('BBBBBBBB', p: {'CCCCCCCC': 0.9});
    final picked = await routing.selectForPeer(peer, const LinkContext(rssi: -60), now: now);
    expect(picked.map((m) => m.id), ['direct', 'urgent', 'normal']);
  });

  test('holds when the peer is no better placed than we are', () async {
    await storage.saveMessage(msg('m', dst: 'CCCCCCCC'));
    final picked = await routing.selectForPeer(_peer('BBBBBBBB'), const LinkContext(rssi: -60), now: now);
    expect(picked, isEmpty);
  });

  test('skips messages the peer already has or knows are delivered, and expired ones', () async {
    await storage.saveMessage(msg('has', dst: 'CCCCCCCC'));
    await storage.saveMessage(msg('done', dst: 'CCCCCCCC'));
    await storage.saveMessage(msg('old', dst: 'CCCCCCCC', at: now.subtract(const Duration(hours: 3))));
    await storage.saveMessage(msg('fresh', dst: 'CCCCCCCC'));
    final peer = _peer('BBBBBBBB', p: {'CCCCCCCC': 0.9}, have: {'has'}, delivered: {'done'});
    final picked = await routing.selectForPeer(peer, const LinkContext(rssi: -60), now: now);
    expect(picked.map((m) => m.id), ['fresh']);
  });

  test('respects the per-exchange budget and hop limit', () async {
    for (var i = 0; i < 10; i++) {
      await storage.saveMessage(msg('m$i', dst: 'CCCCCCCC'));
    }
    await storage.saveMessage(msg('tired', dst: 'CCCCCCCC', hops: 10));
    final peer = _peer('BBBBBBBB', p: {'CCCCCCCC': 0.9});
    final picked = await routing.selectForPeer(peer, const LinkContext(rssi: -60), now: now);
    expect(picked.length, RoutingService.maxPerExchange);
    expect(picked.any((m) => m.id == 'tired'), isFalse);
  });

  test('broadcasts flood only up to the hop cap', () async {
    await storage.saveMessage(msg('b0'));
    await storage.saveMessage(msg('b3', hops: RoutingService.maxBroadcastHops));
    final picked = await routing.selectForPeer(_peer('BBBBBBBB'), const LinkContext(rssi: -60), now: now);
    expect(picked.map((m) => m.id), ['b0']);
  });

  test('falls back to the PRoPHET rule without a learned policy', () async {
    SharedPreferences.setMockInitialValues({'gazalink_node_id': 'AAAAAAAA'});
    final fallback = RoutingService(storage: StorageService(), batteryLevel: () async => 1.0);
    await fallback.initialize(loadAsset: false);
    expect(fallback.usingLearnedPolicy, isFalse);
    await storage.saveMessage(msg('m', dst: 'CCCCCCCC'));
    final better = await fallback.selectForPeer(_peer('BBBBBBBB', p: {'CCCCCCCC': 0.5}), const LinkContext(rssi: -60), now: now);
    final worse = await fallback.selectForPeer(_peer('DDDDDDDD'), const LinkContext(rssi: -60), now: now);
    expect(better.map((m) => m.id), ['m']);
    expect(worse, isEmpty);
  });

  group('storage', () {
    test('ingest stores relay copies, marks delivered for us, ignores duplicates', () async {
      final incoming = [msg('relay', dst: 'CCCCCCCC'), msg('mine', dst: 'AAAAAAAA')];
      final first = await storage.ingestMessages(incoming, 'AAAAAAAA', now: now);
      expect(first.relayed.map((m) => m.id), ['relay']);
      expect(first.delivered.map((m) => m.id), ['mine']);
      expect(await storage.getDeliveredIds(), contains('mine'));
      final again = await storage.ingestMessages(incoming, 'AAAAAAAA', now: now);
      expect(again.dropped, 2);
    });

    test('full buffer admits only urgent messages by evicting the oldest normal one', () async {
      for (var i = 0; i < StorageService.bufferCapacity; i++) {
        await storage.ingestMessages([msg('n$i', dst: 'CCCCCCCC', at: now.add(Duration(minutes: i)))], 'AAAAAAAA', now: now);
      }
      final normal = await storage.ingestMessages([msg('extra', dst: 'CCCCCCCC')], 'AAAAAAAA', now: now);
      expect(normal.dropped, 1);
      final urgent = await storage.ingestMessages([msg('u', dst: 'CCCCCCCC', pr: Priority.urgent)], 'AAAAAAAA', now: now);
      expect(urgent.relayed.length, 1);
      final ids = (await storage.getMessages()).map((m) => m.id).toSet();
      expect(ids, contains('u'));
      expect(ids, isNot(contains('n0')));
    });

    test('purge drops expired messages and relay copies of delivered ones', () async {
      await storage.ingestMessages([msg('old', dst: 'CCCCCCCC', at: now.subtract(const Duration(hours: 5))), msg('gone', dst: 'CCCCCCCC')], 'AAAAAAAA', now: now.subtract(const Duration(hours: 5)));
      await storage.addDeliveredIds(['gone']);
      await storage.purge(now: now);
      expect(await storage.getMessages(), isEmpty);
    });

    test('relaying counts copies made and marks the message sent', () async {
      final m = msg('m', dst: 'CCCCCCCC');
      await storage.saveMessage(m);
      await storage.markForwarded(m, toDestination: false);
      final stored = (await storage.getMessages()).single;
      expect(stored.copiesMade, 1);
      expect(stored.deliveryStatus, DeliveryStatus.sent);
      expect(routing.prepareForTransfer(stored).copiesMade, 0);
      expect(routing.prepareForTransfer(stored).hopCount, 1);
    });

    test('forwarding to the destination records an anti-packet', () async {
      final m = msg('m', dst: 'BBBBBBBB');
      await storage.saveMessage(m);
      await storage.markForwarded(m, toDestination: true);
      expect(await storage.getDeliveredIds(), contains('m'));
      expect((await storage.getMessages()).single.deliveryStatus, DeliveryStatus.delivered);
    });
  });

  group('encounter table', () {
    test('direct encounter raises predictability, aging lowers it', () {
      final t = EncounterTable(now: now);
      t.onEncounter('B', {}, now: now, selfId: 'A');
      expect(t.predictability('B', now: now), closeTo(0.75, 1e-9));
      expect(t.predictability('B', now: now.add(const Duration(hours: 1))), lessThan(0.75));
    });

    test('transitivity learns about nodes only the peer has met', () {
      final t = EncounterTable(now: now);
      t.onEncounter('B', {'C': 0.8}, now: now, selfId: 'A');
      expect(t.predictability('C', now: now), closeTo(0.75 * 0.8 * 0.25, 1e-9));
    });

    test('repeat encounters saturate below 1', () {
      final t = EncounterTable(now: now);
      for (var i = 0; i < 20; i++) {
        t.onEncounter('B', {}, now: now, selfId: 'A');
      }
      expect(t.predictability('B', now: now), lessThan(1.0));
      expect(t.predictability('B', now: now), greaterThan(0.99));
    });
  });
}
