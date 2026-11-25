import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter/material.dart';

import '../util.dart';
import 'elder_page.dart';
import 'guardians_page.dart';
import 'incident_page.dart';
import 'smart_devices_page.dart';

/// 單一機器人：長者資料（可編輯，機器人一分鐘內同步）＋事件列表。
class DevicePage extends StatelessWidget {
  const DevicePage({super.key, required this.deviceId});
  final String deviceId;

  @override
  Widget build(BuildContext context) {
    final ref = FirebaseFirestore.instance.collection('devices').doc(deviceId);
    return StreamBuilder<DocumentSnapshot<Map<String, dynamic>>>(
      stream: ref.snapshots(),
      builder: (context, ds) {
        final d = ds.data?.data() ?? {};
        return Scaffold(
          appBar: AppBar(title: Text((d['elderName'] as String?)?.isNotEmpty == true ? d['elderName'] : '機器人'), actions: [
            IconButton(icon: const Icon(Icons.edit), tooltip: '編輯長輩資料',
                onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => ElderPage(deviceId: deviceId)))),
          ]),
          body: ListView(children: [
            Card(
              margin: const EdgeInsets.all(12),
              child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('長者資料（會印在事件報告上，給救護人員與醫師看）', style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 8),
                Text((d['profile'] as String?)?.isNotEmpty == true ? d['profile'] : '尚未填寫。建議填：年齡、慣用藥物、過敏、病史、緊急聯絡人。'),
                const SizedBox(height: 8),
                Text('機器人狀態：${d['state'] ?? '—'}｜底盤：${d['chassis'] ?? '—'}｜最後回報 ${ago(d['lastSeen'] as num?)}',
                    style: Theme.of(context).textTheme.bodySmall),
              ])),
            ),
            Card(
              margin: const EdgeInsets.symmetric(horizontal: 12),
              child: Column(children: [
                ListTile(
                  leading: const Icon(Icons.notifications_active_outlined, color: brandTeal),
                  title: const Text('通知與監護人'),
                  subtitle: Text(guardianOrder(d).map((u) => (d['memberNames'] ?? {})[u] ?? '家人').join(' → ') + ' ＋ 守護中心'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => GuardiansPage(deviceId: deviceId))),
                ),
                const Divider(height: 1),
                ListTile(
                  leading: const Icon(Icons.watch_outlined, color: brandTeal),
                  title: const Text('智慧裝置'),
                  subtitle: const Text('手錶心率與步數、機器人底盤'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => SmartDevicesPage(deviceId: deviceId))),
                ),
              ]),
            ),
            const Padding(padding: EdgeInsets.fromLTRB(16, 16, 16, 4), child: Text('事件紀錄', style: TextStyle(fontWeight: FontWeight.bold))),
            StreamBuilder<QuerySnapshot<Map<String, dynamic>>>(
              stream: ref.collection('incidents').orderBy('start', descending: true).limit(50).snapshots(),
              builder: (context, s) {
                if (!s.hasData) return const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()));
                if (s.data!.docs.isEmpty) return const Padding(padding: EdgeInsets.all(24), child: Text('目前沒有事件。'));
                return Column(children: [
                  for (final e in s.data!.docs)
                    Builder(builder: (context) {
                      final x = e.data();
                      final st = statusOf(x);
                      return ListTile(
                        leading: Icon(Icons.circle, color: st.$2, size: 14),
                        title: Text('${x['reasonLabel'] ?? x['reason']}'),
                        subtitle: Text('${fmtTime(x['start'])}｜${st.$1}'),
                        trailing: const Icon(Icons.chevron_right),
                        onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => IncidentPage(deviceId: deviceId, incidentId: e.id))),
                      );
                    }),
                ]);
              },
            ),
          ]),
        );
      },
    );
  }

}
