package tw.neara.robot

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tw.neara.core.Incident
import tw.neara.core.Json
import tw.neara.core.Lines
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 雲端後端用戶端（規格見 docs/api.md）。機器人是 IoT 裝置：用 HTTPS＋裝置金鑰，不用 Firebase SDK。
 * 後端網址沒設定時，一律只存本機（事件檔案在 App 的 files/incidents/）。
 */
class Cloud(private val prefs: Prefs, private val filesDir: File) {
    val enabled get() = prefs.backendUrl.isNotBlank()
    var lastError: String? = null; private set

    private fun req(method: String, path: String, body: String?, auth: Boolean = true): JSONObject {
        val url = URL(prefs.backendUrl.trimEnd('/') + path)
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = if (method == "PATCH") "POST" else method
        if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (auth) c.setRequestProperty("Authorization", "Device ${prefs.deviceId}:${prefs.deviceKey ?: ""}")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "{}"
        if (code !in 200..299) throw IllegalStateException("後端錯誤 $code：$text")
        return JSONObject(text)
    }

    /** 第一次開機向後端註冊，拿到裝置金鑰與配對碼。 */
    suspend fun ensureRegistered(): String? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        runCatching {
            if (prefs.deviceKey == null) {
                val r = req("POST", "/api/device/register", Json.value(mapOf("deviceId" to prefs.deviceId, "model" to android.os.Build.MODEL, "appVersion" to BuildConfig.VERSION_NAME)), auth = false)
                prefs.deviceKey = r.getString("deviceKey")
                r.optString("pairingCode")
            } else {
                req("POST", "/api/device/pairing-code", "{}").optString("pairingCode")
            }
        }.onFailure { lastError = it.message }.getOrNull()
    }

    /** 心跳：回報狀態，順便拿家屬在 App 改的設定（稱呼、長者資料）。 */
    /** 回傳一小時內被家屬或守護中心「遠端結案」的事件 ID。 */
    suspend fun heartbeat(state: String, chassis: String): List<String> = withContext(Dispatchers.IO) {
        if (!enabled || prefs.deviceKey == null) return@withContext emptyList()
        runCatching {
            val r = req("POST", "/api/device/heartbeat", Json.value(mapOf("state" to state, "chassis" to chassis, "appVersion" to BuildConfig.VERSION_NAME)))
            r.optJSONObject("config")?.let { cfg ->
                cfg.optString("elderName").takeIf { it.isNotBlank() }?.let { prefs.elderName = it }
                if (cfg.has("profile")) prefs.profile = cfg.optString("profile")
            }
            val ids = r.optJSONArray("resolvedIncidents")
            List(ids?.length() ?: 0) { ids!!.getString(it) }
        }.onFailure { lastError = it.message }.getOrDefault(emptyList())
    }

    /** 事件先存本機，再上傳；上傳失敗會留在 pending，下次心跳時補傳。 */
    suspend fun saveIncident(inc: Incident, snapshotJpeg: ByteArray?, isNew: Boolean) = withContext(Dispatchers.IO) {
        val dir = File(filesDir, "incidents").apply { mkdirs() }
        File(dir, "${inc.id}.json").writeText(inc.toJson())
        if (snapshotJpeg != null) File(dir, "${inc.id}.jpg").writeBytes(snapshotJpeg)
        if (!enabled || prefs.deviceKey == null) return@withContext
        val ok = runCatching { upload(inc, snapshotJpeg, isNew) }.onFailure { lastError = it.message }.isSuccess
        val flag = File(dir, "${inc.id}.pending")
        if (ok) flag.delete() else flag.writeText(if (isNew) "new" else "update")
    }

    private fun upload(inc: Incident, jpeg: ByteArray?, isNew: Boolean) {
        val body = JSONObject(inc.toJson())
        if (isNew && jpeg != null) body.put("snapshotJpegBase64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        if (isNew) req("POST", "/api/device/incidents", body.toString())
        else req("PATCH", "/api/device/incidents/${inc.id}", body.toString())
    }

    /** 補傳先前失敗的事件。 */
    suspend fun retryPending() = withContext(Dispatchers.IO) {
        if (!enabled || prefs.deviceKey == null) return@withContext
        val dir = File(filesDir, "incidents")
        dir.listFiles { f -> f.name.endsWith(".pending") }?.forEach { flag ->
            val id = flag.name.removeSuffix(".pending")
            val json = File(dir, "$id.json").takeIf { it.exists() }?.readText() ?: return@forEach
            val jpg = File(dir, "$id.jpg").takeIf { it.exists() }?.readBytes()
            runCatching {
                val body = JSONObject(json)
                val isNew = flag.readText() == "new"
                if (isNew && jpg != null) body.put("snapshotJpegBase64", Base64.encodeToString(jpg, Base64.NO_WRAP))
                if (isNew) req("POST", "/api/device/incidents", body.toString())
                else req("PATCH", "/api/device/incidents/$id", body.toString())
            }.onSuccess { flag.delete() }.onFailure { lastError = it.message }
        }
    }

    /** AI 聊天：有後端就走後端（金鑰不在機器人上）；沒有後端才用本機設定的 Gemini 金鑰。 */
    suspend fun chat(name: String, history: List<Pair<String, String>>): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (enabled && prefs.deviceKey != null) {
                val h = JSONArray(); history.forEach { (role, text) -> h.put(JSONObject().put("role", role).put("text", text)) }
                req("POST", "/api/device/chat", JSONObject().put("name", name).put("history", h).toString()).optString("reply").ifBlank { null }
            } else if (prefs.geminiKey.isNotBlank()) gemini(name, history) else null
        }.onFailure { lastError = it.message }.getOrNull()
    }

    private fun gemini(name: String, history: List<Pair<String, String>>): String? {
        val models = listOf(prefs.geminiModel, "gemini-flash-lite-latest", "gemini-flash-latest").distinct()
        for (m in models) {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 20000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", prefs.geminiKey)
            val contents = JSONArray()
            history.forEach { (role, text) -> contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))) }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Lines.systemPrompt(name)))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("temperature", 0.8).put("maxOutputTokens", 1024))
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code == 404 || code == 400) continue
            if (code != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
            val sb = StringBuilder(); for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
            return sb.toString().replace(Regex("[*#>`]"), "").trim().ifBlank { null }
        }
        return null
    }
}
