package ai.sonon.chajak

import org.json.JSONObject

/**
 * 제목이 아직 없는 곡(완성된 노래 전)의 기본 이름 = 만든 날짜·시각. "내 멜로디"가 줄줄이 같지 않게 곡마다 다르다.
 * 서버 created 형식: 2026-11-05T23:32:54 → "11월 5일 23:32 멜로디".
 */
fun dateTitle(created: String?): String? {
    val c = localCreated(created) ?: return null
    return "${c.monthValue}월 ${c.dayOfMonth}일 %02d:%02d 멜로디".format(c.hour, c.minute)
}

/**
 * 서버 created → 이 기기(차)의 시각. 시간대가 붙어 있으면(2026-11-09T23:31:28+09:00, 0.4.8 서버부터) 기기 시간대로 바꾸고,
 * 없으면(옛 곡) 적힌 그대로 본다.
 */
fun localCreated(created: String?): java.time.LocalDateTime? {
    if (created == null || created.length < 19) return null
    return runCatching {
        java.time.OffsetDateTime.parse(created).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime()
    }.getOrNull() ?: runCatching { java.time.LocalDateTime.parse(created.substring(0, 19)) }.getOrNull()
}

/** 곡 이름: 완성된 노래 제목 → 만든 날짜 → fallback. 목록(title 키)·곡 상세(final.title) 둘 다 받는다. */
fun songName(s: JSONObject, fallback: String = "멜로디"): String =
    s.optJSONObject("final")?.optString("title")?.takeIf { it.isNotEmpty() }
        ?: s.optString("title").takeIf { !s.isNull("title") && it.isNotEmpty() }
        ?: dateTitle(s.optString("created"))
        ?: fallback
