package tw.neara.core

import kotlin.math.abs
import kotlin.math.atan2

/** 一個關節點（座標為 0–1 的畫面比例）。 */
data class Landmark(val x: Float, val y: Float, val visibility: Float = 1f)

/** 一個人的 33 個關節點（MediaPipe Pose 格式）。 */
typealias Pose = List<Landmark>

data class DetectorConfig(
    val uprightDeg: Double = 35.0,     // 軀幹與垂直線夾角 < 35° → 直立（站或坐）
    val lyingDeg: Double = 60.0,       // > 60° → 躺平
    val fastFallSec: Double = 4.0,     // 從直立到躺平要在 4 秒內才算「快速倒下」
    val requireFastFall: Boolean = true,
    val confirmSec: Double = 3.0,      // 躺平持續幾秒才確認
    val minVisibility: Float = 0.3f,
    val vanishSec: Double = 3.0,       // 往下掉後消失超過幾秒 → 主動詢問
    val dropRatio: Double = 0.08,      // 1 秒內髖部下降超過畫面 8% → 算往下掉
)

/** 偵測器每一幀的觀察結果。 */
sealed class Observation {
    object NoPerson : Observation()
    data class Upright(val deg: Double, val persons: Int) : Observation()
    data class Leaning(val deg: Double, val persons: Int) : Observation()
    data class LyingSlow(val deg: Double, val sinceUprightSec: Double?) : Observation()
    data class Suspect(val held: Double, val need: Double) : Observation()
    data class VanishWaiting(val gone: Double, val need: Double) : Observation()
    /** 判定跌倒（躺平確認，或往下掉後消失）。px 是長者在畫面中的水平位置（0 左、1 右）。 */
    data class Fall(val px: Double, val reason: FallReason) : Observation()
}

enum class FallReason(val label: String) {
    LYING("偵測到疑似跌倒"),
    VANISH("長者往下掉後消失在畫面中"),
    CHAT("聊天中提到緊急狀況"),
    RADAR("毫米波雷達偵測到跌倒"),
    BUTTON("長輩按下「我需要幫忙」"),
    TEST("（測試）模擬跌倒"),
}

/** 量一個姿勢的軀幹角度（肩膀中點 → 髖部中點 與垂直線的夾角）。 */
data class Measure(val deg: Double, val hipX: Double, val hipY: Double)

fun measure(pose: Pose, aspect: Double, minVis: Float): Measure? {
    if (pose.size < 25) return null
    val idx = intArrayOf(11, 12, 23, 24) // 左右肩、左右髖
    if (idx.any { pose[it].visibility < minVis }) return null
    val shX = (pose[11].x + pose[12].x) / 2.0; val shY = (pose[11].y + pose[12].y) / 2.0
    val hpX = (pose[23].x + pose[24].x) / 2.0; val hpY = (pose[23].y + pose[24].y) / 2.0
    val dx = (shX - hpX) * aspect; val dy = hpY - shY
    return Measure(Math.toDegrees(atan2(abs(dx), abs(dy))), hpX, hpY)
}

/**
 * 跌倒偵測（規則式，對應提案 4.3-A）：
 *  規則一（躺平）：快速從直立變躺平，且躺著超過 confirmSec → 跌倒。
 *  規則二（消失）：往下掉或身體傾斜時從畫面消失，超過 vanishSec → 跌倒（處理被家具遮住）。
 *  同時看最多兩個人（鏡子倒影也算），取最平躺的那一個判斷。
 * 純邏輯、不碰相機，方便單元測試；時間一律用毫秒，由呼叫端傳入。
 */
class FallDetector(var cfg: DetectorConfig = DetectorConfig()) {
    var lastUprightMs = 0L; private set
    /** 最後一次正常活動（有人直立）的時間，給事件報告用。 */
    var lastWellMs = 0L; private set
    var lastPx = 0.5; private set
    private var lyingSince: Long? = null
    private var fastFall = false
    private var lastSeenMs = 0L
    private var vanishRisk = false
    private val hipTrail = ArrayDeque<Pair<Long, Double>>()

    fun reset() {
        lastUprightMs = 0; lyingSince = null; fastFall = false
        lastSeenMs = 0; vanishRisk = false; hipTrail.clear()
    }

    /**
     * @param armed false 時只更新內部狀態（例如正在詢問或冷卻中），不會回報 Fall。
     */
    fun update(poses: List<Pose>, nowMs: Long, aspect: Double, armed: Boolean): Observation {
        val ms = poses.mapNotNull { measure(it, aspect, cfg.minVisibility) }
        if (ms.isEmpty()) return noPerson(nowMs, armed)

        val primary = ms.maxByOrNull { it.deg }!!
        val deg = primary.deg
        lastPx = primary.hipX
        lastSeenMs = nowMs
        hipTrail.addLast(nowMs to primary.hipY)
        while (hipTrail.isNotEmpty() && nowMs - hipTrail.first().first > 1500) hipTrail.removeFirst()
        val old = hipTrail.firstOrNull { nowMs - it.first >= 800 } ?: hipTrail.first()
        val dropping = primary.hipY - old.second > cfg.dropRatio
        vanishRisk = dropping || deg > 45

        if (ms.any { it.deg < cfg.uprightDeg }) {
            lastUprightMs = nowMs
            lastWellMs = nowMs
            if (deg < cfg.uprightDeg) { lyingSince = null; fastFall = false }
        }

        if (deg > cfg.lyingDeg) {
            if (lyingSince == null) {
                lyingSince = nowMs
                fastFall = lastUprightMs > 0 && (nowMs - lastUprightMs) <= cfg.fastFallSec * 1000
            }
            if (cfg.requireFastFall && !fastFall) {
                return Observation.LyingSlow(deg, if (lastUprightMs > 0) (nowMs - lastUprightMs) / 1000.0 else null)
            }
            val held = (nowMs - lyingSince!!) / 1000.0
            if (armed && held >= cfg.confirmSec) {
                lyingSince = null; fastFall = false
                return Observation.Fall(primary.hipX, FallReason.LYING)
            }
            return Observation.Suspect(held, cfg.confirmSec)
        }
        if (deg >= cfg.uprightDeg) lyingSince = null
        return if (deg < cfg.uprightDeg) Observation.Upright(deg, ms.size) else Observation.Leaning(deg, ms.size)
    }

    private fun noPerson(nowMs: Long, armed: Boolean): Observation {
        if (lastSeenMs > 0 && vanishRisk) {
            val gone = (nowMs - lastSeenMs) / 1000.0
            if (armed && gone >= cfg.vanishSec) {
                lastSeenMs = 0; vanishRisk = false
                return Observation.Fall(lastPx, FallReason.VANISH)
            }
            return Observation.VanishWaiting(gone, cfg.vanishSec)
        }
        return Observation.NoPerson
    }
}
