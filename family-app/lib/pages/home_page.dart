import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';

import '../util.dart';
import 'device_page.dart';
import 'elder_page.dart';
import 'incident_page.dart';
import 'onboarding_page.dart';
import 'video_call_page.dart';

/// 首頁「我的長輩」（照設計稿）：每位長輩一張卡（平安／警報／離線、步數、心率、上次聊天）＋最近事件。
class HomePage extends StatelessWidget {
  const HomePage({super.key, required this.devices});
  final List<QueryDocumentSnapshot<Map<String, dynamic>>> devices;

  String _greeting() {
    final h = DateTime.now().hour;
    return h < 11 ? '早安' : h < 18 ? '午安' : '晚安';
  }

  @override
  Widget build(BuildContext context) {
    final user = FirebaseAuth.instance.currentUser;
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (c) =>
            OnboardingPage(startAtCode: true, onDone: () => Navigator.pop(c)))),
        icon: const Icon(Icons.add), label: const Text('新增長輩')),
      body: SafeArea(child: ListView(padding: const EdgeInsets.fromLTRB(16, 16, 16, 96), children: [
        StreamBuilder<DocumentSnapshot<Map<String, dynamic>>>(
          stream: FirebaseFirestore.instance.collection('users').doc(user!.uid).snapshots(),
          builder: (c, s) => Text('${_greeting()}，${s.data?.data()?['displayName'] ?? ''}',
              style: const TextStyle(fontSize: 15, color: brandMuted)),
        ),
        const Text('我的長輩', style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold)),
        const SizedBox(height: 14),
        for (final d in devices) ...[ElderCard(id: d.id, data: d.data()), const SizedBox(height: 14)],
      ])),
    );
  }
}

class ElderCard extends StatelessWidget {
  const ElderCard({super.key, required this.id, required this.data});
  final String id;
  final Map<String, dynamic> data;

