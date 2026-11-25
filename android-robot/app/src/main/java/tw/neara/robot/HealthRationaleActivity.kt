package tw.neara.robot

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Health Connect 授權畫面上「為什麼需要這些資料」的說明頁（只顯示文字，不含任何設定）。 */
class HealthRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setPadding(48, 48, 48, 48); textSize = 17f
            text = "Neara 照護機器人只讀取手錶的「心率」與「步數」，不會寫入或讀取其他健康資料。\n\n" +
                "用途：\n• 長輩可能跌倒時，把事件前後的心率附在事件報告上，給家人與醫師參考。\n" +
                "• 每日平安摘要顯示今天走了幾步（家人自己開啟才會發送）。\n\n" +
                "資料只傳到 Neara 雲端，心率只保留最近 2 小時，事件報告保存 90 天後自動刪除。" +
                "可以隨時在 Health Connect 取消授權。"
        })
    }
}
