package tw.neara.robot

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import tw.neara.core.MiioCodec

/**
 * 設定頁（由安裝人員或家屬操作）。用程式產生畫面，欄位一目了然、好維護。
 * 部分設定（稱呼、長者資料）之後會由家屬 App 透過雲端同步覆蓋。
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val saves = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(col) })

        fun title(t: String) = col.addView(TextView(this).apply { text = t; textSize = 18f; setPadding(0, 32, 0, 8) })
        fun hint(t: String) = col.addView(TextView(this).apply { text = t; textSize = 12f; alpha = 0.7f })
        fun text(label: String, get: () -> String, set: (String) -> Unit, type: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1) {
            col.addView(TextView(this).apply { this.text = label })
            val e = EditText(this).apply { setText(get()); inputType = type; if (lines > 1) { minLines = lines; inputType = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE } }
            col.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            saves += { set(e.text.toString().trim()) }
        }
        fun num(label: String, get: () -> Number, set: (String) -> Unit) =
            text(label, { get().toString() }, set, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val c = CheckBox(this).apply { text = label; isChecked = get() }
            col.addView(c); saves += { set(c.isChecked) }
        }

        title("長者")
        text("稱呼", { prefs.elderName }, { prefs.elderName = it })
        text("基本資料、慣用藥物、過敏、緊急聯絡人（印在事件報告上）", { prefs.profile }, { prefs.profile = it }, lines = 4)

        title("雲端後端")
        text("後端網址（Cloud Functions）", { prefs.backendUrl }, { prefs.backendUrl = it }, InputType.TYPE_TEXT_VARIATION_URI)
        hint("裝置 ID：${prefs.deviceId}")

        title("底盤")
        col.addView(TextView(this).apply { text = "底盤種類" })
        val kinds = listOf("none" to "無底盤（只用手機）", "s6" to "石頭 S6（區網控制）", "tobbie" to "Tobbie II（藍牙）")
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, kinds.map { it.second })
            setSelection(kinds.indexOfFirst { it.first == prefs.chassis }.coerceAtLeast(0))
        }
        col.addView(sp); saves += { prefs.chassis = kinds[sp.selectedItemPosition].first }
        text("石頭 S6 的 IP", { prefs.s6Ip }, { prefs.s6Ip = it }, InputType.TYPE_CLASS_TEXT)
        text("石頭 S6 金鑰（32 位）", { prefs.s6Token }, { prefs.s6Token = it }, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        num("轉向時間（毫秒）", { prefs.turnMs }, { prefs.turnMs = it.toIntOrNull() ?: 800 })
        num("跌倒時靠近長者（毫秒，0＝不靠近，上限 3000）", { prefs.approachMs }, { prefs.approachMs = (it.toIntOrNull() ?: 1000).coerceIn(0, 3000) })
        check("左右對調（轉錯方向時勾選）", { prefs.swapLR }, { prefs.swapLR = it })
        check("使用前鏡頭", { prefs.useFrontCamera }, { prefs.useFrontCamera = it })

        title("偵測")
        num("躺平持續幾秒才確認", { prefs.confirmSec }, { prefs.confirmSec = it.toFloatOrNull() ?: 3f })
        check("必須是快速倒下才算跌倒", { prefs.requireFastFall }, { prefs.requireFastFall = it })

        title("對話與聲音")
        num("主動關心間隔（分鐘）", { prefs.chatGapMin }, { prefs.chatGapMin = it.toIntOrNull() ?: 30 })
        num("語速（0.6–1.2）", { prefs.speechRate }, { prefs.speechRate = (it.toFloatOrNull() ?: 0.9f).coerceIn(0.6f, 1.2f) })
        num("音調（0.8–1.5）", { prefs.speechPitch }, { prefs.speechPitch = (it.toFloatOrNull() ?: 1.1f).coerceIn(0.8f, 1.5f) })
        text("Gemini 金鑰（只在沒有雲端後端時使用）", { prefs.geminiKey }, { prefs.geminiKey = it }, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        title("專用裝置")
        check("螢幕固定（長者無法跳出 App）", { prefs.kioskLock }, { prefs.kioskLock = it })
        hint("要開機直接進入：系統設定 → 應用程式 → 預設應用程式 → 主畫面，選「Neara 照護機器人」。")

        col.addView(Button(this).apply {
            text = "儲存"
            setOnClickListener {
                saves.forEach { it() }
                if (prefs.chassis == "s6" && runCatching { MiioCodec.tokenFromHex(prefs.s6Token) }.isFailure) {
                    Toast.makeText(this@SettingsActivity, "S6 金鑰格式不對（要 32 位十六進位）", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                Toast.makeText(this@SettingsActivity, "已儲存，部分設定重開 App 後生效", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
    }
}
