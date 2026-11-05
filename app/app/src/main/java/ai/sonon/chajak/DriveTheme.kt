package ai.sonon.chajak

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime

/**
 * 오늘 드라이브 → 노래 주제·앨범. 지금은 요일·시간대로(출근길/퇴근길/밤/주말), 목적지·날씨는 SDK 확인 후 붙인다(⚠).
 * 15번 v4 5절 "오늘 드라이브가 주제가 된다", "드라이브 앨범".
 */
object DriveTheme {

    private val DAYS = mapOf(DayOfWeek.MONDAY to "월요일", DayOfWeek.TUESDAY to "화요일", DayOfWeek.WEDNESDAY to "수요일",
        DayOfWeek.THURSDAY to "목요일", DayOfWeek.FRIDAY to "금요일", DayOfWeek.SATURDAY to "토요일", DayOfWeek.SUNDAY to "일요일")

    /** 드라이브의 때: 출근길 · 퇴근길 · 밤 드라이브 · 주말 드라이브 · 낮 드라이브. */
    fun moment(t: LocalDateTime): String {
        val weekend = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY
        return when {
            t.hour >= 22 || t.hour < 5 -> "밤 드라이브"
            weekend -> "주말 드라이브"
            t.hour in 5..9 -> "출근길"
            t.hour in 17..21 -> "퇴근길"
            else -> "낮 드라이브"
        }
    }

    /** 주제 기본값: "금요일 퇴근길, 저녁 7시의 드라이브". */
    fun now(t: LocalDateTime = LocalDateTime.now()): String {
        val part = when (t.hour) { in 5..11 -> "아침"; in 12..16 -> "오후"; in 17..20 -> "저녁"; else -> "밤" }
        val h = (t.hour % 12).let { if (it == 0) 12 else it }
        return "${DAYS[t.dayOfWeek]} ${moment(t)}, $part ${h}시의 드라이브"
    }

    private fun parse(created: String?): LocalDateTime? =
        created?.takeIf { it.length >= 19 }?.let { runCatching { LocalDateTime.parse(it.substring(0, 19)) }.getOrNull() }

    /** 서버 created(2026-11-05T23:32:54) → 앨범 이름. */
    fun momentOf(created: String?): String = parse(created)?.let(::moment) ?: "그 밖의"

    fun withinHours(created: String?, hours: Long): Boolean =
        parse(created)?.let { Math.abs(Duration.between(it, LocalDateTime.now()).toHours()) < hours } ?: false  // 서버·기기 시간대가 달라도(에뮬레이터 등) 너그럽게
}
