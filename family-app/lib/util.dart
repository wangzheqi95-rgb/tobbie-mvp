import 'package:cloud_functions/cloud_functions.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

/// 設計稿的顏色（沉穩青綠＋溫暖米白）。
const brandTeal = Color(0xFF1F6F66);
const brandTealLight = Color(0xFFE3F1EE);
const brandCream = Color(0xFFFBF8F3);
const alertRed = Color(0xFFB3261E);

/// 監護人通知順序（與後端 guardianOrder 相同規則）。
List<String> guardianOrder(Map<String, dynamic> d) {
  final members = List<String>.from(d['members'] ?? const []);
  final order = List<String>.from(d['guardianOrder'] ?? const []).where(members.contains).toList();
  return {...order, ...members}.toList();
}

/// 後端 Cloud Functions（台灣區域）。
final functions = FirebaseFunctions.instanceFor(region: 'asia-east1');

String fmtTime(num? ms) => ms == null ? '—' : DateFormat('yyyy/MM/dd HH:mm:ss').format(DateTime.fromMillisecondsSinceEpoch(ms.toInt()));

String ago(num? ms) {
  if (ms == null) return '—';
  final s = DateTime.now().difference(DateTime.fromMillisecondsSinceEpoch(ms.toInt())).inSeconds;
  if (s < 60) return '$s 秒前';
  if (s < 3600) return '${s ~/ 60} 分鐘前';
  if (s < 86400) return '${s ~/ 3600} 小時前';
  return '${s ~/ 86400} 天前';
}

/// 距離未來某個時間還有多久：「2 分鐘後」。
String until(num ms) {
  final s = ((ms - DateTime.now().millisecondsSinceEpoch) / 1000).round();
  if (s <= 0) return '即將';
  if (s < 60) return '$s 秒後';
  return '${(s / 60).ceil()} 分鐘後';
}

/// 事件狀態 → 標籤文字與顏色。
(String, Color) statusOf(Map<String, dynamic> x) {
  if (x['outcome'] == 'ALERTED' && x['resolved'] == null) return ('警報中', const Color(0xFFD93025));
  if (x['resolved'] != null) return ('已結案', Colors.grey);
  if (x['outcome'] == 'CANCELLED') return ('長者表示沒事', const Color(0xFF188038));
  return ('確認中', const Color(0xFFB06000));
}

void toast(BuildContext context, String msg) =>
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

/// 把後端錯誤轉成看得懂的中文。
String errText(Object e) => e is FirebaseFunctionsException ? (e.message ?? e.code) : e.toString();
