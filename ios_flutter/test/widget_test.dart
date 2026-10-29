import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:allerix_ios/main.dart';
import 'package:allerix_ios/jellyfin_client.dart';

void main() {
  testWidgets('Allerix login screen shows branding and server field', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: LoginScreen(onSignedIn: _ignoreSession),
      ),
    );

    expect(find.text('ALLERIA'), findsOneWidget);
    expect(find.text('FILMOTHÈQUE'), findsOneWidget);
    expect(find.text('Jellyfin server URL'), findsOneWidget);
    expect(find.text('SIGN IN'), findsOneWidget);
  });
}

void _ignoreSession(JellyfinSession session) {}
