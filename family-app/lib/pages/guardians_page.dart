import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../util.dart';

/// 通知與監護人：
///  - 監護人順序：警報時守護中心＋第 1 位同時收到；3 分鐘沒人按「我來處理」就通知下一位
///  - 邀請其他家人、調整順序、移除（只有主要監護人＝第 1 位能調整別人）
///  - 我自己的通知偏好與電話（機器人用 SIM 卡自動撥號時會撥這支）
class GuardiansPage extends StatelessWidget {
  const GuardiansPage({super.key, required this.deviceId});
  final String deviceId;

  @override
  Widget build(BuildContext context) {
    final me = FirebaseAuth.instance.currentUser!.uid;
    final ref = FirebaseFirestore.instance.collection('devices').doc(deviceId);
    return Scaffold(
      appBar: AppBar(title: const Text('通知與監護人')),
      body: StreamBuilder<DocumentSnapshot<Map<String, dynamic>>>(
        stream: ref.snapshots(),
        builder: (context, s) {
          final d = s.data?.data();
          if (d == null) return const Center(child: CircularProgressIndicator());
          final order = guardianOrder(d);
          final names = Map<String, dynamic>.from(d['memberNames'] ?? {});
          final rel = Map<String, dynamic>.from(d['relations'] ?? {});
          final primary = order.isNotEmpty && order.first == me;
          return ListView(padding: const EdgeInsets.all(16), children: [
            Text('監護人（依通知順序）', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            const Text('警報時，守護中心和第 1 位會同時收到；3 分鐘內沒有人按「我來處理」，就通知下一位。',
                style: TextStyle(fontSize: 13, color: Colors.black54)),
            const SizedBox(height: 8),
            for (var i = 0; i < order.length; i++)
              Card(child: ListTile(
                leading: CircleAvatar(backgroundColor: i == 0 ? brandTeal : brandTealLight,
                    foregroundColor: i == 0 ? Colors.white : brandTeal, child: Text('${i + 1}')),
                title: Text('${names[order[i]] ?? '家人'}（${rel[order[i]] ?? '其他'}）${order[i] == me ? '・我' : ''}'),
                subtitle: Text(i == 0 ? '主要監護人' : '第 ${i + 1} 順位'),
                trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                  if (primary && i > 0)
                    IconButton(tooltip: '往前移', icon: const Icon(Icons.arrow_upward),
                        onPressed: () => _move(context, order, i, i - 1)),
                  if (primary && i < order.length - 1)
                    IconButton(tooltip: '往後移', icon: const Icon(Icons.arrow_downward),
                        onPressed: () => _move(context, order, i, i + 1)),
                  if ((primary && order[i] != me) || (order[i] == me && order.length > 1))
                    IconButton(tooltip: order[i] == me ? '退出' : '移除', icon: const Icon(Icons.person_remove_outlined),
                        onPressed: () => _remove(context, order[i], order[i] == me)),
                ]),
              )),
            const Card(child: ListTile(
              leading: Icon(Icons.support_agent, color: brandTeal),
              title: Text('Neara 守護中心'),
              subtitle: Text('24 小時值班，警報時與第 1 位同時收到'),
            )),
            const SizedBox(height: 8),
            OutlinedButton.icon(onPressed: () => _invite(context), icon: const Icon(Icons.person_add_alt), label: const Text('邀請家人加入')),
            const Divider(height: 40),
            _MyPrefs(uid: me),
          ]);
        },
      ),
    );
  }

  Future<void> _move(BuildContext context, List<String> order, int from, int to) async {
    final o = [...order];
    final x = o.removeAt(from);
    o.insert(to, x);
    try {
      await functions.httpsCallable('updateGuardians').call({'deviceId': deviceId, 'order': o});
    } catch (e) {
      if (context.mounted) toast(context, '調整失敗：${errText(e)}');
    }
  }

  Future<void> _remove(BuildContext context, String uid, bool self) async {
    final ok = await showDialog<bool>(context: context, builder: (c) => AlertDialog(
      title: Text(self ? '退出監護' : '移除這位家人'),
      content: Text(self ? '退出後你不會再收到這位長輩的任何通知。' : '移除後他不會再收到這位長輩的任何通知。'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
        FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(self ? '退出' : '移除')),
      ],
    ));
    if (ok != true || !context.mounted) return;
    try {
      await functions.httpsCallable('updateGuardians').call({'deviceId': deviceId, 'remove': uid});
      if (self && context.mounted) Navigator.of(context).popUntil((r) => r.isFirst);
    } catch (e) {
      if (context.mounted) toast(context, '失敗：${errText(e)}');
    }
  }

  Future<void> _invite(BuildContext context) async {
    try {
      final r = await functions.httpsCallable('createInviteCode').call({'deviceId': deviceId});
      final code = (r.data as Map)['code'] as String;
      if (!context.mounted) return;
      await showDialog<void>(context: context, builder: (c) => AlertDialog(
        title: const Text('邀請碼'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          SelectableText(code, style: const TextStyle(fontSize: 34, fontWeight: FontWeight.bold, letterSpacing: 6)),
          const SizedBox(height: 8),
          const Text('請家人安裝 Neara 家屬 App，在「新增長輩」輸入這組號碼。10 分鐘內有效，用過即失效。'),
        ]),
        actions: [
          TextButton(onPressed: () { Clipboard.setData(ClipboardData(text: code)); toast(c, '已複製'); }, child: const Text('複製')),
          FilledButton(onPressed: () => Navigator.pop(c), child: const Text('完成')),
        ],
      ));
    } catch (e) {
      if (context.mounted) toast(context, '產生失敗：${errText(e)}');
    }
  }
}

