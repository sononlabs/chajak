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
            val key = song.optString("key")
            val tonic = PCS[key.removeSuffix("m")]
            if (midis.size < 10 && "finish" !in done)
                out += Suggestion("음이 ${midis.size}개뿐이에요. 2분으로 늘리면 같은 음이 많이 반복돼요.", "멜로디 완성하기", "finish")
            if (tonic != null && midis.last() % 12 != tonic && "finish" !in done && out.none { it.action == "finish" })
                out += Suggestion("끝음이 으뜸음(${key.removeSuffix("m")})이 아니라 끝이 열려 있어요. 닫아 주면 노래의 끝맺음이 자연스러워요.", "멜로디 완성하기", "finish")
            if (span < 5 && "rewrite" !in done)
                out += Suggestion("음폭이 ${span}반음으로 좁아요. 후렴이 밋밋할 수 있어요.", "AI로 살리기", "rewrite")
        }
        val tempo = song.optInt("tempo")
        if (tempo > 150) out += Suggestion("템포 ${tempo}로 빨라요. 노래가 바쁘게 들릴 수 있어요.", "천천히 다시 부르기", "rehum")
        if (tempo in 1..64) out += Suggestion("템포 ${tempo}로 느려요. 노래가 늘어질 수 있어요.", "조금 빠르게 다시 부르기", "rehum")
        if (out.isEmpty()) out += Suggestion("멜로디가 잘 잡혔어요. 이대로 노래로 만들어 보세요.", "노래 만들기 →", "create")
        return out.take(3)
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
        if (o.optInt("style_weight", 50) < 50)
            out += Suggestion("스타일 강도 ${o.optInt("style_weight")}이라 장르 색이 옅을 수 있어요.", "스타일 70으로 새 버전", "remix:style=70")
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
