import 'package:cloud_functions/cloud_functions.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

/// 設計稿的顏色（沉穩青綠＋溫暖米白）。
const brandTeal = Color(0xFF1F6F66);
const brandTealLight = Color(0xFFE3F1EE);
const brandCream = Color(0xFFFBF8F3);
const alertRed = Color(0xFFB3261E);
const brandLine = Color(0xFFE6E0D6);
const brandInk = Color(0xFF1D2B2A);
const brandMuted = Color(0xFF5B6B68);
const okGreen = Color(0xFF2E7D4F);
const amber = Color(0xFF8A5A00);
const amberLight = Color(0xFFFFF1D6);
const robotBg = Color(0xFF10201E);
const robotEye = Color(0xFF7FE0CC);

/// 白底卡片＋細框（設計稿的卡片樣式）。
final cardDecoration = BoxDecoration(color: Colors.white, border: Border.all(color: brandLine), borderRadius: BorderRadius.circular(18));
final bigButton = FilledButton.styleFrom(minimumSize: const Size.fromHeight(56), textStyle: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)));

/// 服務條款與隱私權政策（放在 Firebase Hosting 的 legal/ 底下）。
/// 建置時可用 --dart-define=NEARA_WEB=https://你的網址 換掉預設值。
const webBase = String.fromEnvironment('NEARA_WEB', defaultValue: 'https://neara-prod.web.app');
const termsUrl = '$webBase/legal/terms.html';
const privacyUrl = '$webBase/legal/privacy.html';

/// 機器人超過 3 分鐘沒回報就算離線。
bool isOnline(Map<String, dynamic> d) {
  final last = d['lastSeen'] as num?;
  return last != null && DateTime.now().millisecondsSinceEpoch - last < 3 * 60 * 1000;
}

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
  if (x['outcome'] == 'ALERTED' && x['resolved'] == null) return ('警報中', alertRed);
  if (x['resolved'] != null) return ('已結案', const Color(0xFF8A8F8C));
  if (x['outcome'] == 'CANCELLED') return ('長輩表示沒事，已取消', okGreen);
  return ('確認中', amber);
}

void toast(BuildContext context, String msg) =>
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

/// 把後端錯誤轉成看得懂的中文。
String errText(Object e) => e is FirebaseFunctionsException ? (e.message ?? e.code) : e.toString();
