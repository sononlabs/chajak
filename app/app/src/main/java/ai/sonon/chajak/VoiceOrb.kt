package ai.sonon.chajak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 목소리에 반응하는 원(2026-11-10 사용자: 음성 인식 표시를 화면 가운데에 — ProtoPie 음성 반응·audio-reactive 참고).
 * 가운데 원 + 퍼지는 고리 3개. 소리가 크면 원이 커지고 고리가 밖으로 퍼진다. 조용하면 천천히 숨 쉬듯 움직인다.
 * level(dBFS)을 넣으면 커질 땐 바로, 작아질 땐 천천히 따라간다. 주행 중엔 calm=true 로 움직임을 줄인다.
 */
class VoiceOrb(context: Context) : View(context) {
    var color: Int = Color.parseColor("#3D7BFF")
    var calm = false
    private var shown = 0f      // 0..1 화면에 보이는 크기
    private var target = 0f
    private val start = System.nanoTime()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** -60dB(조용) ~ -15dB(큼) → 0..1. */
    fun level(db: Double) {
        target = ((db + 60) / 45).toFloat().coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val t = (System.nanoTime() - start) / 1e9f
        shown += (target - shown) * (if (target > shown) 0.45f else 0.08f)
        val cx = width / 2f
        val cy = height / 2f
        val r0 = min(width, height) * 0.16f
        val breathe = if (calm) 0f else 0.04f * sin(t * 2.2f)
        val r = r0 * (1f + breathe + shown * 0.55f)
        // 퍼지는 고리 3개: 시간에 따라 밖으로, 소리가 클수록 진하게
        for (i in 0 until 3) {
            val phase = ((t * (if (calm) 0.25f else 0.5f) + i / 3f) % 1f)
            val rr = r * (1.15f + phase * (0.9f + shown * 1.2f))
            ring.color = color
            ring.alpha = (max(0f, 1f - phase) * (40 + 140 * shown)).toInt().coerceIn(0, 255)
            ring.strokeWidth = r0 * (0.06f + 0.06f * shown)
            canvas.drawCircle(cx, cy, rr, ring)
        }
        // 부드러운 바깥 빛
        fill.color = color
        fill.alpha = (30 + 60 * shown).toInt()
        canvas.drawCircle(cx, cy, r * 1.25f, fill)
        fill.alpha = 255
        canvas.drawCircle(cx, cy, r, fill)
        postInvalidateOnAnimation()
    }
}
