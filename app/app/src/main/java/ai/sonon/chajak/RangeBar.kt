package ai.sonon.chajak

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * 음역 막대: 가로로 C2~C6. 바탕은 낮은 음(파랑)→높은 음(주황) 옅은 띠, 아래에 C 음 눈금, 그 아래 맞는 목소리 유형 범위(괄호선).
 * range(지금까지 모인 음) 의 10~90% 를 색 띠로, 편한 음(25~75%)을 진하게, current(지금 내는 음)를 빛나는 점으로 그린다.
 */
@SuppressLint("ViewConstructor")
class RangeBar(context: Context, font: Typeface) : View(context) {

    var range: VoiceRange? = null
        set(v) { field = v; invalidate() }

    /** 지금 들리는 음(MIDI). null 이면 점을 안 그린다. 부드럽게 따라가도록 그릴 때 섞는다. */
    var current: Float? = null
        set(v) { field = v; invalidate() }
    private var shown: Float? = null

    init { setPadding((32 * resources.displayMetrics.density).toInt(), 0, (32 * resources.displayMetrics.density).toInt(), 0) }

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33131417"); strokeWidth = density * 2 }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font; textSize = 24 * density;  // Google AAOS 최소 24(이전 20)
        color = Color.parseColor("#A3131417"); textAlign = Paint.Align.CENTER
    }
    private val typeLabel = Paint(label).apply { textSize = 24 * density;  /* 이전 18 */ color = Color.parseColor("#80131417") }

    private fun x(m: Float) = paddingLeft + (m - VoiceRange.LOWEST) / (VoiceRange.HIGHEST - VoiceRange.LOWEST) * (width - paddingLeft - paddingRight)

    override fun onDraw(canvas: Canvas) {
        val barTop = 12 * density
        val barH = 40 * density
        val r = barH / 2
        val left = x(VoiceRange.LOWEST.toFloat())
        val right = x(VoiceRange.HIGHEST.toFloat())
        track.shader = LinearGradient(left, 0f, right, 0f,
            intArrayOf(alpha(VoiceRange.color(36f), 40), alpha(VoiceRange.color(60f), 40), alpha(VoiceRange.color(84f), 40)),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(left, barTop, right, barTop + barH), r, r, track)

        range?.takeIf { it.total > 0 }?.let { v ->
            val lo = v.low ?: return@let
            val hi = v.high ?: return@let
            val mid = (lo + hi) / 2f
            band.color = alpha(VoiceRange.color(mid), 110)
            canvas.drawRoundRect(RectF(x(lo - 0.5f), barTop + 4 * density, x(hi + 0.5f), barTop + barH - 4 * density), r, r, band)
            val cl = v.comfortLow ?: lo
            val ch = v.comfortHigh ?: hi
            band.color = alpha(VoiceRange.color(mid), 220)
            canvas.drawRoundRect(RectF(x(cl - 0.5f), barTop + 10 * density, x(ch + 0.5f), barTop + barH - 10 * density), r, r, band)
        }

        // C 음 눈금 + 이름, 그 아래 목소리 유형(유형 범위 가운데)
        for (m in VoiceRange.LOWEST..VoiceRange.HIGHEST step 12) {
            canvas.drawLine(x(m.toFloat()), barTop + barH + 4 * density, x(m.toFloat()), barTop + barH + 12 * density, tick)
            canvas.drawText(VoiceRange.name(m), x(m.toFloat()), barTop + barH + 40 * density, label)
        }
        range?.type()?.let { t ->
            val y = barTop + barH + 56 * density
            canvas.drawLine(x(t.lo.toFloat()), y, x(t.hi.toFloat()), y, tick)
            canvas.drawLine(x(t.lo.toFloat()), y - 6 * density, x(t.lo.toFloat()), y, tick)
            canvas.drawLine(x(t.hi.toFloat()), y - 6 * density, x(t.hi.toFloat()), y, tick)
            canvas.drawText("${t.name} 음역", x(t.center), y + 30 * density, typeLabel)
        }

        current?.let { target ->
            val s = shown?.let { it + (target - it) * 0.5f } ?: target
            shown = s
            val c = VoiceRange.color(s)
            glow.color = alpha(c, 70)
            dot.color = c
            canvas.drawCircle(x(s), barTop + r, r * 1.1f, glow)
            canvas.drawCircle(x(s), barTop + r, r * 0.55f, dot)
        } ?: run { shown = null }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (146 * density).toInt())
    }

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
