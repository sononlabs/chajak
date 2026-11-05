package ai.sonon.chajak

import org.json.JSONObject

/**
 * 제목이 아직 없는 곡(완성된 노래 전)의 기본 이름 = 만든 날짜·시각. "내 멜로디"가 줄줄이 같지 않게 곡마다 다르다.
 * 서버 created 형식: 2026-11-05T23:32:54 → "11월 5일 23:32 멜로디".
 */
fun dateTitle(created: String?): String? {
    val m = Regex("""\d{4}-(\d{2})-(\d{2})T(\d{2}):(\d{2})""").find(created ?: return null) ?: return null
    val (mo, d, h, mi) = m.destructured
    return "${mo.toInt()}월 ${d.toInt()}일 $h:$mi 멜로디"
}

/** 곡 이름: 완성된 노래 제목 → 만든 날짜 → fallback. 목록(title 키)·곡 상세(final.title) 둘 다 받는다. */
fun songName(s: JSONObject, fallback: String = "멜로디"): String =
    s.optJSONObject("final")?.optString("title")?.takeIf { it.isNotEmpty() }
        ?: s.optString("title").takeIf { !s.isNull("title") && it.isNotEmpty() }
        ?: dateTitle(s.optString("created"))
        ?: fallback
