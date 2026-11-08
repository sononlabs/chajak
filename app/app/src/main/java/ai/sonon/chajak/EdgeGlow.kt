package ai.sonon.chajak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 녹음 중 화면 가장자리 글로우(프로젝트 컴포넌트) — 화면을 힐끗 봐도 "지금 녹음 중"을 바로 알게 한다(2026-11-08 사용자 요청, 시리 켤 때의 모서리 빛 참고).
 *
 * - 네 가장자리 안쪽으로 따뜻한 색(녹음 빨강 · 코랄 · 앰버 · 핑크)이 번지고, 색이 가장자리를 따라 천천히 흐른다(6초 한 바퀴).
 * - 소리가 클수록 빛이 안쪽으로 더 깊고 진해진다(커질 땐 빨리, 작아질 땐 천천히). 조용해도 옅게 남아 녹음 중임을 계속 보여 준다.
 * - 운전 중 눈을 끌지 않게: 깜빡임 없음, 숨쉬기는 0.5Hz, 주행 중(calm)에는 색 흐름도 멈춘다. 터치·화면 낭독은 통과(장식).
 * - 두 그라디언트(가장자리를 따라 흐르는 색 × 안쪽으로 옅어지는 마스크)를 ComposeShader(DST_IN)로 합쳐 그린다 — 흐림 필터 없이 GPU 로.
 */
class EdgeGlow(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shift = Matrix()

    /** 주행 중: 색이 가장자리를 따라 흐르지 않고 제자리(소리 크기 반응과 느린 숨쉬기만). 움직임으로 눈을 끌지 않게. */
    var calm = false

    /** 흐름 0~1(한 바퀴 6초). */
    private var phase = 0f
    private var level = 0f
    private var target = 0f

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = FLOW_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 소리 크기 0~1(조용 ~ 큼). */
    fun level(v: Float) { target = v.coerceIn(0f, 1f) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        level += (target - level) * (if (target > level) 0.35f else 0.06f)
        val breath = 0.5f + 0.5f * sin(2 * PI * phase * BREATHS).toFloat()
        val depth = (BASE_DP + LEVEL_DP * level + BREATH_DP * breath) * density
        val alpha = min(1f, 0.5f + 0.15f * breath + 0.35f * level)
        val edge = Color.argb((alpha * 255).toInt(), 255, 255, 255)

        // 위 · 아래(가로로 흐름), 왼쪽 · 오른쪽(세로로 흐름). 모서리는 두 빛이 겹쳐 조금 더 밝다
        draw(canvas, 0f, 0f, w, depth, along = w, horizontal = true, offset = 0f, mask = LinearGradient(0f, 0f, 0f, depth, edge, Color.TRANSPARENT, Shader.TileMode.CLAMP))
        draw(canvas, 0f, h - depth, w, h, along = w, horizontal = true, offset = 0.5f, mask = LinearGradient(0f, h, 0f, h - depth, edge, Color.TRANSPARENT, Shader.TileMode.CLAMP))
        draw(canvas, 0f, 0f, depth, h, along = h, horizontal = false, offset = 0.25f, mask = LinearGradient(0f, 0f, depth, 0f, edge, Color.TRANSPARENT, Shader.TileMode.CLAMP))
        draw(canvas, w - depth, 0f, w, h, along = h, horizontal = false, offset = 0.75f, mask = LinearGradient(w, 0f, w - depth, 0f, edge, Color.TRANSPARENT, Shader.TileMode.CLAMP))
    }

    private fun draw(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, along: Float, horizontal: Boolean, offset: Float, mask: Shader) {
        val span = along * 0.6f
        val flow = if (horizontal) LinearGradient(0f, 0f, span, 0f, COLORS, null, Shader.TileMode.MIRROR)
                   else LinearGradient(0f, 0f, 0f, span, COLORS, null, Shader.TileMode.MIRROR)
        val move = ((if (calm) 0f else phase) + offset) * span * 2
        shift.setTranslate(if (horizontal) move else 0f, if (horizontal) 0f else move)
        flow.setLocalMatrix(shift)
        paint.shader = ComposeShader(flow, mask, PorterDuff.Mode.DST_IN)
        canvas.drawRect(l, t, r, b, paint)
    }

    companion object {
        /** 녹음 빨강(regulation/red) · 코랄 · 앰버 · 핑크 — 앰버·코랄은 브랜드 가이드 5절의 따뜻한 계열 방향(Pleos 토큰 아님). */
        private val COLORS = intArrayOf(
            MainActivity.RECORDING, Color.parseColor("#FF7A59"), Color.parseColor("#F2A33C"),
            Color.parseColor("#FF4D8D"), MainActivity.RECORDING,
        )
        private const val FLOW_MS = 6000L
        /** 한 바퀴(6초)에 세 번 숨쉬기 = 0.5Hz — 깜빡임으로 느껴지지 않게. */
        private const val BREATHS = 3
        private const val BASE_DP = 36f
        private const val LEVEL_DP = 64f
        private const val BREATH_DP = 8f
    }
}
