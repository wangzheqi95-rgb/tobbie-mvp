import 'dart:convert';

import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_storage/firebase_storage.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../util.dart';
import 'video_call_page.dart';

/// 事件報告：摘要、事件照片、問答紀錄、骨架重播、長者資料；警報中可一鍵打 119 或結案。
class IncidentPage extends StatelessWidget {
  const IncidentPage({super.key, required this.deviceId, required this.incidentId});
  final String deviceId;
  final String incidentId;

  @override
  Widget build(BuildContext context) {
    final dref = FirebaseFirestore.instance.collection('devices').doc(deviceId);
    return StreamBuilder<DocumentSnapshot<Map<String, dynamic>>>(
      stream: dref.collection('incidents').doc(incidentId).snapshots(),
      builder: (context, s) {
        final x = s.data?.data();
        if (x == null) return Scaffold(appBar: AppBar(), body: const Center(child: CircularProgressIndicator()));
        final st = statusOf(x);
        final alert = x['outcome'] == 'ALERTED' && x['resolved'] == null;
        final lastWell = x['lastWell'] as num?;
        final gap = lastWell == null ? null : ((x['start'] as num) - lastWell) ~/ 1000;
        return Scaffold(
          appBar: AppBar(title: const Text('事件報告')),
          body: ListView(padding: const EdgeInsets.all(16), children: [
            Row(children: [
              Chip(label: Text(st.$1, style: const TextStyle(color: Colors.white)), backgroundColor: st.$2),
              const SizedBox(width: 8),
              Expanded(child: Text(x['elderName'] ?? '', style: Theme.of(context).textTheme.titleLarge)),
            ]),
            if (alert) ...[
              const SizedBox(height: 12),
              FilledButton.icon(
                style: FilledButton.styleFrom(backgroundColor: const Color(0xFFD93025), minimumSize: const Size.fromHeight(52)),
                onPressed: () => launchUrl(Uri.parse('tel:119')),
                icon: const Icon(Icons.call), label: const Text('撥打 119', style: TextStyle(fontSize: 18))),
              const SizedBox(height: 8),
              if (x['ackAt'] == null)
                FilledButton(
                  style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
                  onPressed: () => _ack(context),
                  child: const Text('我來處理（通知其他監護人）', style: TextStyle(fontSize: 17)))
              else
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(color: brandTealLight, borderRadius: BorderRadius.circular(12)),
                  child: Text('${x['ackByName'] ?? '家人'} 已接手處理（${ago(x['ackAt'] as num?)}）', style: const TextStyle(color: brandTeal, fontWeight: FontWeight.w600))),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(48)),
                onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => VideoCallPage(deviceId: deviceId, elderName: x['elderName'] ?? '長輩'))),
                icon: const Icon(Icons.videocam_outlined), label: const Text('視訊看現場（機器人會自動接通）')),
              const SizedBox(height: 8),
              OutlinedButton(onPressed: () => _resolve(context), child: const Text('我已處理，結案（機器人會停止警報）')),
              const SizedBox(height: 4),
              FutureBuilder<DocumentSnapshot<Map<String, dynamic>>>(
                future: dref.get(),
                builder: (c, d) => Text(_escalationText(x, d.data?.data() ?? {}), style: const TextStyle(fontSize: 13, color: Colors.black54)),
              ),
            ],
            _section(context, '事件摘要'),
            _row('事件時間', '${fmtTime(x['start'])}（${ago(x['start'] as num?)}）'),
            _row('偵測原因', '${x['reasonLabel'] ?? x['reason']}'),
            _row('最後正常活動', lastWell == null ? '—' : '${fmtTime(lastWell)}（事件前約 ${gap! < 60 ? '$gap 秒' : '${gap ~/ 60} 分鐘'}）'),
            _row('處理結果', '${x['outcomeLabel'] ?? x['outcome']}'),
            _row('結案', x['resolved'] == null ? '尚未結案' : '${fmtTime(x['resolved'])}｜${x['resolvedBy'] ?? ''}${x['resolveNote'] != null ? '\n${x['resolveNote']}' : ''}'),
            if (x['vitals'] != null) ...[
              _section(context, '生理數據（${x['vitals']['source'] ?? '手錶'}）'),
              _row('事件前心率', x['vitals']['hrBefore'] == null ? '—' : '${x['vitals']['hrBefore']} bpm'),
              _row('事件後心率', x['vitals']['hrAfter'] == null ? '（尚未收到）' : '${x['vitals']['hrAfter']} bpm（事件後 5 分鐘內最高）'),
            ],
            if (x['snapshotPath'] != null) ...[
              _section(context, '事件當下照片'),
              FutureBuilder<String>(
                future: FirebaseStorage.instance.ref(x['snapshotPath']).getDownloadURL(),
                builder: (c, u) => u.hasData ? ClipRRect(borderRadius: BorderRadius.circular(8), child: Image.network(u.data!)) : const SizedBox(height: 120, child: Center(child: CircularProgressIndicator())),
              ),
            ],
            _section(context, '機器人與長輩的問答'),
            for (final l in (x['dialog'] as List? ?? []))
              _row(TimeOfDay.fromDateTime(DateTime.fromMillisecondsSinceEpoch((l['t'] as num).toInt())).format(context) + '｜${l['who']}', '${l['text']}'),
            if (x['detailPath'] != null) ...[
              _section(context, '動作重播（骨架，事件前 60 秒～後 120 秒）'),
              SkeletonReplay(detailPath: x['detailPath'], start: (x['start'] as num).toInt()),
            ],
            _section(context, '長輩資料（給救護人員與醫師）'),
            FutureBuilder<DocumentSnapshot<Map<String, dynamic>>>(
              future: dref.get(),
              builder: (c, d) => Text((d.data?.data()?['profile'] as String?)?.isNotEmpty == true ? d.data!.data()!['profile'] : '（尚未填寫）'),
            ),
            const SizedBox(height: 24),
            const Text('本報告只呈現紀錄，不做醫療判斷。', style: TextStyle(fontSize: 12)),
          ]),
        );
      },
    );
  }

  Widget _section(BuildContext c, String t) =>
      Padding(padding: const EdgeInsets.only(top: 20, bottom: 6), child: Text(t, style: Theme.of(c).textTheme.titleMedium));
  Widget _row(String k, String v) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(width: 120, child: Text(k, style: const TextStyle(color: Colors.grey))),
        Expanded(child: Text(v)),
      ]));

  /// 「已通知 大明；3 分鐘後通知 小美」
  String _escalationText(Map<String, dynamic> x, Map<String, dynamic> d) {
    final names = Map<String, dynamic>.from(d['memberNames'] ?? {});
    final order = guardianOrder(d);
    final notified = List<String>.from(x['escNotified'] ?? const []).map((u) => names[u] ?? '家人').join('、');
    final level = (x['escLevel'] as num?)?.toInt() ?? 0;
    final next = x['ackAt'] == null && x['escNextAt'] != null && level + 1 < order.length ? names[order[level + 1]] ?? '下一位家人' : null;
    final parts = <String>[
      if (x['centerNotifiedAt'] != null) '守護中心已同步收到',
      if (notified.isNotEmpty) '已通知 $notified',
      if (next != null) '${until(x['escNextAt'] as num)}通知 $next',
    ];
    return parts.join('；');
  }

  Future<void> _ack(BuildContext context) async {
    try {
      await functions.httpsCallable('ackIncident').call({'deviceId': deviceId, 'incidentId': incidentId});
      if (context.mounted) toast(context, '已通知其他監護人由你處理，機器人也會告訴長輩');
    } catch (e) {
      if (context.mounted) toast(context, '失敗：${errText(e)}');
    }
  }

  Future<void> _resolve(BuildContext context) async {
    final note = TextEditingController();
    final ok = await showDialog<bool>(context: context, builder: (c) => AlertDialog(
      title: const Text('結案'),
      content: TextField(controller: note, minLines: 2, maxLines: 4, decoration: const InputDecoration(labelText: '處理經過（例如：已到場，長輩沒事）')),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
        FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('結案')),
      ],
    ));
    if (ok != true || !context.mounted) return;
    try {
      await functions.httpsCallable('resolveIncident').call({'deviceId': deviceId, 'incidentId': incidentId, 'note': note.text.trim()});
      if (context.mounted) toast(context, '已結案，機器人會在 10 秒內停止警報');
    } catch (e) {
      if (context.mounted) toast(context, '結案失敗：${errText(e)}');
    }
  }
}

