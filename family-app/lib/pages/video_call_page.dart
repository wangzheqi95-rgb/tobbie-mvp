import 'dart:async';

import 'package:agora_rtc_engine/agora_rtc_engine.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import '../util.dart';

/// 視訊通話（Agora）：
///  - 平常：機器人響鈴，長輩按「接聽」才接通；45 秒沒接算未接
///  - 長輩警報中：機器人自動接通，家人可以立刻看到現場、跟長輩說話
/// 通話不錄影、不保存。
class VideoCallPage extends StatefulWidget {
  const VideoCallPage({super.key, required this.deviceId, required this.elderName});
  final String deviceId;
  final String elderName;
  @override
  State<VideoCallPage> createState() => _VideoCallPageState();
}

class _VideoCallPageState extends State<VideoCallPage> {
  static const robotUid = 100; // 與後端 ROBOT_UID 相同
  RtcEngine? _engine;
  String? _callId;
  String? _channel;
  String _status = '準備中…';
  bool _robotJoined = false, _muted = false, _ended = false;
  StreamSubscription? _sub;

  @override
  void initState() {
    super.initState();
    _start();
  }

  Future<void> _start() async {
    final perms = await [Permission.camera, Permission.microphone].request();
    if (perms.values.any((p) => !p.isGranted)) { _finish('需要相機與麥克風權限才能視訊'); return; }
    try {
      final r = await functions.httpsCallable('startVideoCall').call({'deviceId': widget.deviceId});
      final m = Map<String, dynamic>.from(r.data as Map);
      _callId = m['callId'] as String;
      _channel = m['channel'] as String;
      setState(() => _status = m['autoAnswer'] == true ? '長輩警報中，正在直接接通…' : '正在呼叫 ${widget.elderName}…');
      // 看通話狀態：長輩拒接、沒接、機器人掛斷
      _sub = FirebaseFirestore.instance.collection('devices').doc(widget.deviceId).collection('calls').doc(_callId).snapshots().listen((s) {
        final st = s.data()?['status'];
        if (st == 'declined') _finish('${widget.elderName}現在不方便，晚點再打');
        if (st == 'ended' && s.data()?['endedBy'] == 'robot') _finish('通話結束');
      });
      final e = createAgoraRtcEngine();
      await e.initialize(RtcEngineContext(appId: m['appId'] as String, channelProfile: ChannelProfileType.channelProfileCommunication));
      e.registerEventHandler(RtcEngineEventHandler(
        onUserJoined: (c, uid, _) { if (uid == robotUid && mounted) setState(() { _robotJoined = true; _status = '通話中'; }); },
        onUserOffline: (c, uid, _) { if (uid == robotUid) _finish('對方已掛斷'); },
        onError: (err, msg) { if (mounted) setState(() => _status = '連線問題：$msg'); },
      ));
      await e.enableVideo();
      await e.startPreview();
      await e.setDefaultAudioRouteToSpeakerphone(true);
      await e.joinChannel(token: m['token'] as String, channelId: m['channel'] as String, uid: (m['uid'] as num).toInt(),
          options: const ChannelMediaOptions(clientRoleType: ClientRoleType.clientRoleBroadcaster,
              publishCameraTrack: true, publishMicrophoneTrack: true, autoSubscribeAudio: true, autoSubscribeVideo: true));
      _engine = e;
      if (mounted) setState(() {});
      // 45 秒機器人沒加入：未接
      Future.delayed(const Duration(seconds: 46), () { if (!_robotJoined) _finish('${widget.elderName}沒有接聽'); });
    } catch (e) {
      _finish('無法撥出：${errText(e)}');
    }
  }

  Future<void> _finish(String msg) async {
    if (_ended) return;
    _ended = true;
    await _sub?.cancel();
    if (_callId != null) {
      try { await functions.httpsCallable('endVideoCall').call({'deviceId': widget.deviceId, 'callId': _callId}); } catch (_) {}
    }
    final e = _engine;
    _engine = null;
    if (e != null) { await e.leaveChannel(); await e.release(); }
    if (mounted) { toast(context, msg); Navigator.pop(context); }
  }

  @override
  void dispose() {
    if (!_ended) _finish('通話結束');
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final e = _engine;
    return PopScope(
      canPop: false,
      onPopInvoked: (didPop) { if (!didPop) _finish('通話結束'); },
      child: Scaffold(
        backgroundColor: Colors.black,
        body: Stack(children: [
          Positioned.fill(child: e != null && _robotJoined
              ? AgoraVideoView(controller: VideoViewController.remote(rtcEngine: e, canvas: const VideoCanvas(uid: robotUid),
                  connection: RtcConnection(channelId: _channel)))
              : Container(color: robotBg, alignment: Alignment.center,
                  child: Text(_status, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white, fontSize: 20)))),
          if (e != null)
            Positioned(top: 48, right: 16, width: 110, height: 160, child: ClipRRect(borderRadius: BorderRadius.circular(12),
                child: AgoraVideoView(controller: VideoViewController(rtcEngine: e, canvas: const VideoCanvas(uid: 0))))),
          Positioned(top: 52, left: 16, child: Text(widget.elderName, style: const TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.bold))),
          Positioned(left: 0, right: 0, bottom: 40, child: Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
            _round(_muted ? Icons.mic_off : Icons.mic, Colors.white24, () async { _muted = !_muted; await _engine?.muteLocalAudioStream(_muted); setState(() {}); }),
            _round(Icons.call_end, alertRed, () => _finish('通話結束'), size: 72),
            _round(Icons.cameraswitch, Colors.white24, () => _engine?.switchCamera()),
          ])),
        ]),
      ),
    );
  }

  Widget _round(IconData icon, Color bg, VoidCallback onTap, {double size = 56}) => Material(
      color: bg, shape: const CircleBorder(),
      child: InkWell(customBorder: const CircleBorder(), onTap: onTap,
          child: SizedBox(width: size, height: size, child: Icon(icon, color: Colors.white, size: size * 0.45))));
}
