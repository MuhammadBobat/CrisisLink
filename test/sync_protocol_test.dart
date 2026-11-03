import 'package:flutter_test/flutter_test.dart';
import 'package:gazalink/services/sync_protocol.dart';

void main() {
  test('frame round-trips across small chunks', () {
    final payload = {'t': 'bundle', 'messages': List.generate(40, (i) => {'id': 'm$i', 'text': 'héllo $i'})};
    final chunks = FrameCodec.encode(payload, chunkSize: 20);
    expect(chunks.length, greaterThan(10));
    expect(chunks.every((c) => c.length <= 20), isTrue);

    final assembler = FrameAssembler();
    Object? out;
    for (var i = 0; i < chunks.length; i++) {
      out = assembler.add(chunks[i]);
      if (i < chunks.length - 1) expect(out, isNull);
    }
    expect(out, payload);
  });

  test('assembler waits for a split header', () {
    final chunks = FrameCodec.encode({'a': 1}, chunkSize: 2);
    final assembler = FrameAssembler();
    expect(assembler.add(chunks.first), isNull);
    Object? out;
    for (final c in chunks.skip(1)) {
      out = assembler.add(c);
    }
    expect(out, {'a': 1});
  });

  test('oversize frames are rejected', () {
    final huge = {'x': 'a' * (FrameCodec.maxFrameBytes + 1)};
    expect(() => FrameCodec.encode(huge, chunkSize: 20), throwsArgumentError);
  });
}
