package tw.neara.robot

import android.content.Context
import java.util.UUID

/** 本機設定（只存在這台機器人上）。家屬在 App 改的設定會由雲端 config 覆蓋 elderName / profile。 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("neara", Context.MODE_PRIVATE)

    val deviceId: String
        get() = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("deviceId", it).apply() }

    var deviceKey: String? by str("deviceKey")
    var backendUrl: String by str("backendUrl", BuildConfig.DEFAULT_BACKEND_URL)
    var elderName: String by str("elderName", "阿嬤")
    var profile: String by str("profile", "")
    var chassis: String by str("chassis", "none")          // none / s6 / tobbie
    var s6Ip: String by str("s6Ip", "")
    var s6Token: String by str("s6Token", "")
    var geminiKey: String by str("geminiKey", "")           // 只在沒有雲端後端時使用；商用版改由後端代管
    var geminiModel: String by str("geminiModel", "gemini-flash-lite-latest")
    var turnMs: Int by int("turnMs", 800)
    var approachMs: Int by int("approachMs", 1000)
    var swapLR: Boolean by bool("swapLR", false)
    var useFrontCamera: Boolean by bool("frontCamera", false)
    var confirmSec: Float by float("confirmSec", 3f)
    var requireFastFall: Boolean by bool("fastFall", true)
    var chatGapMin: Int by int("chatGapMin", 30)
    var speechRate: Float by float("speechRate", 0.9f)
    var speechPitch: Float by float("speechPitch", 1.1f)
    var kioskLock: Boolean by bool("kioskLock", false)
    var healthConnect: Boolean by bool("healthConnect", false)  // 串接手錶（Health Connect）
    var healthLastSync: Long by long("healthLastSync", 0L)
    var autoCall: Boolean by bool("autoCall", true)             // 警報時用這支手機的 SIM 卡撥給家人
    var callListJson: String by str("callList", "[]")           // 後端心跳帶來的撥號清單（依監護人順序）
    var fcmToken: String? by str("fcmToken")                    // 視訊來電推播

    // ---- 小工具：用屬性代理讀寫 SharedPreferences
    private fun str(k: String, d: String) = object : kotlin.properties.ReadWriteProperty<Any, String> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = sp.getString(k, d) ?: d
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: String) { sp.edit().putString(k, value).apply() }
    }
    private fun str(k: String) = object : kotlin.properties.ReadWriteProperty<Any, String?> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = sp.getString(k, null)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: String?) { sp.edit().putString(k, value).apply() }
    }
    private fun int(k: String, d: Int) = object : kotlin.properties.ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = sp.getInt(k, d)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Int) { sp.edit().putInt(k, value).apply() }
    }
    private fun long(k: String, d: Long) = object : kotlin.properties.ReadWriteProperty<Any, Long> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = sp.getLong(k, d)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Long) { sp.edit().putLong(k, value).apply() }
    }
    private fun float(k: String, d: Float) = object : kotlin.properties.ReadWriteProperty<Any, Float> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = sp.getFloat(k, d)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Float) { sp.edit().putFloat(k, value).apply() }
    }
    private fun bool(k: String, d: Boolean) = object : kotlin.properties.ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = sp.getBoolean(k, d)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Boolean) { sp.edit().putBoolean(k, value).apply() }
    }
}
