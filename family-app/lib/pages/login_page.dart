import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';

import '../util.dart';

/// 登入／註冊（Email＋密碼）。
class LoginPage extends StatefulWidget {
  const LoginPage({super.key});
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _email = TextEditingController();
  final _pw = TextEditingController();
  bool _busy = false;
  bool _register = false;

  Future<void> _submit() async {
    setState(() => _busy = true);
    try {
      final auth = FirebaseAuth.instance;
      if (_register) {
        await auth.createUserWithEmailAndPassword(email: _email.text.trim(), password: _pw.text);
      } else {
        await auth.signInWithEmailAndPassword(email: _email.text.trim(), password: _pw.text);
      }
    } on FirebaseAuthException catch (e) {
      if (mounted) toast(context, _register ? '註冊失敗：${e.message}' : '登入失敗：${e.message}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: ListView(padding: const EdgeInsets.all(24), children: [
          const SizedBox(height: 48),
          Text('Neara 家屬', style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 8),
          const Text('長輩有狀況時第一時間通知您，並附上機器人整理好的事件報告。'),
          const SizedBox(height: 32),
          TextField(controller: _email, keyboardType: TextInputType.emailAddress, decoration: const InputDecoration(labelText: 'Email')),
          TextField(controller: _pw, obscureText: true, decoration: const InputDecoration(labelText: '密碼（至少 6 碼）')),
          const SizedBox(height: 24),
          FilledButton(onPressed: _busy ? null : _submit, child: Text(_register ? '註冊' : '登入')),
          TextButton(onPressed: () => setState(() => _register = !_register), child: Text(_register ? '已有帳號？登入' : '還沒有帳號？註冊')),
          const SizedBox(height: 24),
          const Text('註冊即表示您同意 Neara 依個人資料保護法蒐集、處理長者的健康與事件資料，用於緊急通報與照護。', style: TextStyle(fontSize: 12)),
        ]),
      ),
    );
  }
}
