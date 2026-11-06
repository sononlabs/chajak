package ai.sonon.chajak

import android.graphics.Color
import org.json.JSONObject

/**
 * 멜로디 성격(서버 melody_mood.py 가 음표에서 잰 값)을 화면에 보여 줄 때 쓰는 색·말.
 * 성격은 에너지(0~100) × 밝기(장조/단조) 6칸 + 밝기가 애매할 때 3칸. 노래 만들기에서 스타일·가사 말투가 이 성격을 따른다.
 */
object Mood {

    private val COLORS = mapOf(
        "포근한" to "#F29E4C", "쓸쓸한" to "#4F74B5", "잔잔한" to "#7AA5CC",
        "산뜻한" to "#2FAE6E", "아련한" to "#9277D1", "부드러운" to "#6FB394",
        "신나는" to "#F4BC22", "강렬한" to "#E5383B", "힘찬" to "#EF7A35",
    )

    fun color(label: String?): Int = Color.parseColor(COLORS[label] ?: "#8B95A1")

    /** 에너지를 다섯 칸 점으로(●●○○○). 주행 중에도 한눈에. */
    fun dots(energy: Int): String = (1..5).joinToString("") { if (energy >= it * 20 - 10) "●" else "○" }

    /** "쓸쓸한 멜로디" 처럼 부를 이름. */
    fun name(mood: JSONObject): String = "${mood.optString("label")} 멜로디"

    /** 이유 한 줄(서버가 고른 두드러진 특성 2~3개). */
    fun why(mood: JSONObject): String =
        mood.optJSONArray("why")?.let { a -> (0 until a.length()).joinToString(" · ") { a.getString(it) } } ?: ""
}
