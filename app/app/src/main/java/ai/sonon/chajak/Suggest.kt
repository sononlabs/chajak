package ai.sonon.chajak

import org.json.JSONObject

/**
 * AI 제안: 멜로디·노래를 보고 고칠 점 2~3개 + 바로 적용할 동작. 판단은 규칙이라 같은 곡이면 같은 제안이 나온다.
 * 동작(action)은 앱의 기존 기능으로 이어진다 — finish/rewrite(변주), create(노래 만들기 화면), rehum(다시 부르기),
 * remix:<무엇>=<값>(이 노래로 다시 만들기). 2026-11-06 사용자 질문 "AI가 음악을 어떻게 개선하면 좋을지 제안"에서 시작.
 */
data class Suggestion(val text: String, val label: String, val action: String)

object Suggest {

    private val PCS = mapOf("C" to 0, "C#" to 1, "Db" to 1, "D" to 2, "D#" to 3, "Eb" to 3, "E" to 4, "F" to 5, "F#" to 6, "Gb" to 6,
                            "G" to 7, "G#" to 8, "Ab" to 8, "A" to 9, "A#" to 10, "Bb" to 10, "B" to 11)

    /** 2단계 내 멜로디: 첫 트랙(내 허밍) 음표를 본다. */
    fun melody(song: JSONObject): List<Suggestion> {
        val tracks = song.optJSONArray("tracks") ?: return emptyList()
        if (tracks.length() == 0) return emptyList()
        val done = (0 until tracks.length()).mapNotNull { tracks.getJSONObject(it).optString("variation").takeIf { v -> v.isNotEmpty() } }.toSet()
        val score = tracks.getJSONObject(0).optJSONArray("score") ?: return emptyList()
        val midis = (0 until score.length()).map { score.getJSONObject(it).optInt("midi") }
        val out = mutableListOf<Suggestion>()
        if (midis.isNotEmpty()) {
            val span = midis.max() - midis.min()
            // 으뜸음: 성격 분석이 고른 조(예: "A#단조") → 없으면 악보 조 표기
            val key = song.optJSONObject("mood")?.optJSONObject("features")?.optString("key")?.takeIf { it.isNotEmpty() }
                ?.removeSuffix("장조")?.removeSuffix("단조") ?: song.optString("key").removeSuffix("m")
            val tonic = PCS[key]
            // 멜로디 상세의 AI 제안은 '노래 만들기' 하나만 남겼다(2026-11-14 사용자: 다듬기·화음·다시 부르기 버튼 삭제).
            // 멜로디에 아쉬운 점이 있으면 노래 만들기의 '내 멜로디: AI가 다듬기'를 켜고 노래 만들기로 보낸다 — 실제로 노래에 반영되는 길
            val issue = when {
                midis.size < 10 -> "음이 ${midis.size}개뿐이라 2분 노래에서 같은 음이 많이 반복될 수 있어요."
                tonic != null && midis.last() % 12 != tonic -> "끝음이 으뜸음(${key})이 아니라 끝이 열려 있어요."
                span < 5 -> "음폭이 ${span}반음으로 좁아 후렴이 밋밋할 수 있어요."
                else -> null
            }
            if (issue != null) out += Suggestion("$issue AI가 다듬어서 노래로 만들어요.", "AI가 다듬어 노래 만들기", "create:polish")
        }
        if (out.isEmpty()) out += Suggestion("멜로디가 잘 잡혔어요. 이대로 노래로 만들어 보세요.", "노래 만들기 →", "create")
        return out.take(1)
    }

    /** 4단계 완성: 노래 설정·가사 맞춤·내 음역대를 본다. */
    fun song(final: JSONObject, song: JSONObject, range: VoiceRange): List<Suggestion> {
        val o = final.optJSONObject("options") ?: JSONObject()
        val out = mutableListOf<Suggestion>()
        val miss = final.optDouble("syllable_miss", 0.0)
        if (!miss.isNaN() && miss > 0.3 && final.optJSONObject("lyrics")?.length() ?: 0 > 0)
            out += Suggestion(if (miss >= 1) "가사 글자 수가 박자와 크게(두 배 가까이) 어긋나요. 가사를 뭉개거나 급하게 부를 수 있어요."
                              else "가사 글자 수가 박자와 약 ${(miss * 100).toInt()}% 어긋나요. 노래가 가사를 급하게 부를 수 있어요.", "가사 새로 쓰기", "remix:lyrics=new")
        val type = range.type()
        val voice = o.optString("voice", "auto")
        if (type != null && voice != "none") {
            val want = if (type.male) "male" else "female"
            if (voice != want) out += Suggestion("내 음역은 ${type.name} 쪽이에요. ${if (type.male) "남성" else "여성"} 목소리면 따라 부르기 편해요.",
                "${if (type.male) "남성" else "여성"} 목소리로 새 버전", "remix:voice=$want")
        }
        val score = song.optJSONArray("tracks")?.optJSONObject(0)?.optJSONArray("score")
        val span = score?.let { s -> (0 until s.length()).map { s.getJSONObject(it).optInt("midi") }.let { m -> if (m.isEmpty()) 99 else m.max() - m.min() } } ?: 99
        if (o.optInt("melody_weight", 80) >= 70 && span < 6)
            out += Suggestion("내 허밍 그대로라 음폭(${span}반음)이 좁아 단조로울 수 있어요. AI 멜로디로 넓혀 볼까요?", "AI 멜로디로 새 버전", "remix:melody=50")
        if (o.optInt("seconds", 120) <= 60)
            out += Suggestion("1분 곡이라 후렴이 한 번뿐이에요.", "2분으로 새 버전", "remix:seconds=120")
        if (out.isEmpty()) out += Suggestion("잘 만들어졌어요. 다른 주제로도 불러 볼까요?", "🎲 다른 주제로 새 버전", "remix:theme=random")
        return out.take(3)
    }
}
