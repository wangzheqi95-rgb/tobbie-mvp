package tw.neara.robot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import tw.neara.core.Mood
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 機器人的表情臉（照設計稿）：簡單的圓眼睛＋嘴巴，用畫的，不用圖片。
 *  CALM 平常：青綠直立橢圓眼、會眨眼、微笑
 *  HAPPY 聊天：彎彎的笑眼，說話時嘴巴一開一合
 *  WORRIED 詢問：琥珀色圓眼、小圓嘴
 *  ALARM 警報：白色圓眼、一字嘴
 *  SLEEP 未啟動：閉眼
 */
class FaceView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var mood: Mood = Mood.CALM
        set(v) { field = v; invalidate() }

    /** 正在說話（嘴巴動）。可從任何執行緒設定。 */
    @Volatile var talking = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private val start = System.currentTimeMillis()

    private fun colorOf(m: Mood) = when (m) {
        Mood.WORRIED -> Color.parseColor("#F7D58C")
        Mood.ALARM -> Color.WHITE
        else -> Color.parseColor("#7FE0CC")
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val t = System.currentTimeMillis() - start
        val unit = min(w, h * 1.1f) / 6f          // 依畫面大小縮放
        val cx = w / 2; val cy = h * 0.45f
        val gap = unit * 1.15f                     // 兩眼中心到臉中心的距離
        val col = colorOf(mood)
        fill.color = col; stroke.color = col; stroke.strokeWidth = unit * 0.18f

        for (side in listOf(-1, 1)) {
            val ex = cx + side * gap
            when (mood) {
                Mood.CALM -> {
                    // 每 5 秒眨一次眼（0.15 秒）
                    val blink = if (t % 5000 > 4850) 0.12f else 1f
                    val ew = unit * 0.75f; val eh = unit * 1.0f * blink
                    r.set(ex - ew / 2, cy - eh / 2, ex + ew / 2, cy + eh / 2)
                    c.drawRoundRect(r, ew / 2, ew / 2 * blink, fill)
                }
                Mood.HAPPY -> {
                    r.set(ex - unit * 0.5f, cy - unit * 0.25f, ex + unit * 0.5f, cy + unit * 0.75f)
                    c.drawArc(r, 200f, 140f, false, stroke)
                }
                Mood.WORRIED, Mood.ALARM -> c.drawCircle(ex, cy, unit * 0.42f, fill)
                Mood.SLEEP -> c.drawLine(ex - unit * 0.4f, cy, ex + unit * 0.4f, cy, stroke)
            }
        }

        val my = cy + unit * 1.35f
        when (mood) {
            Mood.CALM, Mood.SLEEP -> {
                r.set(cx - unit * 0.55f, my - unit * 0.45f, cx + unit * 0.55f, my + unit * 0.2f)
                c.drawArc(r, 20f, 140f, false, stroke)
            }
            Mood.HAPPY -> {
                // 說話時嘴巴高度在 30%～100% 之間擺動
                val open = if (talking) 0.3f + 0.7f * abs(sin(t / 120.0)).toFloat() else 0.55f
                r.set(cx - unit * 0.45f, my - unit * 0.25f, cx + unit * 0.45f, my - unit * 0.25f + unit * 0.6f * open)
                c.drawArc(r, 0f, 180f, true, fill)
            }
            Mood.WORRIED -> c.drawCircle(cx, my, unit * 0.22f, stroke)
            Mood.ALARM -> c.drawLine(cx - unit * 0.5f, my, cx + unit * 0.5f, my, stroke)
        }
        // 持續重畫（眨眼、說話）；畫面很簡單，耗電可忽略
        if (mood == Mood.CALM || mood == Mood.HAPPY) postInvalidateOnAnimation()
    }
}
