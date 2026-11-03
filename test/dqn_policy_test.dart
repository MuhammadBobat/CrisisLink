import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:gazalink/routing/dqn_policy.dart';

void main() {
  final json = jsonDecode(File('assets/policy/dqn_policy.json').readAsStringSync()) as Map<String, dynamic>;

  test('shipped policy matches the Python network on parity vectors', () {
    final policy = DqnPolicy.fromJson(json);
    final inputs = (json['parity']['inputs'] as List).map((r) => (r as List).map((v) => (v as num).toDouble()).toList());
    final outputs = (json['parity']['outputs'] as List).map((r) => (r as List).map((v) => (v as num).toDouble()).toList());
    var checked = 0;
    for (final pair in List.generate(inputs.length, (i) => [inputs.elementAt(i), outputs.elementAt(i)])) {
      final got = policy.qValues(pair[0]);
      expect(got[0], closeTo(pair[1][0], 1e-4));
      expect(got[1], closeTo(pair[1][1], 1e-4));
      checked++;
    }
    expect(checked, greaterThan(10));
  });

  test('feature set mismatch is rejected', () {
    final bad = Map<String, dynamic>.from(json)..['feature_names'] = ['urgent'];
    expect(() => DqnPolicy.fromJson(bad), throwsFormatException);
  });
}
