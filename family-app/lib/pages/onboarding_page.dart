import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../notifications.dart';
import '../util.dart';
import 'elder_page.dart';
import 'guardians_page.dart';
import 'settings_page.dart';

/// 新手引導（照設計稿三步驟）：歡迎與權限 → 輸入配對碼 → 完成（LINE、邀請家人、長輩資料、測試警報）。
/// 也用在「新增長輩」：從第 2 步開始。
class OnboardingPage extends StatefulWidget {
  const OnboardingPage({super.key, required this.onDone, this.startAtCode = false});
  final VoidCallback onDone;
  final bool startAtCode;
  @override
  State<OnboardingPage> createState() => _OnboardingPageState();
}

class _OnboardingPageState extends State<OnboardingPage> {
  late int _step = widget.startAtCode ? 1 : 0;
  String? _deviceId;
  bool _primary = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 24, 24, 24),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [for (var i = 0; i < 3; i++) Expanded(child: Container(
            height: 4, margin: EdgeInsets.only(right: i < 2 ? 6 : 0),
            decoration: BoxDecoration(color: i <= _step ? brandTeal : brandLine, borderRadius: BorderRadius.circular(2))))]),
          const SizedBox(height: 32),
          Expanded(child: switch (_step) {
            0 => _Welcome(onNext: () async { await Notifications.requestPermission(); setState(() => _step = 1); }),
            1 => _PairCode(
                onBack: widget.startAtCode ? () => widget.onDone() : () => setState(() => _step = 0),
                onPaired: (id, primary) => setState(() { _deviceId = id; _primary = primary; _step = 2; })),
            _ => _Done(deviceId: _deviceId!, primary: _primary, onDone: widget.onDone),
          }),
        ]),
      )),
    );
  }
}

class _Welcome extends StatelessWidget {
  const _Welcome({required this.onNext});
  final Future<void> Function() onNext;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Container(width: 112, height: 112, decoration: BoxDecoration(color: robotBg, borderRadius: BorderRadius.circular(34)),
          child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            for (var i = 0; i < 2; i++) Container(width: 20, height: 28, margin: const EdgeInsets.symmetric(horizontal: 8),
                decoration: BoxDecoration(color: robotEye, borderRadius: BorderRadius.circular(10))),
          ])),
      const SizedBox(height: 24),
      Text('歡迎使用 Neara', style: Theme.of(context).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.bold)),
      const SizedBox(height: 10),
      const Text('長輩跌倒時，Neara 會先關心他，確認需要幫忙才通知您。我們需要以下權限：',
          style: TextStyle(fontSize: 17, height: 1.6, color: brandMuted)),
      const SizedBox(height: 20),
      const _PermTile(icon: Icons.notifications_active_outlined, title: '通知（含緊急警報）', sub: '勿擾模式時也能響鈴'),
      const SizedBox(height: 12),
      const _PermTile(icon: Icons.call_outlined, title: '電話', sub: '一鍵撥 119 或長輩家'),
      const Spacer(),
      FilledButton(style: bigButton, onPressed: onNext, child: const Text('同意並繼續')),
      const SizedBox(height: 10),
      Wrap(alignment: WrapAlignment.center, children: [
        const Text('繼續即表示同意 ', style: TextStyle(fontSize: 13, color: brandMuted)),
        InkWell(onTap: () => launchUrl(Uri.parse(termsUrl)), child: const Text('服務條款', style: TextStyle(fontSize: 13, color: brandTeal, decoration: TextDecoration.underline))),
        const Text(' 與 ', style: TextStyle(fontSize: 13, color: brandMuted)),
        InkWell(onTap: () => launchUrl(Uri.parse(privacyUrl)), child: const Text('隱私權政策', style: TextStyle(fontSize: 13, color: brandTeal, decoration: TextDecoration.underline))),
      ]),
    ]);
  }
}

class _PermTile extends StatelessWidget {
  const _PermTile({required this.icon, required this.title, required this.sub});
  final IconData icon;
  final String title, sub;
  @override
  Widget build(BuildContext context) => Container(
      padding: const EdgeInsets.all(16),
      decoration: cardDecoration,
      child: Row(children: [
        Icon(icon, color: brandTeal),
        const SizedBox(width: 12),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500)),
          Text(sub, style: const TextStyle(fontSize: 13, color: brandMuted)),
        ])),
      ]));
}

class _PairCode extends StatefulWidget {
  const _PairCode({required this.onBack, required this.onPaired});
  final VoidCallback onBack;
  final void Function(String deviceId, bool primary) onPaired;
  @override
  State<_PairCode> createState() => _PairCodeState();
}

class _PairCodeState extends State<_PairCode> {
  static const relations = ['兒子', '女兒', '配偶', '孫子女', '照顧者', '其他'];
  final _code = TextEditingController();
  String _relation = relations.first;
  bool _busy = false;

