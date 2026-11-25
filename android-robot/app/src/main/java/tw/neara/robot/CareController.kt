package tw.neara.robot

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tw.neara.core.Answer
import tw.neara.core.CallPlan
import tw.neara.core.ackLine
import tw.neara.core.CareState
import tw.neara.core.DetectorConfig
import tw.neara.core.FallDetector
import tw.neara.core.FallReason
import tw.neara.core.Incident
import tw.neara.core.Lines
import tw.neara.core.Observation
import tw.neara.core.Outcome
import tw.neara.core.Pose
import tw.neara.core.PoseFrame
import tw.neara.core.PoseRing
import tw.neara.core.Replies
import tw.neara.core.measure
import java.io.ByteArrayOutputStream
import java.util.Calendar

/** 畫面要顯示的東西（由 MainActivity 實作）。 */
interface CareUi {
    fun state(s: CareState)
    fun info(text: String)
    fun bubble(text: String?)
    fun alarm(show: Boolean, text: String = "")
    fun log(text: String)
    /** 用這支手機的 SIM 卡撥電話（開擴音）；回傳是否成功撥出。 */
    fun placeCall(name: String, phone: String): Boolean
}

/**
 * 照護流程總控（提案 4.4 狀態機）：
 * MONITOR →（跌倒）→ NAVIGATE 前往 → CONFIRM 詢問 → 沒事：CANCELLED → COOLDOWN
 *                                                  → 求救／沒回應：ALERT（通知家屬與守護中心）→ 解除 → COOLDOWN
 * 另有 CHAT 主動關心；聊天中偵測到跌倒或聽到緊急詞，立刻中斷聊天改走跌倒流程。
 * 跌倒是否成立、要不要警報，一律用規則判斷，不交給 AI。
 */
