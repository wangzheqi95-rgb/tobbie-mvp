package tw.neara.core

/**
 * 照護狀態（對應提案 4.4 告警狀態機，另加聊天與冷卻）：
 *  MONITOR 監測 → NAVIGATE 前往長者 → CONFIRM 開口確認 → ALERT 警報 / CANCELLED 取消 → COOLDOWN 冷卻 → MONITOR
 *  CHAT 主動關心聊天：偵測到跌倒或聽到緊急詞時，立刻中斷並進入 NAVIGATE。
 */
enum class CareState(val label: String) {
    IDLE("未啟動"),
    MONITOR("監測中"),
    SUSPECT("疑似跌倒"),
    CHAT("聊天中"),
    NAVIGATE("前往長者身邊"),
    CONFIRM("詢問中"),
    ALERT("警報中"),
    COOLDOWN("冷卻中"),
}

enum class Answer { OK, HELP, NONE }

/** 長者回答的判讀（規則式，本機執行，不交給 AI；對應提案 4.5「回應分類」）。 */
object Replies {
    val OK_WORDS = listOf("沒事", "我很好", "還好", "不用", "沒關係", "我沒事", "OK", "ok", "好得很")
    val HELP_WORDS = listOf("救命", "幫忙", "幫我", "好痛", "很痛", "不好", "起不來", "救護車")
    val EMERGENCY_WORDS = listOf("救命", "跌倒", "好痛", "很痛", "起不來", "救護車", "胸口痛", "頭好暈", "喘不過氣")
    val BYE_WORDS = listOf("掰掰", "拜拜", "再見", "不聊了", "先這樣", "我要睡了")

    fun classify(text: String?): Answer {
        if (text.isNullOrBlank()) return Answer.NONE
        val help = HELP_WORDS.any { text.contains(it) }
        if (help && !text.contains("不用")) return Answer.HELP
        if (OK_WORDS.any { text.contains(it) }) return Answer.OK
        if (help) return Answer.HELP
        return Answer.NONE
    }

    fun isEmergency(text: String) = EMERGENCY_WORDS.any { text.contains(it) }
    fun isBye(text: String) = BYE_WORDS.any { text.contains(it) }
}

/** 機器人說的台詞（親切口語，{name} 會換成稱呼）。 */
object Lines {
    fun ask1(name: String, reason: FallReason) =
        if (reason == FallReason.VANISH) "${name}，我突然看不到您了，您還好嗎？" else "${name}，您還好嗎？是不是跌倒了？"
    fun ask2(name: String) = "${name}，聽得到我嗎？沒事的話，跟我說「沒事」就好喔。"
    const val RELIEF = "那就好～慢慢起來，不要急喔。"
    fun alarm(name: String) = "${name}別擔心，我已經通知您的家人了。先不要亂動，我在這裡陪您。"
    fun greet(name: String, hour: Int): String {
        val hi = when { hour < 11 -> "早安"; hour < 18 -> "午安"; else -> "晚安" }
        return listOf("${name}，${hi}呀！今天過得怎麼樣？", "${name}，${hi}～有沒有記得喝水呀？", "${name}，${hi}！今天身體還舒服嗎？").random()
    }
    const val CHAT_NO_REPLY = "好喔，那我先不吵您，有需要再叫我～"
    const val CHAT_BYE = "好～那我們等等再聊，掰掰！"
    val CANNED = listOf("嗯嗯，我有在聽喔。然後呢？", "這樣啊～那您現在感覺怎麼樣？", "真好～還有什麼想跟我分享的嗎？")

    fun systemPrompt(name: String) = """你是一台住在長輩家裡的居家陪伴機器人，正在跟「$name」聊天。
請用台灣日常口語的中文，語氣溫暖親切，像孫子孫女在聊天。
每次回覆一到兩句、不超過 40 個字，常常用一個簡單的問題結尾，讓長輩願意繼續說。
不要使用表情符號、條列、英文或括號說明。
不要做醫療診斷；如果長輩說身體不舒服，溫柔關心，並建議告訴家人或去看醫生。"""
}

/**
 * 警報時機器人用自己的 SIM 卡撥號給家人（規則跟後端推播升級一樣）：
 * 一進入警報就撥第 1 位；之後每 3 分鐘還沒有人在 App 按「我來處理」，就撥下一位；
 * 有人接手、或清單撥完，就不再撥。
 */
class CallPlan(private val size: Int, private val gapMs: Long = 180_000) {
    private var next = 0
    private var lastAt = 0L

    /** 現在要撥清單上的第幾位（null＝現在不用撥）。 */
    fun due(now: Long, acked: Boolean): Int? {
        if (acked || next >= size) return null
        if (next > 0 && now - lastAt < gapMs) return null
        lastAt = now
        return next++
    }
}

/** 家人接手後，機器人對長輩說的話。 */
fun ackLine(name: String, by: String) = "${name}，${by}已經收到通知，正在處理了，我會陪著您。"

/** 表情臉的表情（對應設計稿：平常青綠、聊天開心、詢問琥珀、警報紅）。 */
enum class Mood { SLEEP, CALM, HAPPY, WORRIED, ALARM }

fun moodFor(s: CareState): Mood = when (s) {
    CareState.IDLE -> Mood.SLEEP
    CareState.MONITOR, CareState.COOLDOWN -> Mood.CALM
    CareState.CHAT -> Mood.HAPPY
    CareState.SUSPECT, CareState.NAVIGATE, CareState.CONFIRM -> Mood.WORRIED
    CareState.ALERT -> Mood.ALARM
}

/** 表情臉下方兩顆大按鈕要顯示什麼（左、右）。 */
enum class FaceButton(val label: String) {
    CHAT("聊天"), END_CHAT("不聊了"), OK("我沒事"), HELP("我需要幫忙"), CALL_119("打 119"), OK_NOW("我沒事了")
}

fun buttonsFor(s: CareState): Pair<FaceButton, FaceButton> = when (s) {
    CareState.CHAT -> FaceButton.END_CHAT to FaceButton.HELP
    CareState.SUSPECT, CareState.NAVIGATE, CareState.CONFIRM -> FaceButton.OK to FaceButton.HELP
    CareState.ALERT -> FaceButton.CALL_119 to FaceButton.OK_NOW
    else -> FaceButton.CHAT to FaceButton.HELP
}

/** 平常狀態畫面上的話。 */
fun idleCaption(name: String, hour: Int): String {
    val hi = when { hour < 5 -> "晚安"; hour < 11 -> "早安"; hour < 18 -> "午安"; else -> "晚安" }
    return "${name}，${hi}。\n需要我就按「聊天」。"
}
