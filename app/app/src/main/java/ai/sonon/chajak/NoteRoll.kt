package ai.sonon.chajak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * 노래방 음정 막대처럼 트랙 음표를 그린다. 가로 = 트랙 시간 전체, 세로 = 반음 한 줄씩(위로 갈수록 높은 음).
 * 막대마다 계이름을 적고, playhead(초)를 주면 지금 부르는 음을 빨갛게, 지난 음은 옅게 칠한다.
 * 따라 부르기 연습: grades(음표 번호 → GOOD/CLOSE/MISS)로 막대 색을, sung 으로 사용자가 낸 음을 파란 점으로 겹친다.
 */
class NoteRoll(context: Context, val notes: List<Note>, private val seconds: Float, font: Typeface) : View(context) {

    data class Note(val start: Float, val dur: Float, val midi: Int)

    var playhead = -1f
        set(v) { field = v; invalidate() }

    /** false 면 지금 음을 빨강 대신 진하게(따라 부르는 중엔 빨강을 '틀림'에 쓴다). */
    var showCurrent = true
    val grades = HashMap<Int, Int>()
    private val sung = ArrayList<Pair<Float, Float>>()

    fun addSung(t: Float, midi: Float) {
        sung += t to midi
        invalidate()
    }

    val current: Note? get() = notes.firstOrNull { playhead >= it.start && playhead < it.start + it.dur }

    private val low = (notes.minOfOrNull { it.midi } ?: 60) - 2
    private val high = maxOf((notes.maxOfOrNull { it.midi } ?: 72) + 2, low + 12)

    private val density = resources.displayMetrics.density
    private val sharpLane = Paint().apply { color = MainActivity.TINT }
    private val laneLine = Paint().apply { color = MainActivity.TINT; strokeWidth = density }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val head = Paint().apply { color = MainActivity.RECORDING; strokeWidth = density * 3 }
    private val laneText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MainActivity.TEXT3; textSize = density * 24;  /* Google AAOS 최소 24(이전 18) */ typeface = font }
    private val barText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = density * 24; typeface = font }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val gutter = density * 64
        val pad = density * 12
        val lane = (height - 2 * pad) / (high - low + 1)
        fun top(m: Int) = pad + (high - m) * lane
        fun x(t: Float) = gutter + (width - gutter - pad) * t / seconds

        for (m in low..high) {
            if (NAMES[m % 12].endsWith("♯")) canvas.drawRect(gutter, top(m), width - pad, top(m) + lane, sharpLane)
            else if (lane >= density * 26 || m % 12 == 0) canvas.drawText(NAMES[m % 12], pad, top(m) + lane / 2 + laneText.textSize / 3, laneText)
            if (m % 12 == 0) canvas.drawLine(gutter, top(m) + lane, width - pad, top(m) + lane, laneLine)
        }
        val now = current
        for ((i, n) in notes.withIndex()) {
            box.set(x(n.start) + density, top(n.midi) + lane * 0.12f, x(n.start + n.dur) - density, top(n.midi) + lane * 0.88f)
            bar.color = when (grades[i]) {
                GOOD -> MainActivity.ACTIVE
                CLOSE -> AMBER
                MISS -> MainActivity.RECORDING
                else -> when {
                    n == now -> if (showCurrent) MainActivity.RECORDING else MainActivity.TEXT
                    playhead >= n.start + n.dur -> MainActivity.TEXT_DISABLED
                    else -> MainActivity.GRAY_600
                }
            }
            canvas.drawRoundRect(box, density * 6, density * 6, bar)
            val label = NAMES[n.midi % 12]
            if (box.width() > barText.measureText(label) + density * 8 && box.height() > barText.textSize)
                canvas.drawText(label, box.left + density * 4, box.centerY() + barText.textSize / 3, barText)
        }
        for ((t, m) in sung) canvas.drawCircle(x(t), pad + (high - m) * lane + lane / 2, density * 5, dot)
        if (playhead >= 0) canvas.drawLine(x(playhead), pad, x(playhead), height - pad, head)
    }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MainActivity.POSITIVE }

    companion object {
        const val MISS = 0
        const val CLOSE = 1
        const val GOOD = 2
        /** 아깝게 맞음(반음 안) — Pleos 팔레트에 노랑 계열이 없어 프로젝트 선택. */
        val AMBER = android.graphics.Color.parseColor("#F5A300")

        /** 고정 도(C = 도) 계이름. */
        val NAMES = listOf("도", "도♯", "레", "레♯", "미", "파", "파♯", "솔", "솔♯", "라", "라♯", "시")
    }
}
