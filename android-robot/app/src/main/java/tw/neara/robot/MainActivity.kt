package tw.neara.robot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
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
import kotlinx.coroutines.launch
import tw.neara.core.CareState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 機器人主畫面：開了就自動開始監測（不需要按任何按鈕）。
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

    private lateinit var stateView: TextView
    private lateinit var infoView: TextView
    private lateinit var bubbleView: TextView
    private lateinit var pairingView: TextView
    private lateinit var alarmView: LinearLayout
    private lateinit var overlay: OverlayView

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.CAMERA] == true && res[Manifest.permission.RECORD_AUDIO] == true) startAll()
        else infoView.text = "需要相機與麥克風權限才能照護長者，請到系統設定開啟。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        stateView = findViewById(R.id.state)
        infoView = findViewById(R.id.info)
        bubbleView = findViewById(R.id.bubble)
        pairingView = findViewById(R.id.pairing)
        alarmView = findViewById(R.id.alarm)
        overlay = findViewById(R.id.overlay)

        prefs = Prefs(this)
        voice = Voice(this, prefs)
        cloud = Cloud(prefs, filesDir)
        care = CareController(lifecycleScope, prefs, voice, cloud, this) { analyzer?.lastBitmap }

        findViewById<Button>(R.id.btnTest).setOnClickListener { if (started) care.testFall() }
        findViewById<Button>(R.id.btnChat).setOnClickListener { if (started) care.startChat("手動觸發") }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btnClear).setOnClickListener { care.clearAlarm() }

        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
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

    private fun startAll() {
        if (started) return
        started = true
        try {
            analyzer = PoseAnalyzer(this) { poses, w, h -> overlay.show(poses, w, h); care.onPoses(poses, w, h) }
        } catch (e: Exception) {
            infoView.text = "姿勢辨識無法啟動：${e.message}"
            return
        }
        startCamera()
        care.start()
        lifecycleScope.launch {
            val code = cloud.ensureRegistered()
            pairingView.text = when {
                !cloud.enabled -> "尚未設定雲端後端（設定 → 後端網址）：事件只存在本機"
                code != null -> "家屬配對碼：$code（在家屬 App 輸入，10 分鐘內有效）"
                else -> "雲端連線失敗：${cloud.lastError ?: "未知"}"
            }
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

    // ---------------------------------------------------------------- CareUi
    private val stateColor = mapOf(
        CareState.MONITOR to "#34A853", CareState.SUSPECT to "#F9AB00", CareState.CONFIRM to "#F9AB00",
        CareState.NAVIGATE to "#F9AB00", CareState.ALERT to "#EA4335", CareState.CHAT to "#A142F4",
    )
    override fun state(s: CareState) = runOnUiThread {
        stateView.text = s.label
        stateView.setBackgroundColor(Color.parseColor(stateColor[s] ?: "#1A1D24"))
    }
    override fun info(text: String) = runOnUiThread { infoView.text = text }
    override fun bubble(text: String?) = runOnUiThread {
        bubbleView.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        bubbleView.text = text ?: ""
    }
    override fun alarm(show: Boolean, text: String) = runOnUiThread {
        alarmView.visibility = if (show) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.alarmText).text = "${SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date())}\n$text"
    }
    override fun log(text: String) { Log.i(CareController.TAG, text); runOnUiThread { infoView.text = text } }

    override fun onDestroy() {
        care.stop(); voice.shutdown(); analyzer?.close(); cameraExec.shutdown()
        super.onDestroy()
    }

    /** 從設定頁回來：重新套用底盤設定。 */
    override fun onRestart() {
        super.onRestart()
        if (started) { care.chassis.close(); connectChassis() }
    }
}
