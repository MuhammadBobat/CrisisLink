import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:gazalink/routing/feature_builder.dart';

void main() {
  test('feature builder reproduces the simulator state vectors', () {
    final cases = jsonDecode(File('test/fixtures/feature_cases.json').readAsStringSync()) as List;
    expect(cases.length, greaterThan(20));
    for (final c in cases) {
      final i = c['inputs'] as Map<String, dynamic>;
      final got = buildFeatures(DecisionInputs(
        urgent: i['urgent'],
        ttlLeft: (i['ttlLeft'] as num).toDouble(),
        hops: i['hops'],
        pSelfToDst: (i['pSelfToDst'] as num).toDouble(),
        pPeerToDst: (i['pPeerToDst'] as num).toDouble(),
        peerFree: (i['peerFree'] as num).toDouble(),
        selfFree: (i['selfFree'] as num).toDouble(),
        peerBattery: (i['peerBattery'] as num).toDouble(),
        selfBattery: (i['selfBattery'] as num).toDouble(),
        linkQuality: (i['linkQuality'] as num).toDouble(),
        contactAgeSeconds: (i['contactAgeSeconds'] as num).toDouble(),
        neighbours: i['neighbours'],
        isSource: i['isSource'],
        peerIsDestination: i['peerIsDestination'],
        copiesMade: i['copiesMade'],
      ));
      final want = (c['features'] as List).map((v) => (v as num).toDouble()).toList();
      expect(got.length, want.length);
      for (var k = 0; k < want.length; k++) {
        expect(got[k], closeTo(want[k], 1e-5), reason: '${kFeatureNames[k]} in $i');
      }
    }
  });

  test('rssi maps into [0, 1]', () {
    expect(linkQualityFromRssi(-120), 0.0);
    expect(linkQualityFromRssi(-30), 1.0);
    expect(linkQualityFromRssi(-70), closeTo(0.5, 1e-9));
  });
}
