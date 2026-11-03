import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gazalink/screens/message_create_screen.dart';
import 'package:gazalink/services/storage_service.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('rejects a malformed recipient node id', (tester) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(MaterialApp(
      home: Provider<StorageService>(create: (_) => StorageService(), child: const MessageCreateScreen()),
    ));

    await tester.enterText(find.byType(TextFormField).first, 'Need water at the north shelter');
    await tester.enterText(find.byType(TextFormField).last, 'XYZ');
    await tester.ensureVisible(find.byType(ElevatedButton));
    await tester.tap(find.byType(ElevatedButton));
    await tester.pump();

    expect(find.text('Node IDs are 8 characters (0-9, A-F)'), findsOneWidget);
  });
}
