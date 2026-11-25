import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import '../util.dart';

/// 「我的」：綁定 LINE（手機沒開 App 也收得到警報）、服務條款與隱私權政策、登出、刪除帳號。
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

  /// 刪除帳號：要輸入「刪除」兩個字確認，避免誤按。
  Future<void> _deleteAccount() async {
    final ctl = TextEditingController();
    final ok = await showDialog<bool>(context: context, builder: (c) => AlertDialog(
      title: const Text('刪除帳號'),
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('刪除後：\n• 你會從所有長輩的監護人名單移除，不再收到任何通知\n• 你的帳號、電話、LINE 綁定都會刪除，無法復原\n• 如果你是主要監護人，會由下一位家人遞補\n\n長輩的事件紀錄會保留給其他家人，90 天後自動刪除。'),
        const SizedBox(height: 12),
        TextField(controller: ctl, decoration: const InputDecoration(labelText: '請輸入「刪除」確認')),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
        FilledButton(style: FilledButton.styleFrom(backgroundColor: alertRed),
            onPressed: () => Navigator.pop(c, ctl.text.trim() == '刪除'), child: const Text('刪除帳號')),
      ],
    ));
    if (ok != true || !mounted) return;
    setState(() => _busy = true);
    try {
      await functions.httpsCallable('deleteMyAccount').call();
      await FirebaseAuth.instance.signOut();
      if (mounted) Navigator.of(context).popUntil((r) => r.isFirst);
    } catch (e) {
      if (mounted) toast(context, '刪除失敗：${errText(e)}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = FirebaseAuth.instance.currentUser;
    return Scaffold(
      appBar: AppBar(title: const Text('我的')),
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
        const SizedBox(height: 24),
        const Divider(),
        ListTile(contentPadding: EdgeInsets.zero, leading: const Icon(Icons.description_outlined), title: const Text('服務條款'),
            onTap: () => launchUrl(Uri.parse(termsUrl))),
        ListTile(contentPadding: EdgeInsets.zero, leading: const Icon(Icons.privacy_tip_outlined), title: const Text('隱私權政策'),
            onTap: () => launchUrl(Uri.parse(privacyUrl))),
        const Divider(),
        const SizedBox(height: 8),
        OutlinedButton(onPressed: () async {
          await FirebaseAuth.instance.signOut();
          if (context.mounted) Navigator.of(context).popUntil((r) => r.isFirst);
        }, child: const Text('登出')),
        const SizedBox(height: 8),
        TextButton(onPressed: _busy ? null : _deleteAccount,
            child: const Text('刪除帳號', style: TextStyle(color: alertRed))),
      ]),
    );
  }
}
