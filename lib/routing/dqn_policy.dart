import 'dart:convert';
import 'package:flutter/services.dart' show AssetBundle, rootBundle;
import 'feature_builder.dart';

class _Layer {
  final List<List<double>> w; // [in][out]
  final List<double> b;
  final bool relu;
  _Layer(this.w, this.b, this.relu);
}

/// Q-network trained in simulation (simulation/train.py), evaluated on-device.
/// Actions: index 0 = hold, 1 = forward.
class DqnPolicy {
  static const String assetPath = 'assets/policy/dqn_policy.json';

  final List<_Layer> _layers;
  final Map<String, dynamic> meta;

  /// Forward when Q(forward) - Q(hold) exceeds this margin. Raise it to save battery.
  double margin;

  DqnPolicy._(this._layers, this.meta, this.margin);

  factory DqnPolicy.fromJson(Map<String, dynamic> json, {double margin = 0.0}) {
    final names = (json['feature_names'] as List).cast<String>();
    if (names.length != kFeatureNames.length ||
        !List.generate(names.length, (i) => names[i] == kFeatureNames[i]).every((ok) => ok)) {
      throw FormatException('Policy feature set does not match the app: $names');
    }
    final layers = (json['layers'] as List).map((l) {
      final w = (l['w'] as List).map((row) => (row as List).map((v) => (v as num).toDouble()).toList()).toList();
      final b = (l['b'] as List).map((v) => (v as num).toDouble()).toList();
      return _Layer(w, b, l['act'] == 'relu');
    }).toList();
    return DqnPolicy._(layers, Map<String, dynamic>.from(json['meta'] ?? {}), margin);
  }

  static Future<DqnPolicy> loadAsset({AssetBundle? bundle, double margin = 0.0}) async {
    final raw = await (bundle ?? rootBundle).loadString(assetPath);
    return DqnPolicy.fromJson(jsonDecode(raw), margin: margin);
  }

  /// Returns [Q(hold), Q(forward)].
  List<double> qValues(List<double> features) {
    var h = features;
    for (final layer in _layers) {
      final out = List<double>.from(layer.b);
      for (var i = 0; i < h.length; i++) {
        final row = layer.w[i];
        final xi = h[i];
        if (xi == 0) continue;
        for (var j = 0; j < out.length; j++) {
          out[j] += xi * row[j];
        }
      }
      if (layer.relu) {
        for (var j = 0; j < out.length; j++) {
          if (out[j] < 0) out[j] = 0;
        }
      }
      h = out;
    }
    return h;
  }

  /// Q-advantage of forwarding over holding; also used to rank candidates.
  double advantage(List<double> features) {
    final q = qValues(features);
    return q[1] - q[0];
  }

  bool shouldForward(List<double> features) => advantage(features) > margin;
}
