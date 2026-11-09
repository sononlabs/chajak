package ai.sonon.chajak

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * Pleos Gleo Interface(시스템 음성 비서) 연동 — "차작, 녹음 시작해 줘" 같은 말로 앱 기능을 부른다.
 *
 * - 부를 수 있는 기능은 `assets/schema.json`(App Schema)에 7개: 허밍 녹음 시작·끝내기 · 편곡 바꾸기 · 대화 녹음 시작·끝내기 · 최근 노래 틀기 · 노래 멈추기.
 * - Gleo 는 기능 이름을 Intent action 으로, 파라미터를 extras "ACTION_PARAM"(JSON 문자열)으로 보낸다(component = activity → startActivity).
 * - 결과는 7초 안에 브로드캐스트 `APP_ACTION_RESPONSE` 로 돌려준다(extras: packageName, response = {functionName, status, message, mode, data}).
 *   message 는 영어 문장(문서 규칙), Gleo 가 그걸 바탕으로 사용자에게 말한다.
 * - 스키마는 Gleo 쪽 검토·승인 뒤 활성화된다. 그 전에는 adb 로 같은 Intent 를 보내 시험한다.
 * 근거: document.pleos.ai — App Schema Writing Guide · Callback Schema Writing Guide · Code Details with Examples · Delivering the Schema to Gleo.
 */
object Gleo {
    const val START = "START_HUM_RECORDING"
    const val FINISH = "FINISH_HUM_RECORDING"
    const val ARRANGE = "CHANGE_SONG_ARRANGEMENT"
    const val TALK_START = "START_CONVERSATION_RECORDING"
    const val TALK_FINISH = "FINISH_CONVERSATION_RECORDING"
    const val PLAY = "PLAY_LATEST_SONG"
    const val STOP = "STOP_SONG"
    val ACTIONS = setOf(START, FINISH, ARRANGE, TALK_START, TALK_FINISH, PLAY, STOP)

    private const val PARAM = "ACTION_PARAM"
    private const val RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    /** Gleo 가 보낸 파라미터(JSON). 없거나 깨졌으면 빈 객체. */
    fun params(intent: Intent): JSONObject =
        intent.getStringExtra(PARAM)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    /**
     * status: success · asking(사용자에게 더 물어야 함) · fail.
     * mode: voice(기본, Gleo 가 말함) · beep · silent(앱이 직접 소리를 낼 때 — 녹음 시작은 3·2·1 이 녹음에 섞이지 않게 silent).
     */
    fun respond(context: Context, function: String, status: String, message: String, mode: String = "voice") {
        val json = JSONObject().put("functionName", function).put("status", status).put("message", message).put("mode", mode)
        Log.i("Chajak", "Gleo 응답 $json")
        context.sendBroadcast(Intent(RESPONSE).putExtra("packageName", context.packageName).putExtra("response", json.toString()))
    }
}