/// 我的通知偏好（套用在我所有的長輩）。緊急警報不能關。
class _MyPrefs extends StatefulWidget {
  const _MyPrefs({required this.uid});
  final String uid;
  @override
  State<_MyPrefs> createState() => _MyPrefsState();
}

class _MyPrefsState extends State<_MyPrefs> {
  static const labels = {
    'checking': '機器人正在確認狀況',
    'cancelled': '長輩表示沒事、已取消',
    'offline': '機器人離線超過 30 分鐘',
    'daily': '每日平安摘要（晚上 9 點）',
    'autoCall': '緊急時機器人自動打電話給我',
  };
  static const defaults = {'checking': true, 'cancelled': true, 'offline': true, 'daily': false, 'autoCall': true};

  Future<void> _set(Map<String, dynamic> patch) async {
    try {
      await functions.httpsCallable('updateMyProfile').call(patch);
    } catch (e) {
      if (mounted) toast(context, '儲存失敗：${errText(e)}');
    }
  }

  Future<void> _editText(String title, String field, String current, TextInputType type) async {
    final ctl = TextEditingController(text: current);
    final v = await showDialog<String>(context: context, builder: (c) => AlertDialog(
      title: Text(title),
      content: TextField(controller: ctl, keyboardType: type, autofocus: true),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c), child: const Text('取消')),
        FilledButton(onPressed: () => Navigator.pop(c, ctl.text.trim()), child: const Text('儲存')),
      ],
    ));
    if (v != null) await _set({field: v});
  }

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<DocumentSnapshot<Map<String, dynamic>>>(
      stream: FirebaseFirestore.instance.collection('users').doc(widget.uid).snapshots(),
      builder: (context, s) {
        final u = s.data?.data() ?? {};
        final prefs = {...defaults, ...Map<String, dynamic>.from(u['notifyPrefs'] ?? {})};
        final phone = (u['phone'] as String?) ?? '';
        return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('我的通知設定', style: Theme.of(context).textTheme.titleMedium),
          const Text('套用在你所有的長輩', style: TextStyle(fontSize: 13, color: Colors.black54)),
          const SizedBox(height: 8),
          ListTile(contentPadding: EdgeInsets.zero, title: const Text('我的稱呼'),
              subtitle: Text((u['displayName'] as String?)?.isNotEmpty == true ? u['displayName'] : '尚未設定（通知裡會顯示這個名字）'),
              trailing: const Icon(Icons.edit_outlined),
              onTap: () => _editText('我的稱呼（例如：大明）', 'displayName', u['displayName'] ?? '', TextInputType.name)),
          ListTile(contentPadding: EdgeInsets.zero, title: const Text('我的電話'),
              subtitle: Text(phone.isEmpty ? '尚未設定（機器人無法自動打給你）' : phone),
              trailing: const Icon(Icons.edit_outlined),
              onTap: () => _editText('我的電話', 'phone', phone, TextInputType.phone)),
          const SwitchListTile(contentPadding: EdgeInsets.zero, value: true, onChanged: null,
              title: Text('緊急警報（跌倒且沒有回應）'), subtitle: Text('無法關閉，勿擾模式也會響')),
          for (final k in labels.keys)
            SwitchListTile(contentPadding: EdgeInsets.zero, value: prefs[k] == true, title: Text(labels[k]!),
                subtitle: k == 'autoCall' && phone.isEmpty ? const Text('請先填電話') : null,
                onChanged: (v) => _set({'notifyPrefs': {k: v}})),
        ]);
      },
    );
  }
}
