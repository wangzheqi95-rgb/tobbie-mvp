import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter/material.dart';

import '../util.dart';

/// 智慧裝置：目前串接的是長輩的手錶（透過機器人手機上的 Health Connect 讀心率與步數），
/// 以及機器人自己的底盤。其他裝置先列為「之後支援」。
class SmartDevicesPage extends StatelessWidget {
  const SmartDevicesPage({super.key, required this.deviceId});
  final String deviceId;

  static const _hcStatus = {
    'OK': ('已連線', Color(0xFF2E7D4F)),
    'NO_PERMISSION': ('尚未授權：請在機器人「設定 → 授權讀取手錶資料」', Color(0xFF8A5A00)),
    'NOT_INSTALLED': ('機器人手機沒有 Health Connect', Color(0xFF8A5A00)),
    'OFF': ('未開啟：請在機器人「設定 → 串接手錶」打勾', Colors.grey),
  };

  @override
  Widget build(BuildContext context) {
    final ref = FirebaseFirestore.instance.collection('devices').doc(deviceId);
    return Scaffold(
      appBar: AppBar(title: const Text('智慧裝置')),
      body: StreamBuilder<DocumentSnapshot<Map<String, dynamic>>>(
        stream: ref.snapshots(),
        builder: (context, s) {
          final d = s.data?.data() ?? {};
          final hc = Map<String, dynamic>.from((d['integrations'] ?? {})['healthConnect'] ?? {});
          final st = _hcStatus[hc['status']] ?? _hcStatus['OFF']!;
          return ListView(padding: const EdgeInsets.all(16), children: [
            const Text('串接長輩已有的裝置，數據會附在事件報告上。', style: TextStyle(color: Colors.black54)),
            const SizedBox(height: 12),
            Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Row(children: [
                Icon(Icons.watch_outlined, color: brandTeal, size: 28),
                SizedBox(width: 12),
                Expanded(child: Text('智慧手錶（Health Connect）', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600))),
              ]),
              const SizedBox(height: 6),
              Text(st.$1, style: TextStyle(color: st.$2)),
              if (hc['lastSync'] != null) Text('上次同步：${ago(hc['lastSync'] as num?)}', style: const TextStyle(fontSize: 13, color: Colors.black54)),
              const SizedBox(height: 10),
              StreamBuilder<DocumentSnapshot<Map<String, dynamic>>>(
                stream: ref.collection('health').doc('latest').snapshots(),
                builder: (context, h) {
                  final x = h.data?.data();
                  final hr = (x?['hr'] as List?) ?? [];
                  final last = hr.isEmpty ? null : hr.last as Map;
                  return Row(children: [
                    Expanded(child: _stat('最新心率', last == null ? '—' : '${last['bpm']} bpm', last == null ? null : ago(last['t'] as num?))),
                    const SizedBox(width: 10),
                    Expanded(child: _stat('今天步數', x?['stepsToday'] == null ? '—' : '${x!['stepsToday']} 步', null)),
                  ]);
                },
              ),
              const SizedBox(height: 8),
              const Text('設定方式：手錶配對在機器人那支手機上，在手錶的 App 開啟「同步到 Health Connect」，'
                  '再到機器人「設定 → 智慧裝置」打勾並授權。只讀心率與步數。',
                  style: TextStyle(fontSize: 13, color: Colors.black54)),
            ]))),
            Card(child: ListTile(
              leading: const Icon(Icons.smart_toy_outlined, color: brandTeal, size: 28),
              title: const Text('機器人底盤'),
              subtitle: Text((d['chassis'] as String?)?.isNotEmpty == true ? '${d['chassis']}｜狀態：${d['state'] ?? '—'}' : '沒有底盤（只用手機）'),
            )),
            const SizedBox(height: 16),
            Text('之後支援', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            const Wrap(spacing: 8, runSpacing: 8, children: [
              Chip(label: Text('Apple 健康')), Chip(label: Text('Garmin')), Chip(label: Text('藍牙血壓計')),
              Chip(label: Text('浴室／臥室跌倒雷達')), Chip(label: Text('緊急按鈕')), Chip(label: Text('門窗感應器')),
            ]),
          ]);
        },
      ),
    );
  }

  Widget _stat(String label, String value, String? sub) => Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: brandCream, borderRadius: BorderRadius.circular(12)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: const TextStyle(fontSize: 12, color: Colors.black54)),
        Text(value, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
        if (sub != null) Text(sub, style: const TextStyle(fontSize: 12, color: Colors.black54)),
      ]));
}
