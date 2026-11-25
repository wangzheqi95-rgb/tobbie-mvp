package tw.neara.core

/**
 * 核心邏輯測試（不需要 Android、不需要 JUnit，直接 kotlinc 編譯執行）：
 *   kotlinc core/src -include-runtime -d core-test.jar && java -cp core-test.jar tw.neara.core.CoreTestsKt
 * 另有 tools/miio_crosscheck.py 用 python-miio 交叉驗證 miIO 封包，與假裝置做端到端 UDP 測試。
 */
private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) "  ($detail)" else "")
    if (!ok) failures++
}

private fun pose(sh: Pair<Float, Float>, hp: Pair<Float, Float>, vis: Float = 0.9f): Pose {
    val lm = MutableList(33) { Landmark(0.3f, 0.5f, vis) }
    lm[11] = Landmark(sh.first - 0.03f, sh.second, vis); lm[12] = Landmark(sh.first + 0.03f, sh.second, vis)
    lm[23] = Landmark(hp.first - 0.03f, hp.second, vis); lm[24] = Landmark(hp.first + 0.03f, hp.second, vis)
    return lm
}
private val STAND = pose(0.25f to 0.30f, 0.25f to 0.60f)
private val LIE = pose(0.05f to 0.75f, 0.30f to 0.78f)
private val ASPECT = 4.0 / 3.0

/** 以 10 fps 餵資料，回傳第一個 Fall（若有）。 */
private fun feed(d: FallDetector, frames: List<Pair<Long, List<Pose>>>, armed: Boolean = true): Observation.Fall? {
    for ((t, ps) in frames) { val o = d.update(ps, t, ASPECT, armed); if (o is Observation.Fall) return o }
    return null
}
private fun seq(fromMs: Long, toMs: Long, ps: List<Pose>) = (fromMs until toMs step 100).map { it to ps }

