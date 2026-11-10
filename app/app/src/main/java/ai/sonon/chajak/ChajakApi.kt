package ai.sonon.chajak

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 서버가 돌려준 오류. message 는 화면에 그대로 띄울 수 있는 문장(서버 README 오류 코드 표). */
class ApiException(val code: String, message: String) : Exception(message)

/** 차작 서버(chajak-server) HTTP 클라이언트. 모든 호출은 블로킹이라 백그라운드 스레드에서 부른다. */
class ChajakApi(val base: String) {

    /** input: hum(흥얼거림) | music(재생 중인 음악을 녹음) — 트랙 이름에만 쓴다. */
    fun createSong(hum: File, instrument: String, melody: String, accompaniment: Boolean, source: String = "hum"): JSONObject =
        multipart("/v1/songs", hum, mapOf("instrument" to instrument, "melody" to melody, "accompaniment" to accompaniment.toString(), "input" to source))

    fun addTrack(songId: String, hum: File, instrument: String, melody: String, mode: String, source: String = "hum"): JSONObject =
        multipart("/v1/songs/$songId/tracks", hum, mapOf("instrument" to instrument, "melody" to melody, "mode" to mode, "input" to source))

    fun addVariation(songId: String, variation: String): JSONObject =
        request("POST", "/v1/songs/$songId/variations", JSONObject().put("variation", variation))

    fun deleteTrack(songId: String, n: Int): JSONObject = request("DELETE", "/v1/songs/$songId/tracks/$n", null)

    /** 곡 지우기: 멜로디·트랙·노래 모든 버전·표지(서버와 생성 엔진에 남은 사본까지). */
    fun deleteSong(songId: String): JSONObject = request("DELETE", "/v1/songs/$songId", null)

    /** 노래 한 버전 지우기 → 남은 곡 정보. */
    fun deleteFinal(songId: String, finalId: String): JSONObject = request("DELETE", "/v1/songs/$songId/finals/$finalId", null)

    fun variations(): JSONObject = request("GET", "/v1/variations", null)

    fun instruments(): JSONObject = request("GET", "/v1/instruments", null)

    fun themes(): JSONObject = request("GET", "/v1/themes", null)

    /** 최종 노래 만들기 시작 → 작업(job). 진행은 job(id) 로 묻는다. */
    fun startFinal(songId: String, prompt: String, options: JSONObject): JSONObject =
        request("POST", "/v1/songs/$songId/final", JSONObject(options.toString()).put("prompt", prompt))

    fun job(id: String): JSONObject = request("GET", "/v1/jobs/$id", null, 10_000)

    /** 노래 만들기 취소(대기 중이면 빼고, 만드는 중이면 멈춘다). */
    fun cancelJob(id: String): JSONObject = request("POST", "/v1/jobs/$id/cancel", null, 15_000)

    fun song(id: String): JSONObject = request("GET", "/v1/songs/$id", null)

    fun songs(limit: Int): JSONObject = request("GET", "/v1/songs?limit=$limit", null)

    fun bytes(path: String): ByteArray {
        val c = open(path)
        c.readTimeout = 15_000
        return try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    /** 서버 주소 + 토큰(?t=). 미리 듣기·차량 미디어 센터처럼 헤더를 못 붙이는 곳에서 쓴다. */
    fun url(path: String) = withToken(base + path)

    companion object {
        /** 빌드 때 넣은 서버 공유 토큰(없으면 인증 없이). 서버 README '인증' 참고. */
        val TOKEN: String = BuildConfig.SERVER_TOKEN

        fun withToken(u: String): String =
            if (TOKEN.isEmpty() || "t=" in u.substringAfter('?', "")) u else u + (if ('?' in u) "&" else "?") + "t=" + TOKEN
    }

    private fun request(method: String, path: String, body: JSONObject?, readTimeoutMs: Int = 120_000): JSONObject {
        val c = open(path)
        return try {
            c.readTimeout = readTimeoutMs
            c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            read(c)
        } finally {
            c.disconnect()
        }
    }

    /** 대화 분석 작업 시작(녹음 올리기) → {id}. 진행·결과는 talkStatus. 30분 녹음(약 7MB)도 올리게 3분까지 기다린다. */
    fun talkStart(file: File, direction: String): JSONObject = multipart("/v1/talk", file, mapOf("direction" to direction), 180_000)

    /** 대화 분석 진행: {status: running|done|error, stage, seconds, eta_sec, result?, error?}. */
    fun talkStatus(id: String): JSONObject = request("GET", "/v1/talk/$id", null, 15_000)

    /** 보관한 대화를 다른 방향으로 다시 정리(받아쓰기 없이 약 10초). */
    fun talkDigest(lines: org.json.JSONArray, direction: String): JSONObject =
        request("POST", "/v1/talk/digest", JSONObject().put("lines", lines).put("direction", direction))

    /** 가사만 쓰기(노래 만들기 전 미리 보기). 약 1분이라 4분까지 기다린다. */
    fun writeLyrics(songId: String, body: JSONObject): JSONObject = request("POST", "/v1/songs/$songId/lyrics", body, 240_000)

    /** 곡 구성 미리보기: 가사 들어갈 구간과 줄마다 글자 수 목표. */
    fun songForm(songId: String, seconds: Int): JSONObject = request("GET", "/v1/songs/$songId/form?seconds=$seconds", null, 60_000)

    private fun multipart(path: String, file: File, fields: Map<String, String>, readTimeoutMs: Int = 120_000): JSONObject {
        val boundary = "chajak" + System.nanoTime()
        val c = open(path)
        c.readTimeout = readTimeoutMs
        return try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { out ->
                for ((k, v) in fields) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
                }
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"${file.name}\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            read(c)
        } catch (e: java.io.IOException) {
            throw ApiException("NETWORK", "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.")
        } finally {
            // 허밍 실패(422)처럼 흔한 오류에도 연결을 돌려준다
            c.disconnect()
        }
    }

    private fun open(path: String) = (URL(base + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 120_000
        if (TOKEN.isNotEmpty()) setRequestProperty("X-Chajak-Token", TOKEN)
    }

    private fun read(c: HttpURLConnection): JSONObject {
        val status = try {
            c.responseCode
        } catch (e: java.io.IOException) {
            throw ApiException("NETWORK", "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.")
        }
        // 오류 본문이 없거나 JSON 이 아니어도 코드·문장을 가진 ApiException 으로 — 부르는 쪽이 NOT_FOUND 등을 가를 수 있게
        val text = runCatching { (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (status >= 400) {
            val err = json?.optJSONObject("error")
            if (status == 401) throw ApiException("UNAUTHORIZED", err?.optString("message")?.ifEmpty { null } ?: "이 서버를 쓸 수 있는 앱이 아니에요.")
            throw ApiException(err?.optString("code")?.ifEmpty { null } ?: if (status == 404) "NOT_FOUND" else "HTTP_$status",
                               err?.optString("message")?.ifEmpty { null } ?: "오류가 났어요($status).")
        }
        return json ?: throw ApiException("BAD_RESPONSE", "서버 응답을 읽지 못했어요.")
    }
}