  @override
  Widget build(BuildContext context) {
    final ref = FirebaseFirestore.instance.collection('devices').doc(id);
    final name = (data['elderName'] as String?)?.isNotEmpty == true ? data['elderName'] as String : '（尚未設定稱呼）';
    final online = isOnline(data);
    return StreamBuilder<QuerySnapshot<Map<String, dynamic>>>(
      stream: ref.collection('incidents').orderBy('start', descending: true).limit(2).snapshots(),
      builder: (context, s) {
        final recent = s.data?.docs ?? [];
        final alerts = recent.where((e) => e.data()['outcome'] == 'ALERTED' && e.data()['resolved'] == null).toList();
        final openAlert = alerts.isEmpty ? null : alerts.first;
        final (pill, pillFg, pillBg) = openAlert != null
            ? ('警報中', Colors.white, alertRed)
            : online ? ('平安', brandTeal, brandTealLight) : ('離線', amber, amberLight);
        return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          if (openAlert != null)
            Padding(padding: const EdgeInsets.only(bottom: 10), child: Material(
              color: alertRed, borderRadius: BorderRadius.circular(16),
              child: InkWell(borderRadius: BorderRadius.circular(16),
                onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => IncidentPage(deviceId: id, incidentId: openAlert.id))),
                child: Padding(padding: const EdgeInsets.all(16), child: Row(children: [
                  const Icon(Icons.warning_amber_rounded, color: Colors.white, size: 28),
                  const SizedBox(width: 12),
                  Expanded(child: Text('$name可能跌倒且沒有回應\n${openAlert.data()['ackByName'] != null ? '${openAlert.data()['ackByName']} 已接手' : '點這裡處理'}',
                      style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold, height: 1.4))),
                  const Icon(Icons.chevron_right, color: Colors.white),
                ]))),
            )),
          Container(
            padding: const EdgeInsets.all(18),
            decoration: cardDecoration.copyWith(borderRadius: BorderRadius.circular(20)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              InkWell(
                onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => DevicePage(deviceId: id))),
                child: Row(children: [
                  CircleAvatar(radius: 28, backgroundColor: brandTealLight,
                      child: Text(name.characters.last, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: brandTeal))),
                  const SizedBox(width: 14),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(name, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
                    Text(online ? '機器人在線，${ago(data['lastSeen'] as num?)}回報' : '機器人離線 ${ago(data['lastSeen'] as num?)}，請確認電源與網路',
                        style: const TextStyle(fontSize: 14, color: brandMuted)),
                  ])),
                  Container(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                      decoration: BoxDecoration(color: pillBg, borderRadius: BorderRadius.circular(999)),
                      child: Text(pill, style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold, color: pillFg))),
                ]),
              ),
              const SizedBox(height: 14),
              StreamBuilder<DocumentSnapshot<Map<String, dynamic>>>(
                stream: ref.collection('health').doc('latest').snapshots(),
                builder: (context, h) {
                  final x = h.data?.data();
                  final hr = (x?['hr'] as List?) ?? const [];
                  final last = hr.isEmpty ? null : hr.last as Map;
                  // 步數只算「今天」的（後端記錄 stepsDay＝台灣時間今天 0 點）
                  final now = DateTime.now();
                  final midnight = DateTime(now.year, now.month, now.day).millisecondsSinceEpoch;
                  final stepsToday = x != null && x['stepsDay'] == midnight ? x['stepsToday'] : null;
                  return Row(children: [
                    Expanded(child: _Stat('今天活動', stepsToday == null ? '—' : '$stepsToday 步')),
                    const SizedBox(width: 8),
                    Expanded(child: _Stat('心率', last == null ? '—' : '${last['bpm']} bpm')),
                    const SizedBox(width: 8),
                    Expanded(child: _Stat('上次聊天', data['lastTalkAt'] == null ? '—' : ago(data['lastTalkAt'] as num?))),
                  ]);
                },
              ),
              const SizedBox(height: 14),
              Row(children: [
                Expanded(child: FilledButton(
                  style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14))),
                  onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => ElderPage(deviceId: id))),
                  child: const Text('長輩資料'))),
                const SizedBox(width: 8),
                Expanded(child: OutlinedButton(
                  style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14))),
                  onPressed: online
                      ? () => Navigator.push(context, MaterialPageRoute(builder: (_) => VideoCallPage(deviceId: id, elderName: name)))
                      : () => toast(context, '機器人離線，無法視訊'),
                  child: const Text('視訊通話'))),
              ]),
            ]),
          ),
          if (recent.isNotEmpty) ...[
            const Padding(padding: EdgeInsets.fromLTRB(4, 16, 4, 8), child: Text('最近事件', style: TextStyle(fontSize: 17, fontWeight: FontWeight.bold))),
            for (final e in recent) _EventTile(deviceId: id, id: e.id, x: e.data()),
          ],
        ]);
      },
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat(this.label, this.value);
  final String label, value;
  @override
  Widget build(BuildContext context) => Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: brandCream, borderRadius: BorderRadius.circular(14)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: const TextStyle(fontSize: 12, color: brandMuted)),
        const SizedBox(height: 4),
        FittedBox(fit: BoxFit.scaleDown, child: Text(value, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold))),
      ]));
}

class _EventTile extends StatelessWidget {
  const _EventTile({required this.deviceId, required this.id, required this.x});
  final String deviceId, id;
  final Map<String, dynamic> x;
  @override
  Widget build(BuildContext context) {
    final st = statusOf(x);
    return Padding(padding: const EdgeInsets.only(bottom: 8), child: Material(
      color: Colors.white, shape: RoundedRectangleBorder(side: const BorderSide(color: brandLine), borderRadius: BorderRadius.circular(16)),
      child: ListTile(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        leading: Icon(Icons.circle, size: 12, color: st.$2),
        title: Text(st.$1, style: const TextStyle(fontWeight: FontWeight.w500)),
        subtitle: Text('${fmtTime(x['start'])}｜${x['reasonLabel'] ?? x['reason']}'),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => IncidentPage(deviceId: deviceId, incidentId: id))),
      ),
    ));
  }
}
