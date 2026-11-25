package tw.neara.robot

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.neara.core.CareState
import tw.neara.core.FaceButton
import tw.neara.core.Mood
import tw.neara.core.buttonsFor
import tw.neara.core.idleCaption
import tw.neara.core.moodFor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 機器人主畫面：開了就自動開始監測（不需要按任何按鈕）。
 * 長輩看到的是表情臉（照設計稿）；鏡頭畫面與偵測資訊在「維護面板」，長按上方狀態列 2 秒打開。
 * 螢幕保持常亮；可設為系統預設主畫面，開機直接進入。
 */
class MainActivity : AppCompatActivity(), CareUi {
    private lateinit var prefs: Prefs
    private lateinit var voice: Voice
    private lateinit var cloud: Cloud
    private lateinit var care: CareController
    private var analyzer: PoseAnalyzer? = null
    private val cameraExec = Executors.newSingleThreadExecutor()
    private var started = false

    private lateinit var root: View
    private lateinit var face: FaceView
    private lateinit var stateView: TextView
    private lateinit var statusDot: View
    private lateinit var clockView: TextView
    private lateinit var bubbleView: TextView
    private lateinit var detailView: TextView
    private lateinit var pairingView: TextView
    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var debugLayer: View
    private lateinit var infoView: TextView
    private lateinit var debugPairing: TextView
    private lateinit var overlay: OverlayView

    private var current = CareState.IDLE
    private var bubbleText: String? = null
    private var pairingCode: String? = null
    private var isPaired: Boolean? = null
    private var flash: ValueAnimator? = null

