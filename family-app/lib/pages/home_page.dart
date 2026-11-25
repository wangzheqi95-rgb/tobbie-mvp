import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';

import '../util.dart';
import 'device_page.dart';
import 'settings_page.dart';

/// 首頁：我綁定的機器人（在線狀態、最新事件）＋新增機器人。
class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    final uid = FirebaseAuth.instance.currentUser!.uid;
    return Scaffold(
      appBar: AppBar(title: const Text('我的長輩'), actions: [
        IconButton(icon: const Icon(Icons.settings), onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const SettingsPage()))),
      ]),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _bind(context), icon: const Icon(Icons.add), label: const Text('新增機器人')),
      body: StreamBuilder<QuerySnapshot<Map<String, dynamic>>>(
        stream: FirebaseFirestore.instance.collection('devices').where('members', arrayContains: uid).snapshots(),
        builder: (context, snap) {
          if (snap.hasError) return Center(child: Text('讀取失敗：${snap.error}'));
          if (!snap.hasData) return const Center(child: CircularProgressIndicator());
          final docs = snap.data!.docs;
          if (docs.isEmpty) {
            return const Center(child: Padding(padding: EdgeInsets.all(32),
              child: Text('還沒有綁定機器人。\n請按右下角「新增機器人」，輸入機器人畫面上的 6 位數配對碼。', textAlign: TextAlign.center)));
          }
          return ListView(children: [for (final d in docs) _DeviceTile(id: d.id, data: d.data())]);
        },
      ),
    );
  }

  Future<void> _bind(BuildContext context) async {
    final ctl = TextEditingController();
    final code = await showDialog<String>(context: context, builder: (c) => AlertDialog(
      title: const Text('新增機器人'),
      content: TextField(controller: ctl, keyboardType: TextInputType.number, maxLength: 6,
          decoration: const InputDecoration(labelText: '機器人畫面上的 6 位數配對碼')),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c), child: const Text('取消')),
        FilledButton(onPressed: () => Navigator.pop(c, ctl.text.trim()), child: const Text('綁定')),
      ],
    ));
    if (code == null || code.isEmpty || !context.mounted) return;
    try {
      await functions.httpsCallable('bindDevice').call({'code': code});
      if (context.mounted) toast(context, '綁定成功！');
    } catch (e) {
      if (context.mounted) toast(context, '綁定失敗：${errText(e)}');
    }
  }
}

class _DeviceTile extends StatelessWidget {
  const _DeviceTile({required this.id, required this.data});
  final String id;
  final Map<String, dynamic> data;

  @override
  Widget build(BuildContext context) {
    final last = data['lastSeen'] as num?;
    final online = last != null && DateTime.now().millisecondsSinceEpoch - last < 3 * 60 * 1000;
    final name = (data['elderName'] as String?)?.isNotEmpty == true ? data['elderName'] : '（尚未設定稱呼）';
    return StreamBuilder<QuerySnapshot<Map<String, dynamic>>>(
      stream: FirebaseFirestore.instance.collection('devices').doc(id).collection('incidents').orderBy('start', descending: true).limit(1).snapshots(),
      builder: (context, s) {
        final latest = s.data?.docs.isNotEmpty == true ? s.data!.docs.first.data() : null;
        final st = latest == null ? null : statusOf(latest);
        return Card(
          margin: const EdgeInsets.fromLTRB(12, 8, 12, 0),
          child: ListTile(
            leading: Icon(Icons.smart_toy, color: online ? Colors.green : Colors.grey, size: 36),
            title: Text(name, style: const TextStyle(fontWeight: FontWeight.bold)),
            subtitle: Text('${online ? '在線' : '離線'}｜最後回報 ${ago(last)}'
                '${latest != null ? '\n最近事件：${fmtTime(latest['start'])}' : ''}'),
            isThreeLine: latest != null,
            trailing: st == null ? null : Chip(label: Text(st.$1, style: const TextStyle(color: Colors.white)), backgroundColor: st.$2),
            onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => DevicePage(deviceId: id))),
          ),
        );
      },
    );
  }
}
