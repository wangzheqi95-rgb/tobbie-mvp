package tw.neara.core

/** 一幀骨架紀錄（只存關節座標，不存影像）。p 為 null 代表畫面中看不到人。 */
data class PoseFrame(val t: Long, val deg: Int?, val p: List<Pair<Float, Float>>?) : JsonWritable {
    override fun toJson(): String {
        val pts = p?.joinToString(",", "[", "]") { "[${round3(it.first)},${round3(it.second)}]" } ?: "null"
        return """{"t":$t,"deg":${deg ?: "null"},"p":$pts}"""
    }
    private fun round3(v: Float) = Math.round(v * 1000) / 1000.0
}

data class DialogLine(val t: Long, val who: String, val text: String) : JsonWritable {
    override fun toJson() = """{"t":$t,"who":${Json.str(who)},"text":${Json.str(text)}}"""
}

enum class Outcome(val label: String) {
    PENDING("處理中"),
    CANCELLED("長者表示沒事，已取消"),
    ALERTED("無回應或求救，已發出警報"),
}

/**
 * 緊急事件（對應提案 三-4「緊急事件紀錄」與 4.8）：
 * 事件時間、最後正常活動時間、事件前 60 秒到事件後 120 秒骨架、事件當下照片、問答紀錄、長者資料。
 */
class Incident(
    val id: String,
    val deviceId: String,
    val reason: FallReason,
    val startMs: Long,
    val lastWellMs: Long?,
    val profile: String,
    val chassis: String,
    val frameW: Int,
    val frameH: Int,
    pre: List<PoseFrame>,
) : JsonWritable {
    val pre: List<PoseFrame> = pre.toList()
    val post = mutableListOf<PoseFrame>()
    val dialog = mutableListOf<DialogLine>()
    var outcome = Outcome.PENDING
    var endMs: Long? = null
    var resolvedMs: Long? = null
    var resolvedBy: String? = null
    var snapshotPath: String? = null

    fun say(who: String, text: String, now: Long) { dialog += DialogLine(now, who, text) }

    fun close(o: Outcome, now: Long) { outcome = o; endMs = now }

    override fun toJson(): String = Json.value(
        linkedMapOf(
            "schema" to "neara.incident.v1",
            "id" to id,
            "deviceId" to deviceId,
            "reason" to reason.name,
            "reasonLabel" to reason.label,
            "start" to startMs,
            "lastWell" to lastWellMs,
            "outcome" to outcome.name,
            "outcomeLabel" to outcome.label,
            "end" to endMs,
            "resolved" to resolvedMs,
            "resolvedBy" to resolvedBy,
            "profile" to profile,
            "chassis" to chassis,
            "frameW" to frameW,
            "frameH" to frameH,
            "snapshotPath" to snapshotPath,
            "dialog" to dialog,
            "pre" to pre,
            "post" to post,
        )
    )

    companion object {
        const val PRE_MS = 60_000L
        const val POST_MS = 120_000L
        fun newId(deviceId: String, now: Long): String = "NEARA-${deviceId.takeLast(6)}-$now"
    }
}

/** 骨架環形緩衝：一直保留最近 60 秒，事件發生時把它複製進事件紀錄。每秒最多 10 筆。 */
class PoseRing(private val keepMs: Long = Incident.PRE_MS, private val minGapMs: Long = 100) {
    private val buf = ArrayDeque<PoseFrame>()
    private var lastT = 0L
    fun push(f: PoseFrame): Boolean {
        if (f.t - lastT < minGapMs) return false
        lastT = f.t
        buf.addLast(f)
        while (buf.isNotEmpty() && f.t - buf.first().t > keepMs) buf.removeFirst()
        return true
    }
    fun snapshot(): List<PoseFrame> = buf.toList()
    val size get() = buf.size
}
