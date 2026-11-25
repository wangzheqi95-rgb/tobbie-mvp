import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

import 'pages/incident_page.dart';

/// 推播：
///  - 兩個 Android 通知頻道：neara_critical（緊急，最高優先、響鈴）與 neara_info（一般）。
///  - 前景收到時也跳出通知；點通知直接打開事件報告。
///  - 推播 token 存到 users/{uid}.fcmTokens，後端據此發送。
///  - iOS 緊急通知（Critical Alert，勿擾模式也會響）需另外向 Apple 申請權限。
class Notifications {
  static final navigatorKey = GlobalKey<NavigatorState>();
  static final _local = FlutterLocalNotificationsPlugin();
  static const _critical = AndroidNotificationChannel('neara_critical', '緊急警報',
      description: '長者可能跌倒且沒有回應', importance: Importance.max, playSound: true, enableVibration: true);
  static const _info = AndroidNotificationChannel('neara_info', '一般通知', description: '機器人確認中、已取消、已結案', importance: Importance.defaultImportance);

  static Future<void> init() async {
    await _local.initialize(
      const InitializationSettings(android: AndroidInitializationSettings('@mipmap/ic_launcher'), iOS: DarwinInitializationSettings()),
      onDidReceiveNotificationResponse: (r) => _open(r.payload),
    );
    final android = _local.resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();
    await android?.createNotificationChannel(_critical);
    await android?.createNotificationChannel(_info);

    await FirebaseMessaging.instance.requestPermission(alert: true, sound: true, badge: true, criticalAlert: true);
    FirebaseMessaging.onMessage.listen((m) {
      final n = m.notification;
      if (n == null) return;
      final critical = m.data['level'] == 'critical';
      final ch = critical ? _critical : _info;
      _local.show(m.hashCode, n.title, n.body,
          NotificationDetails(
            android: AndroidNotificationDetails(ch.id, ch.name,
                importance: ch.importance, priority: critical ? Priority.max : Priority.defaultPriority, fullScreenIntent: critical),
            iOS: const DarwinNotificationDetails(presentSound: true),
          ),
          payload: '${m.data['deviceId']}|${m.data['incidentId']}');
    });
    FirebaseMessaging.onMessageOpenedApp.listen((m) => _open('${m.data['deviceId']}|${m.data['incidentId']}'));
    final initial = await FirebaseMessaging.instance.getInitialMessage();
    if (initial != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _open('${initial.data['deviceId']}|${initial.data['incidentId']}'));
    }
  }

  static Future<void> registerToken(String uid) async {
    try {
      final t = await FirebaseMessaging.instance.getToken();
      if (t == null) return;
      await FirebaseFirestore.instance.collection('users').doc(uid).set({
        'fcmTokens': FieldValue.arrayUnion([t]),
      }, SetOptions(merge: true));
    } catch (_) {
      // 推播權限被拒或網路問題：App 仍可正常使用，只是收不到推播
    }
  }

  static void _open(String? payload) {
    if (payload == null || !payload.contains('|')) return;
    final parts = payload.split('|');
    if (parts[0] == 'null' || parts[1] == 'null') return;
    navigatorKey.currentState?.push(MaterialPageRoute(builder: (_) => IncidentPage(deviceId: parts[0], incidentId: parts[1])));
  }
}
