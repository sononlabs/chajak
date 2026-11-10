package ai.sonon.chajak

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 대화 보관함 — 이 기기 안(files/talks/{id}.json)에만 둔다(2026-11-10 사용자: "대화 보관은 앱 로컬에").
 * 녹음 파일은 두지 않고, 서버가 받아쓰고 정리한 결과만 둔다(사람 이름은 2026-11-11부터 가리지 않음 — 가사의 재미). 서버에도 남지 않는다.
 * 항목: id, created(ms), seconds, direction, theme, story, feeling, phrases, details, hooks, names, title, enough, transcript, lines, songs(만든 노래 곡 id)
 */
class TalkStore(context: Context) {
    private val dir = File(context.filesDir, "talks").apply { mkdirs() }

    fun list(): List<JSONObject> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
        .sortedByDescending { it.optLong("created") }

    fun get(id: String): JSONObject? = File(dir, "$id.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    fun save(t: JSONObject) {
        File(dir, t.getString("id") + ".json").writeText(t.toString())
    }

    fun delete(id: String) = File(dir, "$id.json").delete()
}
