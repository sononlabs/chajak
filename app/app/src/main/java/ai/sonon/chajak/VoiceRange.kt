package ai.sonon.chajak

import android.graphics.Color

/**
 * 음역대: 흥얼거릴 때 잡힌 음높이(MIDI)를 반음 칸 히스토그램으로 모아 낮은 끝·높은 끝·편한 음(테시투라)과
 * 목소리 유형 쪽을 낸다. 참고: zhitko/singer-voice-tester(유형별 소속 정도), fschmutz/vocal-range-detector(테시투라).
 * 허밍은 노래보다 좁게 나오므로 "~쪽"으로만 말한다.
 */
class VoiceRange(val counts: IntArray = IntArray(128)) {

    data class Type(val name: String, val lo: Int, val hi: Int, val male: Boolean) {
        val center get() = (lo + hi) / 2f
    }

    val total get() = counts.sum()

    fun add(midi: Float) {
        val m = Math.round(midi)
        if (m in LOWEST..HIGHEST) counts[m]++
    }

    fun addAll(other: VoiceRange) { for (i in counts.indices) counts[i] += other.counts[i] }

    /** p(0~1) 백분위 음. 옥타브 튐 같은 드문 값은 양 끝 10% 를 버려 거른다. */
    fun percentile(p: Float): Int? {
        val n = total
        if (n == 0) return null
        var acc = 0
        val target = p * n
        for (m in counts.indices) { acc += counts[m]; if (acc >= target) return m }
        return null
    }

    val low get() = percentile(0.1f)
    val high get() = percentile(0.9f)
    /** 편한 음(테시투라): 가운데 50%. */
    val comfortLow get() = percentile(0.25f)
    val comfortHigh get() = percentile(0.75f)
    val enough get() = total >= MIN_FRAMES

    /** 범위가 유형 범위와 얼마나 겹치는지 + 편한 음 가운데가 유형 가운데에 얼마나 가까운지. */
    fun type(): Type? {
        val lo = low ?: return null
        val hi = high ?: return null
        val mid = ((comfortLow ?: lo) + (comfortHigh ?: hi)) / 2f
        if (!enough) return null
        return TYPES.maxByOrNull { t ->
            val overlap = (minOf(hi, t.hi) - maxOf(lo, t.lo)).coerceAtLeast(0).toFloat() / maxOf(1, hi - lo)
            overlap - kotlin.math.abs(mid - t.center) / 12f
        }
    }

    fun encode(): String = counts.withIndex().filter { it.value > 0 }.joinToString(",") { "${it.index}:${it.value}" }

    companion object {
        /** 그리는 범위 C2~C6. 녹음 쪽 음높이 찾기는 70~1000Hz. */
        const val LOWEST = 36
        const val HIGHEST = 84
        /** 1초 남짓(50ms 프레임 20개) 넘게 들려야 유형을 말한다. */
        const val MIN_FRAMES = 20

        val TYPES = listOf(
            Type("베이스", 40, 64, true), Type("바리톤", 45, 69, true), Type("테너", 48, 72, true),
            Type("알토", 53, 77, false), Type("메조소프라노", 57, 81, false), Type("소프라노", 60, 84, false),
        )

        private val LETTERS = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        fun name(m: Int) = "${LETTERS[m % 12]}${m / 12 - 1}"

        fun decode(s: String?): VoiceRange = VoiceRange().apply {
            s?.split(",")?.forEach { kv ->
                val (k, v) = kv.split(":").takeIf { it.size == 2 } ?: return@forEach
                val m = k.toIntOrNull() ?: return@forEach
                if (m in counts.indices) counts[m] = v.toIntOrNull() ?: 0
            }
        }

        /** 음높이 → 색: 낮은 음 파랑 → 가운데 초록 → 높은 음 주황(녹음 링·음역 막대 공통). */
        fun color(midi: Float): Int {
            val t = ((midi - LOWEST) / (HIGHEST - LOWEST)).coerceIn(0f, 1f)
            return if (t < 0.5f) blend(LOW_COLOR, MID_COLOR, t * 2) else blend(MID_COLOR, HIGH_COLOR, (t - 0.5f) * 2)
        }

        private val LOW_COLOR = Color.parseColor("#3D5AFE")
        private val MID_COLOR = Color.parseColor("#00B386")
        private val HIGH_COLOR = Color.parseColor("#FF6D00")

        private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    }
}
