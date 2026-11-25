package tw.neara.robot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import tw.neara.core.Pose

/** 在相機畫面上畫骨架（與 PreviewView 的 fitCenter 對齊）。 */
class OverlayView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {
    private var poses: List<Pose> = emptyList()
    private var imgW = 1; private var imgH = 1
    var mirror = false
    private val line = Paint().apply { color = Color.parseColor("#4F8CFF"); strokeWidth = 6f; isAntiAlias = true }
    private val dot = Paint().apply { color = Color.WHITE; isAntiAlias = true }

    fun show(p: List<Pose>, w: Int, h: Int) { poses = p; imgW = w; imgH = h; postInvalidate() }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (poses.isEmpty()) return
        val s = minOf(width.toFloat() / imgW, height.toFloat() / imgH)
        val ox = (width - imgW * s) / 2; val oy = (height - imgH * s) / 2
        fun px(x: Float) = ox + (if (mirror) 1 - x else x) * imgW * s
        fun py(y: Float) = oy + y * imgH * s
        for (p in poses) {
            if (p.size < 33) continue
            for ((a, b) in CONNECTIONS) c.drawLine(px(p[a].x), py(p[a].y), px(p[b].x), py(p[b].y), line)
            for (i in 11..32) c.drawCircle(px(p[i].x), py(p[i].y), 5f, dot)
        }
    }

    companion object {
        val CONNECTIONS = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24,
            23 to 25, 25 to 27, 24 to 26, 26 to 28, 27 to 31, 28 to 32, 0 to 11, 0 to 12)
    }
}