/// 骨架重播：只有關節座標，沒有影像（保護隱私）。藍色＝事件前、紅色＝事件後。
class SkeletonReplay extends StatefulWidget {
  const SkeletonReplay({super.key, required this.detailPath, required this.start});
  final String detailPath;
  final int start;
  @override
  State<SkeletonReplay> createState() => _SkeletonReplayState();
}

class _SkeletonReplayState extends State<SkeletonReplay> {
  List<Map<String, dynamic>> _frames = [];
  double _w = 640, _h = 480;
  int _i = 0;
  bool _loading = true;
  bool _playing = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final bytes = await FirebaseStorage.instance.ref(widget.detailPath).getData(20 * 1024 * 1024);
      final j = jsonDecode(utf8.decode(bytes!)) as Map<String, dynamic>;
      _frames = [...(j['pre'] as List? ?? []), ...(j['post'] as List? ?? [])].cast<Map<String, dynamic>>();
      _w = ((j['frameW'] as num?) ?? 640).toDouble().clamp(1, 4000);
      _h = ((j['frameH'] as num?) ?? 480).toDouble().clamp(1, 4000);
      final z = _frames.indexWhere((f) => (f['t'] as num) >= widget.start);
      _i = (z < 0 ? 0 : z - 20).clamp(0, _frames.isEmpty ? 0 : _frames.length - 1);
    } catch (_) {}
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _play() async {
    setState(() => _playing = !_playing);
    while (_playing && mounted && _i < _frames.length - 1) {
      await Future.delayed(const Duration(milliseconds: 100));
      if (mounted && _playing) setState(() => _i++);
    }
    if (mounted) setState(() => _playing = false);
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const SizedBox(height: 120, child: Center(child: CircularProgressIndicator()));
    if (_frames.isEmpty) return const Text('（沒有骨架資料）');
    final f = _frames[_i];
    final rel = ((f['t'] as num) - widget.start) / 1000;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      AspectRatio(aspectRatio: _w / _h, child: ClipRRect(borderRadius: BorderRadius.circular(8),
          child: CustomPaint(painter: _SkeletonPainter(f['p'] as List?, rel < 0), child: Container(color: const Color(0xFF0B0D10))))),
      Row(children: [
        IconButton(icon: Icon(_playing ? Icons.pause : Icons.play_arrow), onPressed: _play),
        Expanded(child: Slider(value: _i.toDouble(), max: (_frames.length - 1).toDouble(), onChanged: (v) => setState(() => _i = v.round()))),
      ]),
      Text('${rel < 0 ? '事件前' : '事件後'} ${rel.abs().toStringAsFixed(1)} 秒｜${f['p'] == null ? '看不到人' : '軀幹 ${f['deg']}°'}'),
    ]);
  }
}

class _SkeletonPainter extends CustomPainter {
  _SkeletonPainter(this.p, this.before);
  final List? p;
  final bool before;
  static const conn = [[11, 12], [11, 13], [13, 15], [12, 14], [14, 16], [11, 23], [12, 24], [23, 24], [23, 25], [25, 27], [24, 26], [26, 28], [0, 11], [0, 12]];

  @override
  void paint(Canvas c, Size s) {
    final pts = p;
    if (pts == null || pts.length < 29) return;
    final paint = Paint()..color = before ? const Color(0xFF4F8CFF) : const Color(0xFFFF6B5B)..strokeWidth = 4;
    Offset at(int i) => Offset((pts[i][0] as num).toDouble() * s.width, (pts[i][1] as num).toDouble() * s.height);
    for (final e in conn) { c.drawLine(at(e[0]), at(e[1]), paint); }
  }

  @override
  bool shouldRepaint(covariant _SkeletonPainter old) => old.p != p || old.before != before;
}
