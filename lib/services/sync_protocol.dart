import 'dart:convert';
import 'dart:typed_data';

/// Length-prefixed framing over a GATT characteristic.
///
/// A frame is a 4-byte big-endian payload length followed by UTF-8 JSON, split into
/// chunks no larger than the link's ATT payload. This replaces "read until empty",
/// which cannot tell a finished transfer from a stalled one on a lossy low-rate link.
class FrameCodec {
  static const int headerBytes = 4;
  static const int maxFrameBytes = 256 * 1024;

  static List<List<int>> encode(Object json, {required int chunkSize}) {
    assert(chunkSize > 0);
    final body = utf8.encode(jsonEncode(json));
    if (body.length > maxFrameBytes) {
      throw ArgumentError('Frame of ${body.length} bytes exceeds $maxFrameBytes');
    }
    final framed = Uint8List(headerBytes + body.length);
    ByteData.sublistView(framed).setUint32(0, body.length);
    framed.setRange(headerBytes, framed.length, body);
    final chunks = <List<int>>[];
    for (var i = 0; i < framed.length; i += chunkSize) {
      final end = i + chunkSize < framed.length ? i + chunkSize : framed.length;
      chunks.add(framed.sublist(i, end));
    }
    return chunks;
  }
}

/// Reassembles chunks into a frame. Feed chunks until [add] returns the decoded JSON.
class FrameAssembler {
  final BytesBuilder _buf = BytesBuilder(copy: false);

  /// Returns the decoded frame once complete, otherwise null.
  Object? add(List<int> chunk) {
    _buf.add(chunk);
    if (_buf.length < FrameCodec.headerBytes) return null;
    final bytes = _buf.toBytes();
    final length = ByteData.sublistView(bytes).getUint32(0);
    if (length > FrameCodec.maxFrameBytes) {
      throw const FormatException('Frame length exceeds limit');
    }
    if (bytes.length < FrameCodec.headerBytes + length) return null;
    final payload = bytes.sublist(FrameCodec.headerBytes, FrameCodec.headerBytes + length);
    return jsonDecode(utf8.decode(payload));
  }

  int get bytesBuffered => _buf.length;
}
