package tw.neara.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 小米 miIO 區網協定（UDP 54321）。石頭 S6 等小米生態裝置都用它在區網接收指令。
 *
 * 封包 = 32 bytes 標頭 + 加密的 JSON：
 *   0x2131 | 總長度(2) | 0x00000000 | 裝置 ID(4) | 時間戳(4) | 校驗碼 MD5(16)
 * 加密：AES-128-CBC，key = MD5(token)，iv = MD5(key + token)，PKCS7 補位。
 * 校驗碼 = MD5(標頭前 16 bytes + token + 加密後內容)。
 * 實作對照 python-miio（miio/protocol.py），並以單元測試交叉驗證。
 */
class MiioCodec(private val token: ByteArray) {
    init { require(token.size == 16) { "金鑰必須是 32 位十六進位（16 bytes）" } }

    private val aesKey = md5(token)
    private val aesIv = md5(aesKey + token)

    fun encrypt(plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(plain)
    fun decrypt(enc: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE).doFinal(enc)

    // 注意：不要在 Cipher.apply { } 裡直接寫 iv —— 會被解讀成 Cipher 自己的 getIV()（初始化前是 null）
    private fun cipher(mode: Int): Cipher {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(mode, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        return c
    }

    /** 組一個指令封包。json 會自動加上結尾 \0（與官方 App、python-miio 相同）。 */
    fun encode(deviceId: ByteArray, stamp: Long, json: String): ByteArray {
        require(deviceId.size == 4)
        val payload = encrypt(json.toByteArray(Charsets.UTF_8) + byteArrayOf(0))
        val buf = ByteBuffer.allocate(32 + payload.size)
        buf.putShort(0x2131.toShort())
        buf.putShort((32 + payload.size).toShort())
        buf.putInt(0)
        buf.put(deviceId)
        buf.putInt(stamp.toInt())
        val headerHead = buf.array().copyOfRange(0, 16)
        buf.put(md5(headerHead + token + payload))
        buf.put(payload)
        return buf.array()
    }

    data class Decoded(val deviceId: ByteArray, val stamp: Long, val json: String?)

    /** 解開裝置回覆的封包；校驗碼不符會丟例外（代表金鑰錯誤或封包損毀）。 */
    fun decode(packet: ByteArray): Decoded {
        val h = parseHeader(packet)
        val payload = packet.copyOfRange(32, packet.size)
        if (payload.isEmpty()) return Decoded(h.deviceId, h.stamp, null)
        val expect = md5(packet.copyOfRange(0, 16) + token + payload)
        val got = packet.copyOfRange(16, 32)
        if (!expect.contentEquals(got)) throw MiioException("校驗碼不符：金鑰可能錯誤")
        val plain = decrypt(payload).dropLastWhile { it == 0.toByte() }.toByteArray()
        return Decoded(h.deviceId, h.stamp, String(plain, Charsets.UTF_8))
    }

    companion object {
        /** 交握（hello）封包：問裝置的 ID 與目前時間戳。 */
        val HELLO: ByteArray = ByteArray(32) { 0xFF.toByte() }.also {
            it[0] = 0x21; it[1] = 0x31; it[2] = 0x00; it[3] = 0x20
        }

        data class Header(val length: Int, val deviceId: ByteArray, val stamp: Long)

        fun parseHeader(packet: ByteArray): Header {
            if (packet.size < 32) throw MiioException("封包太短")
            val b = ByteBuffer.wrap(packet)
            if (b.short != 0x2131.toShort()) throw MiioException("不是 miIO 封包")
            val len = b.short.toInt() and 0xFFFF
            b.int
            val did = ByteArray(4).also { b.get(it) }
            val stamp = b.int.toLong() and 0xFFFFFFFFL
            return Header(len, did, stamp)
        }

        fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

        fun tokenFromHex(hex: String): ByteArray {
            val h = hex.trim().lowercase()
            require(h.length == 32 && h.all { it in "0123456789abcdef" }) { "金鑰必須是 32 位十六進位" }
            return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}

class MiioException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/**
 * 區網 miIO 用戶端：交握 → 送指令 → 等回覆（id 相符）→ 失敗自動重試。
 * 用 java.net，Android 與一般 JVM 都能跑；所有方法都是阻塞式，請在背景執行緒呼叫。
 */
class MiioClient(
    private val ip: String,
    token: ByteArray,
    private val port: Int = 54321,
    private val timeoutMs: Int = 3000,
) {
    private val codec = MiioCodec(token)
    private var deviceId: ByteArray? = null
    private var deviceStamp = 0L
    private var handshakeAt = 0L
    private var nextId = (1..9000).random()

    @Synchronized
    fun handshake() {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(MiioCodec.HELLO, 32, InetAddress.getByName(ip), port))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val h = MiioCodec.parseHeader(buf.copyOf(p.length))
            deviceId = h.deviceId
            deviceStamp = h.stamp
            handshakeAt = System.currentTimeMillis()
        }
    }

    /** 送出指令並回傳回覆 JSON 字串（例如 {"id":12,"result":["ok"]}）。 */
    @Synchronized
    fun send(method: String, paramsJson: String = "[]", retries: Int = 2): String {
        var last: Exception? = null
        repeat(retries + 1) { attempt ->
            try {
                if (deviceId == null || System.currentTimeMillis() - handshakeAt > 60_000) handshake()
                val id = nextId++
                val stamp = deviceStamp + (System.currentTimeMillis() - handshakeAt) / 1000 + 1
                val req = """{"id":$id,"method":"${Json.esc(method)}","params":$paramsJson}"""
                val pkt = codec.encode(deviceId!!, stamp, req)
                DatagramSocket().use { s ->
                    s.soTimeout = timeoutMs
                    s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(ip), port))
                    val buf = ByteArray(8192)
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val d = codec.decode(buf.copyOf(p.length))
                        val json = d.json ?: continue
                        if (json.contains("\"id\":$id") || json.contains("\"id\": $id")) {
                            if (json.contains("\"error\"")) throw MiioException("裝置回報錯誤：$json")
                            return json
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                last = MiioException("裝置沒有回應（第 ${attempt + 1} 次）", e)
                deviceId = null // 下次重新交握
                nextId += 100
            } catch (e: MiioException) {
                throw e
            } catch (e: Exception) {
                last = MiioException("通訊失敗：${e.message}", e)
                deviceId = null
            }
        }
        throw last ?: MiioException("未知錯誤")
    }
}

/**
 * 石頭 S6 的指令集（對照 python-miio RoborockVacuum）。
 * 安全限制：遙控速度上限 0.15 m/s、單次動作最長 3 秒，避免撞到倒地的長者。
 */
class RoborockS6(private val client: MiioClient) {
    private var seq = 0

    fun status(): String = client.send("get_status")
    fun pause() { client.send("app_pause") }
    fun dock() { client.send("app_charge") }
    fun findMe() { client.send("find_me", "[\"\"]") }

    /** 遙控一個動作：rotationDegPerSec 正值左轉、負值右轉；velocity 正值前進。 */
    fun remoteMove(rotationDegPerSec: Double, velocity: Double, durationMs: Int) {
        val v = velocity.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        val ms = durationMs.coerceIn(100, MAX_DURATION_MS)
        val omega = Math.round(Math.toRadians(rotationDegPerSec.coerceIn(-180.0, 180.0)) * 10) / 10.0
        client.send("app_rc_start")
        seq = 0
        try {
            Thread.sleep(300)
            seq += 1
            client.send("app_rc_move", """[{"omega":$omega,"velocity":$v,"duration":$ms,"seqnum":$seq}]""")
            Thread.sleep(ms + 200L)
        } finally {
            client.send("app_rc_end")
        }
    }

    companion object {
        const val MAX_VELOCITY = 0.15
        const val MAX_DURATION_MS = 3000
        const val TURN_DEG_PER_SEC = 60.0
    }
}