fun main(args: Array<String>) {
    if (args.firstOrNull() == "miio-encode") return miioEncode(args)
    if (args.firstOrNull() == "miio-decode") return miioDecode(args)
    if (args.firstOrNull() == "miio-client") return miioClient(args)

    // ---- 回答判讀
    check("沒事 → OK", Replies.classify("我沒事啦") == Answer.OK)
    check("好痛 → HELP", Replies.classify("好痛起不來") == Answer.HELP)
    check("不用幫忙 → OK（含「不用」不算求救）", Replies.classify("不用幫忙") == Answer.OK)
    check("空白 → NONE", Replies.classify("") == Answer.NONE)
    check("緊急詞", Replies.isEmergency("我剛剛跌倒了") && !Replies.isEmergency("今天天氣很好"))

    // ---- 軀幹角度
    check("站姿角度 < 35°", measure(STAND, ASPECT, 0.3f)!!.deg < 35)
    check("躺姿角度 > 60°", measure(LIE, ASPECT, 0.3f)!!.deg > 60)
    check("可見度不足 → null", measure(pose(0.2f to 0.3f, 0.2f to 0.6f, 0.1f), ASPECT, 0.3f) == null)

    // ---- 規則一：快速倒下並躺 3 秒 → 跌倒
    run {
        val d = FallDetector()
        val f = feed(d, seq(0, 2000, listOf(STAND)) + seq(2000, 6000, listOf(LIE)))
        check("快速倒下 → Fall(LYING)", f?.reason == FallReason.LYING, "$f")
        check("最後正常活動時間 ≈ 1900", d.lastWellMs == 1900L, "${d.lastWellMs}")
    }
    // ---- 慢慢躺下（站姿結束 10 秒後才躺）→ 不算
    run {
        val d = FallDetector()
        val f = feed(d, seq(0, 1000, listOf(STAND)) + seq(1000, 11000, emptyList()) + seq(11000, 16000, listOf(LIE)))
        check("慢慢躺下 → 不判定", f == null, "$f")
    }
    // ---- 未啟動（armed=false）→ 不判定
    run {
        val d = FallDetector()
        val f = feed(d, seq(0, 2000, listOf(STAND)) + seq(2000, 6000, listOf(LIE)), armed = false)
        check("詢問或冷卻中 → 不重複判定", f == null)
    }
    // ---- 規則二：往下掉後被遮住消失 → 跌倒
    run {
        val d = FallDetector()
        val falling = (0 until 5).map { i -> pose(0.25f to (0.30f + i * 0.08f), 0.25f to (0.60f + i * 0.06f)) }
        val frames = seq(0, 2000, listOf(STAND)) + falling.mapIndexed { i, p -> (2000L + i * 100) to listOf(p) } + seq(2500, 7000, emptyList())
        val f = feed(d, frames)
        check("往下掉後消失 3 秒 → Fall(VANISH)", f?.reason == FallReason.VANISH, "$f")
    }
    // ---- 站著走出畫面 → 不算
    run {
        val d = FallDetector()
        val f = feed(d, seq(0, 2000, listOf(STAND)) + seq(2000, 8000, emptyList()))
        check("直立走出畫面 → 不判定", f == null)
    }
    // ---- 鏡子倒影：一個站著、一個躺著 → 取最平躺的那個，但有人直立就不算快速倒下的起點延後
    run {
        val d = FallDetector()
        val f = feed(d, seq(0, 2000, listOf(STAND)) + seq(2000, 6000, listOf(LIE, STAND)))
        check("兩個人（含倒影）仍能判定躺平者", f?.reason == FallReason.LYING, "$f")
    }

    // ---- 骨架環形緩衝
    run {
        val r = PoseRing()
        for (t in 0L..70_000L step 50) r.push(PoseFrame(t, 0, null))
        val s = r.snapshot()
        check("環形緩衝只留 60 秒、每秒最多 10 筆", s.first().t >= 10_000 && s.size in 595..605, "first=${s.first().t} size=${s.size}")
    }

    // ---- 事件 JSON
    run {
        val inc = Incident("X", "dev1", FallReason.LYING, 1000, 900, "王\"阿嬤\"\n82歲", "s6", 640, 480,
            listOf(PoseFrame(950, 10, listOf(0.1f to 0.2f))))
        inc.say("機器人", "您還好嗎？", 1001); inc.close(Outcome.ALERTED, 1100)
        val j = inc.toJson()
        check("事件 JSON 含必要欄位", listOf("\"schema\":\"neara.incident.v1\"", "\"lastWell\":900", "\"outcome\":\"ALERTED\"", "\\\"阿嬤\\\"\\n82").all { j.contains(it) }, j.take(160))
    }

    // ---- miIO 加解密自我一致
    run {
        val tok = MiioCodec.tokenFromHex("00112233445566778899aabbccddeeff")
        val c = MiioCodec(tok)
        val pkt = c.encode(byteArrayOf(1, 2, 3, 4), 12345, """{"id":1,"method":"get_status","params":[]}""")
        val d = c.decode(pkt)
        check("miIO 封包可自行解回", d.json == """{"id":1,"method":"get_status","params":[]}""" && d.stamp == 12345L)
        val bad = pkt.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        check("miIO 校驗碼錯誤會被擋下", runCatching { c.decode(bad) }.isFailure)
        check("hello 封包格式", MiioCodec.HELLO.size == 32 && MiioCodec.HELLO[0] == 0x21.toByte() && MiioCodec.HELLO[3] == 0x20.toByte())
    }

    run {
        val p = CallPlan(3, gapMs = 180_000)
        check("撥號：一開始撥第 1 位", p.due(0, false) == 0)
        check("撥號：3 分鐘內不撥下一位", p.due(60_000, false) == null)
        check("撥號：3 分鐘到撥第 2 位", p.due(180_000, false) == 1)
        check("撥號：有人接手就停", p.due(400_000, true) == null)
        check("撥號：沒接手繼續第 3 位", p.due(400_000, false) == 2)
        check("撥號：清單撥完就停", p.due(999_999, false) == null)
        check("撥號：空清單不撥", CallPlan(0).due(0, false) == null)
        check("接手台詞", ackLine("阿嬤", "大明").contains("大明已經收到通知"))
    }

    println(if (failures == 0) "ALL PASS" else "$failures FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}

// ---- 給 python 交叉驗證用的指令列模式
private fun miioEncode(a: Array<String>) {
    val c = MiioCodec(MiioCodec.tokenFromHex(a[1]))
    val did = MiioCodec.tokenFromHex(a[2].padEnd(32, '0')).copyOf(4)
    println(c.encode(did, a[3].toLong(), a[4]).joinToString("") { "%02x".format(it) })
}
private fun miioDecode(a: Array<String>) {
    val c = MiioCodec(MiioCodec.tokenFromHex(a[1]))
    val bytes = ByteArray(a[2].length / 2) { a[2].substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    val d = c.decode(bytes)
    println("${d.stamp}|${d.json}")
}
private fun miioClient(a: Array<String>) {
    val s6 = RoborockS6(MiioClient(a[1], MiioCodec.tokenFromHex(a[2]), port = a[3].toInt(), timeoutMs = 2000))
    println("status=" + s6.status())
    s6.pause()
    s6.remoteMove(RoborockS6.TURN_DEG_PER_SEC, 0.0, 500)
    s6.remoteMove(0.0, 0.5, 9000) // 應被限制成 0.15 m/s、3000 ms
    s6.dock()
    s6.findMe()
    println("CLIENT DONE")
}
