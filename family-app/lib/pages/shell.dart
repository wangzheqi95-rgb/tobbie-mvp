import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';

import '../notifications.dart';
import '../util.dart';
import 'guardians_page.dart';
import 'home_page.dart';
import 'onboarding_page.dart';
import 'settings_page.dart';
import 'smart_devices_page.dart';

/// 登入後的入口：
///  - 還沒有任何長輩 → 新手引導（三步驟）
///  - 有長輩 → 底部選單：長輩／通知／裝置／我的（照設計稿）
class Shell extends StatefulWidget {
  const Shell({super.key});
  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  int _tab = 0;
  bool? _onboarding; // 第一次讀到資料時決定；引導進行中就算綁好了也先走完第 3 步

  @override
  Widget build(BuildContext context) {
    final uid = FirebaseAuth.instance.currentUser!.uid;
    return StreamBuilder<QuerySnapshot<Map<String, dynamic>>>(
      stream: FirebaseFirestore.instance.collection('devices').where('members', arrayContains: uid).snapshots(),
      builder: (context, snap) {
        if (snap.hasError) return Scaffold(body: Center(child: Text('讀取失敗：${snap.error}')));
        if (!snap.hasData) return const Scaffold(body: Center(child: CircularProgressIndicator()));
        final docs = snap.data!.docs;
        if (_onboarding == null) {
          _onboarding = docs.isEmpty;
          if (!_onboarding!) Notifications.requestPermission(); // 舊使用者：確認有通知權限
        }
        if (_onboarding! || docs.isEmpty) {
          return OnboardingPage(onDone: () => setState(() => _onboarding = false));
        }
        final pages = [
          HomePage(devices: docs),
          _PerDevice(docs: docs, title: '通知與監護人', pageFor: (id) => GuardiansPage(deviceId: id)),
          _PerDevice(docs: docs, title: '智慧裝置', pageFor: (id) => SmartDevicesPage(deviceId: id)),
          const SettingsPage(),
        ];
        return Scaffold(
          body: IndexedStack(index: _tab, children: pages),
          bottomNavigationBar: NavigationBar(
            selectedIndex: _tab,
            onDestinationSelected: (i) => setState(() => _tab = i),
            backgroundColor: Colors.white,
            indicatorColor: brandTealLight,
            destinations: const [
              NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home, color: brandTeal), label: '長輩'),
              NavigationDestination(icon: Icon(Icons.notifications_none), selectedIcon: Icon(Icons.notifications, color: brandTeal), label: '通知'),
              NavigationDestination(icon: Icon(Icons.watch_outlined), selectedIcon: Icon(Icons.watch, color: brandTeal), label: '裝置'),
              NavigationDestination(icon: Icon(Icons.person_outline), selectedIcon: Icon(Icons.person, color: brandTeal), label: '我的'),
            ],
          ),
        );
      },
    );
  }
}

/// 「通知」「裝置」分頁：只有一位長輩就直接顯示；多位就先選長輩。
class _PerDevice extends StatelessWidget {
  const _PerDevice({required this.docs, required this.title, required this.pageFor});
  final List<QueryDocumentSnapshot<Map<String, dynamic>>> docs;
  final String title;
  final Widget Function(String deviceId) pageFor;

  @override
  Widget build(BuildContext context) {
    if (docs.length == 1) return pageFor(docs.first.id);
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        for (final d in docs)
          Card(child: ListTile(
            title: Text((d.data()['elderName'] as String?)?.isNotEmpty == true ? d.data()['elderName'] : '（尚未設定稱呼）'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => pageFor(d.id))),
          )),
      ]),
    );
  }
}