    // 視訊通話
    private lateinit var ringLayer: View
    private lateinit var callLayer: View
    private lateinit var ringText: TextView
    private lateinit var callText: TextView
    private var videoCall: VideoCall? = null
    private var callId: String? = null
    private var callFrom = ""
    private var ringtone: Ringtone? = null

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.CAMERA] == true && res[Manifest.permission.RECORD_AUDIO] == true) startAll()
        else bubble("需要相機與麥克風權限才能照護長輩，請到系統設定開啟。")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        face = findViewById(R.id.face)
        stateView = findViewById(R.id.state)
        statusDot = findViewById(R.id.statusDot)
        clockView = findViewById(R.id.clock)
        bubbleView = findViewById(R.id.bubble)
        detailView = findViewById(R.id.detail)
        pairingView = findViewById(R.id.pairing)
        btnLeft = findViewById(R.id.btnLeft)
        btnRight = findViewById(R.id.btnRight)
        debugLayer = findViewById(R.id.debugLayer)
        infoView = findViewById(R.id.info)
        debugPairing = findViewById(R.id.debugPairing)
        overlay = findViewById(R.id.overlay)
        ringLayer = findViewById(R.id.ringLayer)
        callLayer = findViewById(R.id.callLayer)
        ringText = findViewById(R.id.ringText)
        callText = findViewById(R.id.callText)
        findViewById<Button>(R.id.btnAnswer).setOnClickListener { callId?.let { answer(it) } }
        findViewById<Button>(R.id.btnDecline).setOnClickListener { decline() }
        findViewById<Button>(R.id.btnHangup).setOnClickListener { endCall(notify = true) }

        prefs = Prefs(this)
        voice = Voice(this, prefs)
        voice.onSpeaking = { face.talking = it }
        cloud = Cloud(prefs, filesDir)
        care = CareController(lifecycleScope, prefs, voice, cloud, this, HealthSync(this, prefs)) { analyzer?.lastBitmap }

        btnLeft.setOnClickListener { onFaceButton(buttonsFor(current).first) }
        btnRight.setOnClickListener { onFaceButton(buttonsFor(current).second) }
        // 維護面板：長按狀態列（長輩不容易誤觸）
        findViewById<View>(R.id.statusRow).setOnLongClickListener { debugLayer.visibility = View.VISIBLE; true }
        findViewById<Button>(R.id.btnCloseDebug).setOnClickListener { debugLayer.visibility = View.GONE }
        findViewById<Button>(R.id.btnTest).setOnClickListener { if (started) { debugLayer.visibility = View.GONE; care.testFall() } }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        // 視訊來電：推播（FCM）或心跳都會送到這裡
        lifecycleScope.launch { CallBus.calls.collect { onIncoming(it) } }
        runCatching { FirebaseMessaging.getInstance().token.addOnSuccessListener { prefs.fcmToken = it } }
            .onFailure { Log.w(CareController.TAG, "沒有 google-services.json：視訊來電改由心跳偵測（最多延遲 1 分鐘）") }

        lifecycleScope.launch {
            while (true) { clockView.text = SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date()); refreshIdleCaption(); delay(30_000) }
        }
        state(CareState.IDLE)

        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (prefs.autoCall) perms += Manifest.permission.CALL_PHONE // 警報時用 SIM 卡撥給家人（拒絕也能照常運作）
        if (prefs.chassis == "tobbie") {
            if (Build.VERSION.SDK_INT >= 31) perms += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startAll()
        else askPerms.launch(perms.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        if (prefs.kioskLock) runCatching { startLockTask() } // 螢幕固定：長者不會不小心跳出 App
    }

    private fun onFaceButton(b: FaceButton) {
        if (!started) return
        when (b) {
            FaceButton.CHAT -> care.startChat("長輩按下「聊天」")
            FaceButton.END_CHAT -> care.endChatButton()
            FaceButton.OK, FaceButton.OK_NOW -> care.okButton()
            FaceButton.HELP -> care.helpButton()
            FaceButton.CALL_119 -> care.call119()
        }
    }

    private fun startAll() {
        if (started) return
        started = true
        try {
            analyzer = PoseAnalyzer(this) { poses, w, h -> overlay.show(poses, w, h); care.onPoses(poses, w, h) }
        } catch (e: Exception) {
            bubble("姿勢辨識無法啟動，請聯絡安裝人員。"); infoView.text = "姿勢辨識無法啟動：${e.message}"
            return
        }
        startCamera()
        care.start()
        lifecycleScope.launch {
            val code = cloud.ensureRegistered()
            pairingCode = code
            debugPairing.text = when {
                !cloud.enabled -> "尚未設定雲端後端（設定 → 後端網址）：事件只存在本機"
                code != null -> "家屬配對碼：$code（10 分鐘內有效；設定裡可以重新產生）"
                else -> "雲端連線失敗：${cloud.lastError ?: "未知"}"
            }
            refreshPairing()
        }
        connectChassis()
    }

    private fun connectChassis() {
        val ch = Chassis.create(this, prefs)
        care.chassis = ch
        if (ch is NoChassis) return
        lifecycleScope.launch {
            runCatching { ch.connect() }
                .onSuccess { log("${ch.name} 已連線") }
                .onFailure { log("${ch.name} 連線失敗：${it.message}") }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewView = findViewById<PreviewView>(R.id.preview)
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExec) { proxy -> analyzer?.analyze(proxy) ?: proxy.close() }
            val selector = if (prefs.useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            overlay.mirror = prefs.useFrontCamera
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------------------------------------------------------- 表情臉
    private data class Look(val bg: String, val accent: String, val label: String)
    private fun lookOf(s: CareState) = when (moodFor(s)) {
        Mood.WORRIED -> Look("#2A2416", "#F2B84B", if (s == CareState.NAVIGATE) "偵測到疑似跌倒・正在過去" else "偵測到疑似跌倒・正在確認")
        Mood.ALARM -> Look("#B3261E", "#FFFFFF", "已通知家人")
        Mood.HAPPY -> Look("#10201E", "#5FC79A", "聊天中")
        Mood.SLEEP -> Look("#10201E", "#8FB8B1", "啟動中")
        Mood.CALM -> Look("#10201E", "#5FC79A", "守護中")
    }

    private fun styleButton(b: Button, fb: FaceButton) {
        val (bg, fg) = when (fb) {
            FaceButton.CHAT -> "#1F6F66" to "#FFFFFF"
            FaceButton.END_CHAT -> "#24403C" to "#E3F1EE"
            FaceButton.OK -> "#2E7D4F" to "#FFFFFF"
            FaceButton.HELP -> "#B3261E" to "#FFFFFF"
            FaceButton.CALL_119 -> "#FFFFFF" to "#B3261E"
            FaceButton.OK_NOW -> "#8C1D18" to "#FFFFFF"
        }
        b.text = fb.label
        b.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bg))
        b.setTextColor(Color.parseColor(fg))
    }

    override fun state(s: CareState) = runOnUiThread {
        current = s
        val look = lookOf(s)
        face.mood = moodFor(s)
        stateView.text = look.label
        stateView.setTextColor(Color.parseColor(if (s == CareState.ALERT) "#FFFFFF" else "#8FB8B1"))
        statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor(look.accent))
        val (l, r) = buttonsFor(s)
        styleButton(btnLeft, l); styleButton(btnRight, r)
        flash?.cancel(); flash = null
        if (s == CareState.ALERT) {
            // 警報：背景在深紅與紅之間閃（1.6 秒一次）
            flash = ValueAnimator.ofObject(ArgbEvaluator(), Color.parseColor("#8C1D18"), Color.parseColor("#B3261E")).apply {
                duration = 800; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { root.setBackgroundColor(it.animatedValue as Int) }
                start()
            }
        } else {
            root.setBackgroundColor(Color.parseColor(look.bg))
            detailView.visibility = View.GONE
        }
        bubbleView.setTextColor(Color.parseColor(if (moodFor(s) == Mood.WORRIED) "#FFF1D6" else if (s == CareState.ALERT) "#FFFFFF" else "#E3F1EE"))
        refreshIdleCaption()
        refreshPairing()
    }

    private fun refreshIdleCaption() {
        if (bubbleText.isNullOrBlank()) {
            bubbleView.text = if (current in setOf(CareState.MONITOR, CareState.COOLDOWN))
                idleCaption(prefs.elderName.ifBlank { "您" }, Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) else ""
        }
    }

    private fun refreshPairing() {
        pairingView.text = when {
            current == CareState.ALERT -> ""
            isPaired == false && pairingCode != null -> "家屬配對碼：$pairingCode\n請在家屬 App 輸入（10 分鐘內有效）"
            else -> "鏡頭只分析身體姿勢，不錄影"
        }
    }

    override fun paired(yes: Boolean) = runOnUiThread {
        if (isPaired == yes) return@runOnUiThread
        isPaired = yes
        if (!yes && pairingCode == null) lifecycleScope.launch { pairingCode = cloud.ensureRegistered(); refreshPairing() }
        refreshPairing()
    }

    override fun info(text: String) = runOnUiThread { infoView.text = text }
    override fun bubble(text: String?) = runOnUiThread {
        bubbleText = text
        if (text.isNullOrBlank()) refreshIdleCaption() else bubbleView.text = text
    }
    override fun alarm(show: Boolean, text: String) = runOnUiThread {
        detailView.visibility = if (show && text.isNotBlank()) View.VISIBLE else View.GONE
        detailView.text = text
    }
    override fun log(text: String) { Log.i(CareController.TAG, text); runOnUiThread { infoView.text = text } }

    override fun placeCall(name: String, phone: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            // 螢幕固定模式下系統可能擋住通話畫面，撥號前先解除固定（回到 App 時 onResume 會再固定）
            if (prefs.kioskLock) runCatching { stopLockTask() }
            val tm = getSystemService(TelecomManager::class.java)
            val extras = Bundle().apply { putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true) } // 開擴音，長輩不用拿手機
            tm.placeCall(Uri.fromParts("tel", phone, null), extras)
        }.isSuccess
    }

    // ---------------------------------------------------------------- 視訊通話
    private fun onIncoming(c: IncomingCall) {
        if (!started || c.callId == callId || videoCall?.active == true) return
        callId = c.callId; callFrom = c.fromName
        if (c.autoAnswer) { answer(c.callId); return } // 警報中：家人直接看到現場
        ringText.text = "${c.fromName}\n想跟您視訊"
        ringLayer.visibility = View.VISIBLE
        ringtone = runCatching { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)) }.getOrNull()
        ringtone?.play()
        lifecycleScope.launch { voice.speak("${prefs.elderName.ifBlank { "您" }}，${c.fromName}打視訊來了，要接嗎？") }
        val id = c.callId
        lifecycleScope.launch { delay(45_000); if (callId == id && videoCall == null) stopRinging().also { callId = null } } // 45 秒沒接
    }

    private fun stopRinging() { ringtone?.stop(); ringtone = null; ringLayer.visibility = View.GONE }

    private fun answer(id: String) {
        stopRinging()
        lifecycleScope.launch {
            val r = cloud.callAction(id, "answer")
            if (r == null || !r.has("token")) {
                Toast.makeText(this@MainActivity, "這通視訊已經結束了", Toast.LENGTH_LONG).show(); callId = null; return@launch
            }
            voice.stopSpeaking(); voice.cancelListening()
            val vc = VideoCall(this@MainActivity, findViewById<FrameLayout>(R.id.remoteVideo),
                onEvent = { msg -> runOnUiThread { callText.text = "與 $callFrom 視訊中｜$msg" } },
                onRemoteLeft = { endCall(notify = true) })
            runCatching { vc.join(r.getString("appId"), r.getString("channel"), r.getString("token"), r.getInt("uid")) }
                .onFailure { log("視訊無法啟動：${it.message}"); cloud.callAction(id, "end"); callId = null; return@launch }
            videoCall = vc
            analyzer?.frameTap = { vc.pushFrame(it) } // 同一個鏡頭畫面給視訊；跌倒偵測繼續
            care.inCall = true
            callText.text = "與 $callFrom 視訊中"
            callLayer.visibility = View.VISIBLE
        }
    }

    private fun decline() {
        val id = callId ?: return
        stopRinging(); callId = null
        lifecycleScope.launch { cloud.callAction(id, "decline") }
    }

    private fun endCall(notify: Boolean) {
        val id = callId
        analyzer?.frameTap = null
        videoCall?.leave(); videoCall = null
        care.inCall = false
        callLayer.visibility = View.GONE
        callId = null
        if (notify && id != null) lifecycleScope.launch { cloud.callAction(id, "end") }
    }

    override fun onDestroy() {
        endCall(notify = true)
        stopRinging()
        flash?.cancel()
        care.stop(); voice.shutdown(); analyzer?.close(); cameraExec.shutdown()
        super.onDestroy()
    }

    /** 從設定頁回來：重新套用底盤設定。 */
    override fun onRestart() {
        super.onRestart()
        if (started) { care.chassis.close(); connectChassis() }
    }
}