class CareController(
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val voice: Voice,
    private val cloud: Cloud,
    private val ui: CareUi,
    private val health: HealthSync,
    private val snapshot: () -> Bitmap?,
) {
    var chassis: Chassis = NoChassis()
    private val detector = FallDetector()
    private val ring = PoseRing()
    private val alarmSound = AlarmSound()

    var state = CareState.IDLE; private set
    private var cooldownUntil = 0L
    private var lastChatAt = 0L
    private var chatJob: Job? = null
    private var incident: Incident? = null
    private val collecting = mutableListOf<Incident>()
    private var frameW = 0
    private var frameH = 0
    private var callPlan: CallPlan? = null
    private var callList: List<Pair<String, String>> = emptyList()
    private var ackedBy: String? = null

    private val name get() = prefs.elderName.ifBlank { "您" }
    private fun now() = System.currentTimeMillis()

    private fun setState(s: CareState) { state = s; ui.state(s) }

    fun start() {
        detector.cfg = DetectorConfig(confirmSec = prefs.confirmSec.toDouble(), requireFastFall = prefs.requireFastFall)
        setState(CareState.MONITOR)
        scope.launch { voice.speak("${name}您好，我開始陪您囉！") }
        scope.launch { heartbeatLoop() }
    }

    // ---------------------------------------------------------------- 每一幀
    /** 由相機執行緒呼叫，切回主執行緒處理。 */
    fun onPoses(poses: List<Pose>, w: Int, h: Int) {
        scope.launch(Dispatchers.Main) { handleFrame(poses, w, h) }
    }

    private fun handleFrame(poses: List<Pose>, w: Int, h: Int) {
        if (state == CareState.IDLE) return
        val t = now()
        frameW = w; frameH = h
        val aspect = w.toDouble() / h
        recordPose(poses, t, aspect)
        val armed = state in setOf(CareState.MONITOR, CareState.SUSPECT, CareState.CHAT) && t > cooldownUntil
        when (val o = detector.update(poses, t, aspect, armed)) {
            is Observation.Fall -> fall(o.px, o.reason)
            is Observation.Suspect -> { if (state == CareState.MONITOR) setState(CareState.SUSPECT); ui.info("疑似跌倒，確認中 %.1f / %.0f 秒".format(o.held, o.need)) }
            is Observation.VanishWaiting -> ui.info("人往下掉後消失，等待中 %.1f / %.0f 秒".format(o.gone, o.need))
            is Observation.LyingSlow -> ui.info("躺平，但不是快速倒下")
            is Observation.Leaning -> { backToMonitor(); ui.info("傾斜中（軀幹 %.0f°）".format(o.deg)) }
            is Observation.Upright -> {
                backToMonitor(); ui.info("直立（軀幹 %.0f°，%d 人）".format(o.deg, o.persons))
                val gap = prefs.chatGapMin * 60_000L
                if (state == CareState.MONITOR && t > cooldownUntil && (lastChatAt == 0L || t - lastChatAt >= gap)) {
                    startChat(if (lastChatAt == 0L) "啟動後第一次看到人" else "超過 ${prefs.chatGapMin} 分鐘沒聊天")
                }
            }
            Observation.NoPerson -> if (state == CareState.MONITOR) ui.info("畫面中沒有偵測到人")
        }
    }

    private fun backToMonitor() { if (state == CareState.SUSPECT) setState(CareState.MONITOR) }

    private fun recordPose(poses: List<Pose>, t: Long, aspect: Double) {
        val best = poses.maxByOrNull { measure(it, aspect, 0.3f)?.deg ?: -1.0 }
        val m = best?.let { measure(it, aspect, 0.3f) }
        val f = PoseFrame(t, m?.deg?.toInt(), if (m != null) best.map { it.x to it.y } else null)
        if (!ring.push(f)) return
        val iter = collecting.iterator()
        while (iter.hasNext()) {
            val inc = iter.next()
            if (t - inc.startMs <= Incident.POST_MS) inc.post += f
            else { iter.remove(); scope.launch { cloud.saveIncident(inc, null, false) } } // 事件後 120 秒骨架收齊再更新一次
        }
    }

    // ---------------------------------------------------------------- 跌倒流程
    fun testFall() = fall(detector.lastPx, FallReason.TEST)

    private fun dirFor(px: Double): Char? {
        var d = when { px < 0.4 -> 'L'; px > 0.6 -> 'R'; else -> null }
        if (prefs.useFrontCamera && d != null) d = if (d == 'L') 'R' else 'L' // 前鏡頭畫面左右相反
        if (prefs.swapLR && d != null) d = if (d == 'L') 'R' else 'L'
        return d
    }

    private fun fall(px: Double, reason: FallReason) {
        if (state in setOf(CareState.NAVIGATE, CareState.CONFIRM, CareState.ALERT)) return
        chatJob?.cancel(); voice.cancelListening(); voice.stopSpeaking(); ui.bubble(null)
        scope.launch { fallFlow(px, reason) }
    }

    private suspend fun fallFlow(px: Double, reason: FallReason) {
        setState(CareState.NAVIGATE)
        ui.log(reason.label)
        val t = now()
        val inc = Incident(Incident.newId(prefs.deviceId, t), prefs.deviceId, reason, t,
            detector.lastWellMs.takeIf { it > 0 }, prefs.profile, chassis.name, frameW, frameH, ring.snapshot())
        val jpeg = withContext(Dispatchers.Default) { snapshot()?.let { jpegOf(it) } }
        incident = inc; collecting += inc
        cloud.saveIncident(inc, jpeg, isNew = true)

        // NAVIGATE：最多 8 秒，失敗也照常往下問
        ui.info("前往長者身邊…")
        val dir = dirFor(px)
        val moved = withTimeoutOrNull(8000) { runCatching { chassis.navigate(dir, prefs.turnMs, prefs.approachMs) }.isSuccess }
        if (moved != true) ui.log("底盤移動失敗或逾時（流程照常進行）")

        // CONFIRM：最多問兩次
        setState(CareState.CONFIRM)
        val prompts = listOf(Lines.ask1(name, reason), Lines.ask2(name))
        var result = Answer.NONE
        for ((i, p) in prompts.withIndex()) {
            ui.bubble(p); inc.say("機器人", p, now()); voice.speak(p)
            ui.info("聆聽中（第 ${i + 1} 次）…")
            val heard = voice.listen(8, earlyStop = true)
            val verdict = when (heard.answer) { Answer.OK -> "沒事"; Answer.HELP -> "求救"; else -> "無法判斷" }
            inc.say(name, "${heard.text.ifBlank { "（沒有回應）" }}　→ 判讀：$verdict", now())
            ui.log("第 ${i + 1} 次回應：${heard.text.ifBlank { "（沒有回應）" }} → $verdict")
            result = heard.answer
            if (result != Answer.NONE) break
        }

        if (result == Answer.OK) {
            ui.bubble(Lines.RELIEF); inc.say("機器人", Lines.RELIEF, now()); voice.speak(Lines.RELIEF)
            runCatching { chassis.normal() }
            inc.close(Outcome.CANCELLED, now())
            cloud.saveIncident(inc, null, false)
            ui.log("長者表示沒事，已取消")
            cooldown()
            return
        }

        // ALERT
        setState(CareState.ALERT)
        inc.close(Outcome.ALERTED, now())
        cloud.saveIncident(inc, null, false) // 後端收到 ALERTED 會立刻通知家屬與守護中心
        ui.alarm(true, if (cloud.enabled) "已通知家屬與守護中心" else "尚未設定雲端後端：只在本機警報")
        alarmSound.start()
        scope.launch { runCatching { chassis.alarm(dir, prefs.turnMs) } }
        val msg = Lines.alarm(name)
        ui.bubble(msg); inc.say("機器人", msg, now()); voice.speak(msg)
        // 自動撥號：先撥第 1 位，之後由心跳每 10 秒檢查要不要撥下一位
        ackedBy = null
        callList = if (prefs.autoCall) cloud.callList() else emptyList()
        callPlan = CallPlan(callList.size)
        callTick(inc)
        // 警報期間每 60 秒安撫一次，直到有人解除
        while (state == CareState.ALERT && scope.isActive) {
            delay(60_000)
            if (state == CareState.ALERT) voice.speak(ackedBy?.let { "${name}，${it}正在處理，我會一直陪著您。" } ?: "${name}，我還在這裡陪您，家人很快就來了。")
        }
    }

    /** 警報中：時間到了就撥下一位家人（有人在 App 按「我來處理」就不再撥）。 */
    private fun callTick(inc: Incident) {
        val i = callPlan?.due(now(), ackedBy != null) ?: return
        val (who, phone) = callList[i]
        alarmSound.stop() // 警報聲會蓋過通話，撥號前先停
        val ok = ui.placeCall(who, phone)
        val line = if (ok) "自動撥號給 $who" else "撥號給 $who 失敗（請確認 SIM 卡與電話權限）"
        inc.say("系統", line, now()); ui.log(line)
        scope.launch { cloud.saveIncident(inc, null, false) }
    }

    /** 心跳發現有家人按了「我來處理」。 */
    private fun onAcked(inc: Incident, by: String) {
        if (ackedBy != null) return
        ackedBy = by
        val line = ackLine(name, by)
        ui.alarm(true, "$by 已接手處理"); ui.bubble(line)
        inc.say("系統", "$by 在 App 按下「我來處理」", now())
        scope.launch { voice.speak(line); cloud.saveIncident(inc, null, false) }
    }

    /** 現場的人按「解除警報」。 */
    fun clearAlarm(by: String = "現場解除") {
        callPlan = null
        alarmSound.stop(); voice.stopSpeaking(); ui.alarm(false); ui.bubble(null)
        incident?.let { inc ->
            if (inc.resolvedMs == null) { inc.resolvedMs = now(); inc.resolvedBy = by; scope.launch { cloud.saveIncident(inc, null, false) } }
        }
        scope.launch { runCatching { chassis.normal() } }
        ui.log("警報已解除（$by）")
        cooldown()
    }

    private fun cooldown() {
        setState(CareState.COOLDOWN)
        cooldownUntil = now() + 20_000
        lastChatAt = now()
        detector.reset()
        scope.launch { delay(20_000); if (state == CareState.COOLDOWN) setState(CareState.MONITOR) }
    }

    // ---------------------------------------------------------------- 主動關心聊天
    fun startChat(reason: String) {
        if (state != CareState.MONITOR) return
        chatJob = scope.launch {
            setState(CareState.CHAT)
            lastChatAt = now()
            ui.log("開始主動關心（$reason）")
            val history = mutableListOf<Pair<String, String>>()
            val first = Lines.greet(name, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
            history += "model" to first
            ui.bubble(first); voice.speak(first)
            for (turn in 0 until 6) {
                ui.info("聊天中，聆聽…")
                val said = voice.listen(9, earlyStop = false).text.trim()
                if (said.isEmpty()) { ui.bubble(Lines.CHAT_NO_REPLY); voice.speak(Lines.CHAT_NO_REPLY); break }
                ui.log("$name：$said")
                if (Replies.isEmergency(said)) { endChat(); fall(detector.lastPx, FallReason.CHAT); return@launch }
                if (Replies.isBye(said)) { ui.bubble(Lines.CHAT_BYE); voice.speak(Lines.CHAT_BYE); break }
                history += "user" to said
                ui.info("思考中…")
                val reply = cloud.chat(name, history) ?: Lines.CANNED[turn % Lines.CANNED.size]
                history += "model" to reply
                ui.bubble(reply); voice.speak(reply)
            }
            endChat()
        }
    }

    private fun endChat() {
        if (state == CareState.CHAT) setState(CareState.MONITOR)
        lastChatAt = now(); ui.bubble(null)
    }

    // ---------------------------------------------------------------- 心跳與補傳
    private suspend fun heartbeatLoop() {
        var lastFull = 0L
        var lastHealth = 0L
        while (scope.isActive) {
            val beat = cloud.heartbeat(state.name, chassis.name, health.status)
            val inc = incident
            if (state == CareState.ALERT && inc != null) {
                beat.acked[inc.id]?.let { onAcked(inc, it) }
                if (inc.id in beat.resolved) clearAlarm("家屬／守護中心遠端解除") else callTick(inc)
            }
            if (now() - lastFull > 60_000) { cloud.retryPending(); lastFull = now() }
            // 手錶資料：平常每 5 分鐘、警報中每 1 分鐘同步一次（事件報告要事件後的心率）
            if (now() - lastHealth > if (state == CareState.ALERT) 60_000 else 300_000) {
                lastHealth = now()
                runCatching { health.read() }.onFailure { ui.log("讀取手錶資料失敗：${it.message}") }.getOrNull()?.let { r ->
                    if (cloud.postHealth(r.hr, r.stepsToday)) prefs.healthLastSync = now()
                }
            }
            delay(if (state == CareState.ALERT) 10_000 else 60_000) // 警報中每 10 秒問一次有沒有人接手或遠端結案
        }
    }

    fun stop() { alarmSound.stop(); chassis.close() }

    private fun jpegOf(b: Bitmap): ByteArray {
        val scale = 640f / maxOf(b.width, b.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
    }

    companion object { const val TAG = "Neara" }
}
