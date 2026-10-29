import 'package:flutter/material.dart';
import 'config.dart';

void main() => runApp(const AllerixApp());

class AllerixApp extends StatelessWidget {
  const AllerixApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: appDisplayName,
    debugShowCheckedModeBanner: false,
    theme: ThemeData(brightness: Brightness.dark, scaffoldBackgroundColor: const Color(afBackgroundValue), colorScheme: const ColorScheme.dark(primary: Color(afRedValue), surface: Color(afSurfaceValue)), useMaterial3: true),
    home: const LoginScreen(),
  );
}

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final server = TextEditingController(text: builtInServerUrl);
  final username = TextEditingController();
  final password = TextEditingController();
  @override
  void dispose() { server.dispose(); username.dispose(); password.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(child: Center(child: SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: ConstrainedBox(constraints: const BoxConstraints(maxWidth: 440), child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Icon(Icons.movie_filter_outlined, size: 62, color: Color(afRedValue)),
          const SizedBox(height: 18),
          const Text('ALLERIA', textAlign: TextAlign.center, style: TextStyle(letterSpacing: 7, color: Colors.white60)),
          const SizedBox(height: 6),
          const Text('FILMOTHÈQUE', textAlign: TextAlign.center, style: TextStyle(fontSize: 28, letterSpacing: 2, fontWeight: FontWeight.w800)),
          const SizedBox(height: 34),
          TextField(controller: server, keyboardType: TextInputType.url, autocorrect: false, decoration: const InputDecoration(labelText: 'Jellyfin server URL', prefixIcon: Icon(Icons.dns_outlined))),
          const SizedBox(height: 14),
          TextField(controller: username, decoration: const InputDecoration(labelText: 'Username', prefixIcon: Icon(Icons.person_outline))),
          const SizedBox(height: 14),
          TextField(controller: password, obscureText: true, decoration: const InputDecoration(labelText: 'Password', prefixIcon: Icon(Icons.lock_outline))),
          const SizedBox(height: 22),
          FilledButton(onPressed: () => ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Authentication and library screens are not connected yet.'))), style: FilledButton.styleFrom(backgroundColor: const Color(afRedValue), padding: const EdgeInsets.symmetric(vertical: 16)), child: const Text('Sign In')),
        ],
      )),
    ))),
  );
}
