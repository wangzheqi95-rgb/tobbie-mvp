import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';

import 'firebase_options.dart'; // 由 `flutterfire configure` 產生（不進版控）
import 'notifications.dart';
import 'pages/home_page.dart';
import 'pages/login_page.dart';

/// Neara 家屬 App：收警報、看事件報告、管理長者資料、綁定機器人與 LINE。
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp(options: DefaultFirebaseOptions.currentPlatform);
  await Notifications.init();
  runApp(const NearaFamilyApp());
}

class NearaFamilyApp extends StatelessWidget {
  const NearaFamilyApp({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = ColorScheme.fromSeed(seedColor: const Color(0xFF1A73E8));
    return MaterialApp(
      title: 'Neara 家屬',
      theme: ThemeData(colorScheme: scheme, useMaterial3: true),
      darkTheme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF1A73E8), brightness: Brightness.dark), useMaterial3: true),
      navigatorKey: Notifications.navigatorKey,
      home: StreamBuilder<User?>(
        stream: FirebaseAuth.instance.authStateChanges(),
        builder: (context, snap) {
          if (snap.connectionState == ConnectionState.waiting) {
            return const Scaffold(body: Center(child: CircularProgressIndicator()));
          }
          if (snap.data == null) return const LoginPage();
          Notifications.registerToken(snap.data!.uid);
          return const HomePage();
        },
      ),
    );
  }
}
