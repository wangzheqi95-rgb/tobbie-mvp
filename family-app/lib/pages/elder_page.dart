import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter/material.dart';

import '../util.dart';

/// 長輩資料（照設計稿）：稱呼、年齡、居住狀況、慣用藥物、過敏、病史。
/// 會印在事件報告上給救護人員與醫師參考；儲存後機器人一分鐘內同步。
class ElderPage extends StatefulWidget {
  const ElderPage({super.key, required this.deviceId});
  final String deviceId;
  @override
  State<ElderPage> createState() => _ElderPageState();
}

class _ElderPageState extends State<ElderPage> {
  final _name = TextEditingController();
  final _age = TextEditingController();
  final _living = TextEditingController();
  final _meds = TextEditingController();
  final _allergies = TextEditingController();
  final _history = TextEditingController();
  bool _loaded = false, _busy = false;

  @override
  void initState() {
    super.initState();
    FirebaseFirestore.instance.collection('devices').doc(widget.deviceId).get().then((s) {
      final d = s.data() ?? {};
      final e = Map<String, dynamic>.from(d['elder'] ?? {});
      _name.text = d['elderName'] ?? '';
      _age.text = e['age'] ?? '';
      _living.text = e['living'] ?? '';
      _meds.text = e['meds'] ?? '';
      _allergies.text = e['allergies'] ?? '';
      // 舊資料只有一段文字時，先放進「病史與注意事項」，不會遺失
      _history.text = e['history'] ?? (e.isEmpty ? (d['profile'] ?? '') : '');
      if (mounted) setState(() => _loaded = true);
    });
  }

  Future<void> _save() async {
    setState(() => _busy = true);
    try {
      await functions.httpsCallable('updateDeviceConfig').call({
        'deviceId': widget.deviceId,
        'elderName': _name.text.trim(),
        'elder': {'age': _age.text, 'living': _living.text, 'meds': _meds.text, 'allergies': _allergies.text, 'history': _history.text},
      });
      if (mounted) { toast(context, '已儲存，機器人一分鐘內會同步'); Navigator.pop(context); }
    } catch (e) {
      if (mounted) toast(context, '儲存失敗：${errText(e)}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _field(String label, TextEditingController c, {int lines = 1, TextInputType? type, String? hint}) => Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: TextField(controller: c, minLines: lines, maxLines: lines == 1 ? 1 : lines + 2, keyboardType: type,
          style: const TextStyle(fontSize: 17),
          decoration: InputDecoration(labelText: label, hintText: hint, filled: true, fillColor: brandCream,
              border: const OutlineInputBorder(borderRadius: BorderRadius.all(Radius.circular(12))))));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('長輩資料'), actions: [
        Padding(padding: const EdgeInsets.only(right: 12),
            child: FilledButton(onPressed: _busy || !_loaded ? null : _save, child: const Text('儲存'))),
      ]),
      body: !_loaded ? const Center(child: CircularProgressIndicator()) : ListView(padding: const EdgeInsets.all(16), children: [
        const Text('這些資料會印在事件報告上，給救護人員與醫師參考。儲存後機器人一分鐘內同步。',
            style: TextStyle(color: brandMuted, height: 1.6)),
        const SizedBox(height: 14),
        Container(padding: const EdgeInsets.all(16), decoration: cardDecoration, child: Column(children: [
          _field('機器人怎麼稱呼長輩', _name, hint: '例如：阿嬤'),
          Row(children: [
            Expanded(child: _field('年齡', _age, type: TextInputType.number)),
            const SizedBox(width: 10),
            Expanded(child: _field('居住狀況', _living, hint: '例如：獨居')),
          ]),
        ])),
        const SizedBox(height: 14),
        Container(padding: const EdgeInsets.all(16), decoration: cardDecoration, child: Column(children: [
          _field('慣用藥物', _meds, lines: 2),
          _field('過敏', _allergies),
          _field('病史與注意事項', _history, lines: 2, hint: '例如：曾中風、使用助行器'),
        ])),
      ]),
    );
  }
}