  Future<void> _pair() async {
    final code = _code.text.trim();
    if (!RegExp(r'^\d{6}$').hasMatch(code)) { toast(context, '配對碼是 6 位數字'); return; }
    setState(() => _busy = true);
    try {
      final r = await functions.httpsCallable('bindDevice').call({'code': code, 'relation': _relation});
      final m = r.data as Map;
      widget.onPaired(m['deviceId'] as String, m['primary'] == true);
    } catch (e) {
      if (mounted) toast(context, '配對失敗：${errText(e)}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text('輸入機器人上的配對碼', style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold)),
      const SizedBox(height: 10),
      const Text('在機器人上打開「設定 → 產生家屬配對碼」，或請家人在「通知與監護人」產生邀請碼。6 位數字，10 分鐘內有效。',
          style: TextStyle(fontSize: 16, height: 1.6, color: brandMuted)),
      const SizedBox(height: 24),
      TextField(
        controller: _code, keyboardType: TextInputType.number, maxLength: 6, textAlign: TextAlign.center, autofocus: true,
        style: const TextStyle(fontSize: 34, fontWeight: FontWeight.bold, letterSpacing: 14),
        decoration: const InputDecoration(labelText: '配對碼', counterText: '', filled: true, fillColor: Colors.white,
            border: OutlineInputBorder(borderRadius: BorderRadius.all(Radius.circular(14)))),
      ),
      const SizedBox(height: 16),
      DropdownButtonFormField<String>(
        value: _relation,
        decoration: const InputDecoration(labelText: '您是長輩的', filled: true, fillColor: Colors.white,
            border: OutlineInputBorder(borderRadius: BorderRadius.all(Radius.circular(14)))),
        items: [for (final r in relations) DropdownMenuItem(value: r, child: Text(r))],
        onChanged: (v) => setState(() => _relation = v ?? _relation),
      ),
      const SizedBox(height: 20),
      Container(padding: const EdgeInsets.all(14), decoration: BoxDecoration(color: brandTealLight, borderRadius: BorderRadius.circular(14)),
          child: const Text('第一位配對的人會成為「主要監護人」，可以再邀請其他家人。', style: TextStyle(color: brandTeal, height: 1.6))),
      const Spacer(),
      FilledButton(style: bigButton, onPressed: _busy ? null : _pair, child: Text(_busy ? '配對中…' : '配對')),
      TextButton(onPressed: widget.onBack, child: const Text('上一步')),
    ]);
  }
}

class _Done extends StatefulWidget {
  const _Done({required this.deviceId, required this.primary, required this.onDone});
  final String deviceId;
  final bool primary;
  final VoidCallback onDone;
  @override
  State<_Done> createState() => _DoneState();
}

class _DoneState extends State<_Done> {
  bool _testing = false;

  Future<void> _test() async {
    setState(() => _testing = true);
    try {
      final r = await functions.httpsCallable('sendTestAlert').call({'deviceId': widget.deviceId});
      final m = r.data as Map;
      if (mounted) {
        toast(context, (m['tokens'] ?? 0) == 0 ? '這支手機還沒有推播權限，請到系統設定開啟 Neara 的通知' : '已送出測試警報，幾秒內手機會響');
      }
    } catch (e) {
      if (mounted) toast(context, '送出失敗：${errText(e)}');
    } finally {
      if (mounted) setState(() => _testing = false);
    }
  }

  void _push(Widget page) => Navigator.push(context, MaterialPageRoute(builder: (_) => page));

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Align(alignment: Alignment.centerLeft, child: Container(width: 72, height: 72,
          decoration: const BoxDecoration(color: brandTealLight, shape: BoxShape.circle),
          child: const Icon(Icons.check, color: brandTeal, size: 38))),
      const SizedBox(height: 20),
      Text('配對完成', style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold)),
      const SizedBox(height: 10),
      Text(widget.primary ? '您已成為主要監護人。再做幾件事，通知會更可靠：' : '您已加入監護人名單。再做幾件事，通知會更可靠：',
          style: const TextStyle(fontSize: 16, height: 1.6, color: brandMuted)),
      const SizedBox(height: 20),
      _ActionTile(title: '綁定 LINE', sub: '手機沒開 App 也收得到警報', label: '綁定', color: const Color(0xFF06C755),
          onTap: () => _push(const SettingsPage())),
      const SizedBox(height: 12),
      _ActionTile(title: '邀請其他家人', sub: '您沒接到時，會依序通知他們', label: '邀請',
          onTap: () => _push(GuardiansPage(deviceId: widget.deviceId))),
      const SizedBox(height: 12),
      _ActionTile(title: '填寫長輩資料', sub: '用藥、過敏，會給救護人員看', label: '填寫',
          onTap: () => _push(ElderPage(deviceId: widget.deviceId))),
      const Spacer(),
      FilledButton(style: bigButton, onPressed: widget.onDone, child: const Text('進入首頁')),
      TextButton(onPressed: _testing ? null : _test, child: Text(_testing ? '送出中…' : '傳送測試警報，確認手機會響')),
    ]);
  }
}

class _ActionTile extends StatelessWidget {
  const _ActionTile({required this.title, required this.sub, required this.label, required this.onTap, this.color});
  final String title, sub, label;
  final VoidCallback onTap;
  final Color? color;
  @override
  Widget build(BuildContext context) => Container(
      padding: const EdgeInsets.all(16),
      decoration: cardDecoration,
      child: Row(children: [
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500)),
          Text(sub, style: const TextStyle(fontSize: 13, color: brandMuted)),
        ])),
        color != null
            ? FilledButton(style: FilledButton.styleFrom(backgroundColor: color), onPressed: onTap, child: Text(label))
            : OutlinedButton(onPressed: onTap, child: Text(label)),
      ]));
}
