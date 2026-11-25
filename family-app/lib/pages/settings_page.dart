import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../util.dart';

/// 設定：綁定 LINE（手機沒開 App 也收得到警報）、登出。
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});
  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  String? _lineCode;
  bool _busy = false;

  Future<void> _makeLineCode() async {
    setState(() => _busy = true);
    try {
      final r = await functions.httpsCallable('createLineLinkCode').call();
      setState(() => _lineCode = (r.data as Map)['code'] as String?);
    } catch (e) {
      if (mounted) toast(context, '產生失敗：${errText(e)}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = FirebaseAuth.instance.currentUser;
    return Scaffold(
      appBar: AppBar(title: const Text('設定')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Text('帳號：${user?.email ?? ''}'),
        const SizedBox(height: 24),
        Text('用 LINE 接收警報', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        const Text('1. 在 LINE 加入「Neara」官方帳號好友\n2. 按下方按鈕產生綁定碼\n3. 把綁定碼傳給 Neara 官方帳號（10 分鐘內有效）'),
        const SizedBox(height: 12),
        if (_lineCode != null)
          ListTile(
            title: SelectableText(_lineCode!, style: const TextStyle(fontSize: 28, fontWeight: FontWeight.bold, letterSpacing: 2)),
            trailing: IconButton(icon: const Icon(Icons.copy), onPressed: () {
              Clipboard.setData(ClipboardData(text: _lineCode!));
              toast(context, '已複製');
            }),
          ),
        FilledButton(onPressed: _busy ? null : _makeLineCode, child: Text(_lineCode == null ? '產生 LINE 綁定碼' : '重新產生')),
        const SizedBox(height: 32),
        OutlinedButton(onPressed: () async {
          await FirebaseAuth.instance.signOut();
          if (context.mounted) Navigator.of(context).popUntil((r) => r.isFirst);
        }, child: const Text('登出')),
      ]),
    );
  }
}
