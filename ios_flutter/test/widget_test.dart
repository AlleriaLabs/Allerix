import 'package:flutter_test/flutter_test.dart';
import 'package:allerix_ios/main.dart';

void main() {
  testWidgets('Allerix login screen shows branding and server field', (tester) async {
    await tester.pumpWidget(const AllerixApp());
    expect(find.text('ALLERIA'), findsOneWidget);
    expect(find.text('FILMOTHÈQUE'), findsOneWidget);
    expect(find.text('Jellyfin server URL'), findsOneWidget);
  });
}
