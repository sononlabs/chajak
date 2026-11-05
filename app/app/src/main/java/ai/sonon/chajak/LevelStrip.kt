package ai.sonon.chajak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

/**
 * 녹음 중 파형(가운데 점선 → 소리 나면 막대). 50ms 마다 소리 크기 하나를 받아
 * 오른쪽 끝에 붙이고 왼쪽으로 흘린다. 막대 색은 그때의 음높이 색(목소리 아니면 회색).
 */
class LevelStrip(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val levels = ArrayDeque<Pair<Float, Int>>()
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33131417"); strokeWidth = 2 * density
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 6 * density), 0f)
    }

    /** db: dBFS(-60 조용 ~ -10 큼), color: 막대 색. */
    fun push(db: Double, color: Int) {
        levels.addLast(((db + 60) / 50).coerceIn(0.0, 1.0).toFloat() to color)
        while (levels.size > MAX) levels.removeFirst()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val mid = height / 2f
        canvas.drawLine(0f, mid, width.toFloat(), mid, line)
        val step = width / MAX.toFloat()
        bar.strokeWidth = step * 0.6f
        levels.forEachIndexed { i, (v, c) ->
            if (v < 0.04f) return@forEachIndexed
            val x = width - (levels.size - i) * step + step / 2
            val h = v * (height / 2f - bar.strokeWidth)
            bar.color = c
            canvas.drawLine(x, mid - h, x, mid + h, bar)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (120 * density).toInt())
    }

    companion object { private const val MAX = 120 }  // 6초
}
