package ai.sonon.chajak

import android.Manifest
import android.app.Activity
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.TextWatcher
import android.widget.EditText
import android.widget.SeekBar
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * 차작 MVP: 흥얼거리기 → 서버에서 악기 연주 + 자동 반주 → 듣기 → 겹쳐/이어 부르기 · 멜로디 변주로 트랙 쌓기.
 * 화면은 Pleos Connect Design System 라이트 테마(색·글자·버튼 수치는 아래 companion 참고).
 */
class MainActivity : Activity() {

    private val tag = "Chajak"
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    /** 녹음 전용. io 에 표지·목록 받기가 쌓여 있어도 녹음은 바로 시작한다(검수 2026-11-05). */
    private val rec = Executors.newSingleThreadExecutor()
    /** 표지 그림 받기 전용(둘씩). */
    private val images = Executors.newFixedThreadPool(2)
    private val recorder = HumRecorder()
    /** 재생은 ChajakMediaService(Media3 미디어 세션)가 한다. Activity 는 MediaController 로 명령만 보낸다. */
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    /**
     * 짧은 소리(녹음·내 멜로디 트랙·전체 듣기) 미리 듣기. 미디어 세션 밖에서 잠깐 포커스로 튼다 —
     * 끝나면 듣던 차 음악(폰 블루투스 등)이 이어진다. 완성된 노래만 세션(스티어링휠·위젯)으로 튼다. TransientFocus 참고.
     */
    private var preview: ExoPlayer? = null
    private var previewFocus: TransientFocus? = null
    /** 지금 소리를 내는 쪽. 미리 듣기 중이면 preview, 아니면 세션. */
    private val activePlayer: Player? get() = if (previewActive) preview else controller
    /** 지금 재생이 미리 듣기 플레이어인가(toggle 이 정한다). */
    private var previewActive = false
    /** 다음 한 번은 완성곡도 미리 듣기로(방금 만든 노래를 들려주고 듣던 음악으로 돌아가게). */
    private var playOnce = false
    /** 재생 위치를 물어도 되는 상태(준비 끝). */
    private var prepared = false
    private var car: Car? = null

    private lateinit var api: ChajakApi
    private lateinit var root: FrameLayout

    // 녹음 설정 (길이는 정하지 않고 사용자가 멈출 때까지, 최대 MAX_RECORD_SEC)
    private var instrument = 0
    private var melody = "그대로"
    private var accompaniment = true
    /** null 이면 새 곡, 아니면 쌓기 방식(overlay|append). */
    private var stackMode: String? = null
    /** 곡 화면 토글에서 고른 쌓기 방식. 녹음 버튼을 누르면 stackMode 가 된다. */
    private var stackChoice = "overlay"

    private var song: JSONObject? = null
    private var playing: String? = null
    @Volatile private var driving = false
    private var recording = false
    private var instrumentNames = INSTRUMENTS.map { "GM ${it.second} ${it.third}" }.toMutableList()
    private var variations = DEFAULT_VARIATIONS

    /** 시험 모드: 마이크 대신 앱 files 폴더의 녹음 파일을 차례로 보낸다(am start --es test_inputs a.wav,b.wav). */
    private var testInputs = listOf<String>()
    private var testIndex = 0
    private var countdownToken = 0
    private var counting = false
    /** 녹음 도중 화면을 떠나 멈춘 녹음은 보내지 않고 버린다. */
    @Volatile private var cancelled = false
    private var musicCapturing = false
    private var musicPermissionPending = false
    private var active = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = ChajakApi(intent.getStringExtra("server") ?: prefs.getString(SERVER_CUSTOM, null) ?: BuildConfig.SERVER_URL)
        // 미디어 서비스(차량 미디어 센터·음성 검색)가 화면 없이도 서버를 알 수 있게 둔다
        prefs.edit().putString("server", api.base).apply()
        restoreDraft()
        testInputs = intent.getStringExtra("test_inputs")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf()
        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        setContentView(root)
        watchDriving()
        bindMediaSession()
        audio.registerAudioDeviceCallback(deviceWatch, ui)
        io.execute { loadCatalog() }
        loadLibrary()
        val open = intent.getStringExtra("song")
        when {
            open != null -> { showHome(); openSong(open) }  // 시험·시연용: am start --es song <id>
            // 흥얼거리기 요령은 뺐다(2026-11-10 사용자) — 처음에도 바로 녹음실
            else -> showHome()
        }
        handleGleo(intent)
    }

    private val prefs by lazy { getSharedPreferences("chajak", MODE_PRIVATE) }

    /** 앱이 떠 있을 때 am start --es song <id> 로 곡 열기(시험·시연용). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("song")?.let { openSong(it) }
        handleGleo(intent)
    }

    /**
     * Gleo 음성 명령 처리(Gleo.kt). 주행 중에도 된다 — 화면을 누르지 않고 말로 하는 길이라서.
     * 녹음 시작은 Gleo 가 말하기를 끝내고 오디오를 놓을 틈(0.7초)을 둔 뒤 3·2·1 로 들어간다.
     */
    private fun handleGleo(intent: Intent?) {
        val action = intent?.action?.takeIf { it in Gleo.ACTIONS } ?: return
        setIntent(Intent(this, MainActivity::class.java))  // 화면을 다시 만들 때 같은 명령이 또 돌지 않게
        val p = Gleo.params(intent)
        Log.i(tag, "Gleo 명령 $action $p")
        when (action) {
            Gleo.START -> when {
                recording || counting -> Gleo.respond(this, action, "fail", "A recording is already in progress.")
                pending != null -> Gleo.respond(this, action, "fail", "The previous humming is still being converted. Please try again in a moment.")
                else -> ui.postDelayed({
                    stackMode = null
                    startRecording()
                    if (counting) Gleo.respond(this, action, "success", "Recording starts after the 3-2-1 beeps. Say finish or press play-pause on the steering wheel when done.", "silent")
                    else Gleo.respond(this, action, "fail", "Recording could not start because another sound is using the audio. Please try again after it ends.")
                }, GLEO_GAP_MS)
            }
            Gleo.FINISH -> {
                val cancel = p.optString("result") == "cancel"
                when {
                    counting -> { cancelCountdown(); Gleo.respond(this, action, "success", "The recording was cancelled before it started.") }
                    recording && cancel -> { cancelRecording(); Gleo.respond(this, action, "success", "The recording was cancelled and not saved.") }
                    recording -> { recorder.stop(); Gleo.respond(this, action, "success", "Recording finished. Turning the humming into a melody now.") }
                    else -> Gleo.respond(this, action, "fail", "No recording is in progress.")
                }
            }
            Gleo.TALK_START -> when {
                recording || counting || TalkRecordService.recording -> Gleo.respond(this, action, "fail", "A recording is already in progress.")
                driving -> Gleo.respond(this, action, "fail", "Conversation recording can start only while the car is stopped, because everyone in the car should be told first.")
                else -> {
                    talkPickFor = null
                    showTalkIntro()
                    Gleo.respond(this, action, "success", "Please let everyone in the car know you are recording, then press start on the screen. It can record up to thirty minutes.")
                }
            }
            Gleo.TALK_FINISH -> {
                val cancel = p.optString("result") == "cancel"
                if (!TalkRecordService.recording) Gleo.respond(this, action, "fail", "No conversation recording is in progress.")
                else {
                    talkCancelled = cancel
                    TalkRecordService.stop(this)
                    Gleo.respond(this, action, "success", if (cancel) "The conversation recording was cancelled and not saved." else "Recording finished. Writing it down and turning it into a song story now.")
                }
            }
            Gleo.PLAY -> {
                val latest = library.filter { !it.isNull("title") }.maxByOrNull { it.optString("created") }
                when {
                    recording || counting || TalkRecordService.recording -> Gleo.respond(this, action, "fail", "Please finish the recording first.")
                    latest == null -> Gleo.respond(this, action, "fail", "There is no finished song yet. Hum a melody and make a song first.")
                    else -> {
                        playLatest(latest.getString("id"))
                        Gleo.respond(this, action, "success", "Playing your latest song, ${latest.optString("title")}.")
                    }
                }
            }
            Gleo.STOP -> {
                if (playing == null) Gleo.respond(this, action, "fail", "Nothing is playing.")
                else { stopPlayback(); refresh(); Gleo.respond(this, action, "success", "Stopped the song.", "silent") }
            }
            Gleo.ARRANGE -> {
                val req = p.optString("request").trim()
                val f = song?.optJSONObject("final")
                when {
                    req.isEmpty() -> Gleo.respond(this, action, "asking", "How would you like to change the arrangement?")
                    f == null -> Gleo.respond(this, action, "asking", "Please open the finished song you want to change in Chajak first.")
                    finalSubmitting -> Gleo.respond(this, action, "fail", "A new version is being sent. Please try again in a moment.")
                    recording || counting -> Gleo.respond(this, action, "fail", "Please finish the recording first.")
                    else -> {
                        prompt = f.optString("prompt", prompt)
                        createFinal(from = playerVersion ?: f.optString("id").ifEmpty { null }, keepLyrics = true, keepCover = true, edit = req)
                        Gleo.respond(this, action, "success", "Making a new version with that arrangement, keeping the melody, lyrics and cover. It takes about three minutes.")
                    }
                }
            }
        }
    }

    /** 가장 최근 노래를 열어 바로 듣기(Gleo "내 노래 틀어 줘"). */
    private fun playLatest(songId: String) {
        stopPlayback()
        io.execute {
            try {
                val s = api.song(songId)
                ui.post { song = s; stackMode = null; playerVersion = null; showPlayer(autoplay = true) }
            } catch (e: Exception) {
                ui.post { showError(e.message ?: "노래를 열지 못했어요.") }
            }
        }
    }

    /**
     * 스티어링휠 미디어 키(재생/일시정지)로 녹음 끝내기 — 운전대에서 손을 떼지 않고 "녹음 완료".
     * 차의 미디어 소스(폰 음악)를 가져오지 않아도 된다: 차작 화면이 앞에 있으면 키가 먼저 이 창으로 오고,
     * 여기서 쓰지 않은 키만 시스템이 미디어 세션으로 넘긴다. 녹음·카운트다운 중에만 가로채고 그 밖에는 그대로 흘려보낸다.
     * 카운트다운 중 = 녹음 취소(가운데 버튼과 같게), 녹음 중 = 녹음 완료. 음악에서 따기도 같다.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in FINISH_KEYS || !(recording || counting)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true
        Log.i(tag, "스티어링휠 키 ${KeyEvent.keyCodeToString(keyCode)} → ${if (counting) "녹음 취소" else "녹음 완료"}")
        when {
            counting -> cancelCountdown()
            drill == Drill.SING || recording -> recorder.stop()
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode in FINISH_KEYS && (recording || counting)) true else super.onKeyUp(keyCode, event)

    /** 화면을 떠나면 카운트다운·녹음을 멈춘다. 사용자가 모르는 녹음은 없어야 한다. */
    override fun onPause() {
        super.onPause()
        active = false
        saveDraft()
        cancelDrill()
        // 말로 주제 넣기도 화면을 떠나면 바로 끈다 — 안 보이는 데서 마이크가 열려 있으면 안 된다
        if (listening) {
            listening = false
            recognizer?.cancel(); recognizer?.destroy(); recognizer = null
            releaseFocus()
        }
        if (counting || recording) {
            cancelled = true
            countdownToken++
            counting = false
            recorder.stop()
            releaseFocus()
        }
        // 음악 앱처럼 완성된 노래(세션)는 화면을 떠나도 계속 — 짧은 소리(미리 듣기)만 멈춘다
        if (!musicPermissionPending && !capturePending) stopPreview()
    }

    override fun onResume() {
        super.onResume()
        active = true
        resumeFinalJob()
        // 화면을 떠나 있는 동안 대화 녹음이 끝났으면(30분·알림) 이어서 올린다. 녹음 중이면 녹음 화면으로
        TalkRecordService.onDone = { onTalkRecorded() }
        if (TalkRecordService.result != null) onTalkRecorded()
        else if (TalkRecordService.recording && current.javaClass != ::showTalkRecording.javaClass) showTalkRecording()
        if (cancelled && !recording) {
            cancelled = false
            showHome()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 차량 홈/시스템 패널이 화면을 덮는 경우에도 보이지 않는 녹음은 남기지 않는다.
        if (!hasFocus && (musicCapturing || counting)) {
            cancelled = true
            countdownToken++
            counting = false
            recorder.stop()
            releaseFocus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        finalPollToken++
        finalPollThread?.interrupt()
        talkPollToken++
        talkPollThread?.interrupt()
        scoreToken++                         // 재생 위치·악보 갱신 반복을 멈춘다(화면이 없어도 돌았다)
        ui.removeCallbacksAndMessages(null)
        TalkRecordService.onDone = null      // 서비스가 사라진 화면을 붙잡지 않게(onResume 에서 다시 건다)
        car?.disconnect()
        controller?.release()
        controller = null
        controllerFuture = null
        previewFocus?.release()
        preview?.release()
        preview = null
        stopCapture()
        io.shutdownNow()
        rec.shutdownNow()
        images.shutdownNow()
        audio.unregisterAudioDeviceCallback(deviceWatch)
        recognizer?.destroy()
        recognizer = null
    }

    // ------------------------------------------------------------------ 차량 상태

    /** 주행 규제가 켜지면 녹음을 막고, 녹음 중이면 바로 멈춘다(만들기는 정차 중 · 듣기는 주행 중). */
    private fun watchDriving() {
        // 차량이 아닌 기기(android.car 없음)에서는 createCar 가 예외를 던진다 — 그때는 주행 규제 없이(정차로) 동작
        if (android.os.Build.VERSION.SDK_INT < 29) return
        car = runCatching { Car.createCar(this) }.onFailure { Log.w(tag, "차량 서비스 없음: ${it.message}") }.getOrNull()
        val m = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
        driving = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
        m.registerListener { r ->
            driving = r.isRequiresDistractionOptimization
            Log.i(tag, "주행 규제 ${if (driving) "켜짐" else "꺼짐"}")
            ui.post {
                glow?.calm = driving
                // 주행 중에도 녹음은 계속한다(버튼 한 번으로 시작·그만, 화면을 볼 필요 없음). 녹음 중이 아니면 화면만 새로
                if (!recording) refresh()
            }
        }
    }

    private fun loadCatalog() {
        try {
            val all = api.instruments().getJSONArray("instruments")
            val names = (0 until all.length()).map { all.getString(it) }
            instrumentNames = INSTRUMENTS.map { (_, gm, en) -> names.firstOrNull { it.startsWith("GM $gm ") } ?: "GM $gm $en" }.toMutableList()
            val v = api.variations().getJSONArray("variations")
            variations = (0 until v.length()).map { v.getJSONObject(it).let { o -> Variation(o.getString("id"), o.getString("name"), o.getString("description")) } }
        } catch (e: Exception) {
            Log.w(tag, "목록을 불러오지 못함: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ 화면

    private var current: () -> Unit = {}
    /** 지금 화면의 위아래 스크롤(같은 화면을 다시 그릴 때 위치를 이어받는다). */
    private var scroller: ScrollView? = null

    private fun refresh(): Unit = current()

    /**
     * 화면 = [왼쪽 탭] + 위 내용(스크롤) + 아래 고정 동작 줄. 녹음 버튼은 어느 화면에서나 아래 가운데 같은 자리에 있다.
     * tabs: 큰 화면(녹음실·곡·목록·노래 듣기)에만 왼쪽 탭을 둔다. 카운트다운·녹음 중·오류 화면에는 없다(녹음 중 딴 데로 새지 않게).
     */
    private fun show(build: LinearLayout.() -> Unit, again: () -> Unit, tabs: Boolean = false,
                     header: (LinearLayout.() -> Unit)? = null, bar: (LinearLayout.() -> Unit)? = null) {
        // 같은 화면을 다시 그릴 때(상태 갱신·목록 새로 받기·주행 전환·장치 연결 등)는 스크롤 위치를 지킨다 — 2026-11-08 버그:
        // 스크롤을 내려 두면 갱신될 때마다 맨 위로 튀었다. 화면 종류는 again 의 클래스로 본다(같은 자리의 람다·함수 참조는 같은 클래스)
        val keepY = if (current.javaClass == again.javaClass) scroller?.scrollY ?: 0 else 0
        if (current.javaClass != again.javaClass) deleteArmed = null  // 지우기 확인은 다른 화면으로 가면 풀린다
        current = again
        halo = null
        glow = null
        scoreToken++
        onHome = false
        onList = false
        onDeviceCheck = false
        onSettings = false
        onPlayer = false
        homeFinalBar = null
        homeFinalText = null
        root.removeAllViews()
        val page = column()
        // 고정 머리: 목록 화면의 제목·보기 바꾸기는 스크롤해도 그대로(2026-11-10 사용자)
        if (header != null) page.addView(column().apply {
            setPadding(dp(PAGE_H), dp(PAGE_V), dp(PAGE_H), dp(12))
            try { header() } catch (e: Exception) { Log.e(this@MainActivity.tag, "머리를 그리지 못함", e) }
        })
        serverDownBar()?.let { b ->
            page.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(PAGE_H), dp(16), dp(PAGE_H), 0)
            })
        }
        val col = column().apply {
            setPadding(dp(PAGE_H), if (header != null) dp(4) else dp(PAGE_V), dp(PAGE_H), dp(PAGE_V))
            // 서버 응답에 필드가 빠졌거나(옛 곡 등) 형식이 다르면 여기서 막는다 — ui.post 안의 예외는 io 의 try 로 못 잡는다
            try {
                build()
            } catch (e: Exception) {
                Log.e(this@MainActivity.tag, "화면을 그리지 못함", e)
                removeAllViews()
                addView(headline("이 화면을 열지 못했어요"))
                addView(body("곡 정보가 일부 비어 있어요. 왼쪽 탭에서 다른 곡을 열거나 새로 흥얼거려 주세요."))
            }
        }
        val sv = NoJumpScrollView(this).apply { addView(col) }
        scroller = sv
        if (keepY > 0) sv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sv.viewTreeObserver.removeOnPreDrawListener(this)
                sv.scrollTo(0, keepY)  // 첫 그리기 전에 옮겨서 맨 위가 잠깐 보였다 내려가는 깜빡임이 없게
                return true
            }
        })
        page.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (bar != null) {
            page.addView(View(this).apply { setBackgroundColor(TINT_PRESSED) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            page.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(PAGE_H), dp(20), dp(PAGE_H), dp(20))
                try { bar() } catch (e: Exception) { Log.e(this@MainActivity.tag, "아래 줄을 그리지 못함", e) }
            })
        }
        // 탭이 없는 화면(카운트다운·녹음 중·오류 등)도 같은 폭의 빈 칸을 둔다 — 2026-11-09: 녹음실에서 누른 녹음 버튼이
        // 녹음 화면에서 옆으로 116px 옮겨 가 "녹음 완료"를 같은 자리에서 누를 수 없었다(주행 중 힐끗 보고 누르는 버튼)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(if (tabs) tabRail() else View(this@MainActivity).apply { setBackgroundColor(SURFACE) },
                    LinearLayout.LayoutParams(dp(TAB_W), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(this@MainActivity).apply { setBackgroundColor(TINT_PRESSED) }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(FrameLayout(this@MainActivity).apply {
                addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val floats = listOfNotNull(miniPlayer(), talkChip())
                if (floats.isNotEmpty()) addView(column().apply {
                    floats.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(dp(MINI_W), ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = dp(12) }) }
                }, FrameLayout.LayoutParams(dp(MINI_W), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(16); marginEnd = dp(24)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        })
        if (listening) root.addView(voiceOverlay(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** 지금 듣는 완성 노래(미니 플레이어용): 곡 id · 버전 id · 제목 · 표지 주소. */
    private var nowPlaying: JSONObject? = null

    /**
     * 노래를 틀어 둔 채 다른 화면으로 가면 오른쪽 위에 뜨는 작은 재생 칸(2026-11-10 사용자).
     * 표지 · 제목 · 일시정지/재생 · 열기 · 끄기. 그 노래의 노래 듣기 화면에서는 띄우지 않는다. 녹음을 시작하면 재생이 멈춰 같이 사라진다.
     */
    private fun miniPlayer(): View? {
        val np = nowPlaying ?: return null
        val key = playing ?: return null
        if (!key.startsWith("final:") || key.removePrefix("final:") != np.optString("version")) return null
        if (onPlayer) return null
        if (recording || counting) return null
        val p = activePlayer
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(SURFACE, 24, TINT_PRESSED)
            elevation = dp(8).toFloat()
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; background = round(TINT, 12); clipToOutline = true
                np.optString("cover").takeIf { it.isNotEmpty() }?.let { loadCover(it, COVER_SMALL) { b -> setImageBitmap(b) } }
            }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
            addView(column().apply {
                addView(text(np.optString("title"), BODY_STRONG, TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(text(if (p?.isPlaying == true) "재생 중" else "일시정지", BODY_S, TEXT3))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(if (p?.isPlaying == true) "⏸" else "▶", if (p?.isPlaying == true) "일시정지" else "재생") {
                activePlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                ui.postDelayed({ if (active) refresh() }, 150)
            })
            addView(iconButton("↗", "노래 듣기 화면 열기") {
                playerVersion = np.optString("version"); openSong(np.optString("song"), player = true)
            })
            addView(iconButton("✕", "재생 끄기") { stopPlayback(); refresh() })
        }
    }

    /** 지금 화면이 노래 듣기 화면인지(미니 플레이어를 띄우지 않는다). */
    private var onPlayer = false

    // ------------------------------------------------------------------ 왼쪽 탭

    enum class Tab(val label: String, val glyph: String) { STUDIO("녹음실", "●"), MELODIES("내 멜로디", "♪"), MUSIC("내 노래", "♫"), SETTINGS("설정", "⚙") }

    private var tab = Tab.STUDIO
    /** 목록 탭이 떠 있으면 목록을 새로 받았을 때 다시 그린다. */
    private var onList = false

    /**
     * 왼쪽 탭. 주 탭 세 칸(녹음실·내 노래·내 멜로디)은 화면 세로 가운데(2026-11-08 사용자 요청), 설정은 맨 아래에 따로
     * (2026-11-09 사용자: "설정 탭을 넣어야 할 것 같아" — 녹음 장치·서버 주소 등이 녹음 장치 화면 맨 아래에 숨어 있었다).
     */
    private fun tabRail() = FrameLayout(this).apply {
        setBackgroundColor(SURFACE)
        setPadding(dp(16), dp(PAGE_V), dp(16), dp(PAGE_V))
        fun cell(t: Tab) = column().apply {
            val on = t == tab
            gravity = Gravity.CENTER
            background = if (on) round(TINT_PRESSED, 20) else pressable(round(SURFACE, 20), round(TINT, 20))
            contentDescription = t.label
            setOnClickListener { openTab(t) }
            addView(text(t.glyph, TITLE, if (on) RECORDING.takeIf { t == Tab.STUDIO } ?: TEXT else TEXT3).apply { gravity = Gravity.CENTER })
            addView(text(t.label, if (on) BODY_S_STRONG else BODY_S, if (on) TEXT else TEXT2).apply { gravity = Gravity.CENTER }.withMargin(top = 4))
        }
        val main = listOf(Tab.STUDIO, Tab.MELODIES, Tab.MUSIC)  // 만드는 순서대로(2026-11-09 사용자): 녹음실 → 내 멜로디 → 내 노래
        addView(column().apply {
            main.forEach { t ->
                addView(cell(t), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TAB_H)).apply { if (t != main.last()) bottomMargin = dp(TAB_GAP) })
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        addView(cell(Tab.SETTINGS), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(SETTINGS_TAB_H), Gravity.BOTTOM))
    }

    private fun openTab(t: Tab) {
        tab = t
        when (t) {
            Tab.STUDIO -> { stackMode = null; showHome() }
            Tab.MUSIC -> { loadLibrary(); showMusicList() }
            Tab.MELODIES -> { loadLibrary(); showMelodyList() }
            Tab.SETTINGS -> showSettings()
        }
    }

    // ------------------------------------------------------------------ 설정 탭

    private var onSettings = false
    /** 내 음역대 지우기는 두 번 눌러야 한다(되돌릴 수 없다). */
    private var rangeResetArmed = false

    /**
     * 탭 4 · 설정(2026-11-09). 흩어져 있던 것을 한곳에: 녹음 장치 · 음악 담는 방법 · 내 음역대 · 서버 연결 ·
     * 녹음한 소리가 쓰이는 곳 · 도움말 · 앱 정보. 글자 입력(서버 주소)과 지우기는 정차 중에만.
     */
    private fun showSettings() {
        tab = Tab.SETTINGS
        show({
            onSettings = true
            addView(headline("설정"))
            addView(body("녹음 장치와 서버, 안내를 여기서 바꿔요. 글자 입력과 지우기는 정차 중에만 할 수 있어요."))

            addView(label("녹음"))
            addView(column().apply {
                background = round(SURFACE, 24)
                setPadding(dp(28), dp(20), dp(28), dp(24))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(column().apply {
                        addView(text("녹음 장치", BODY_STRONG, TEXT))
                        addView(text(inputDevice?.let(InputDevices::label) ?: DEFAULT_INPUT, BODY, TEXT2).withMargin(top = 4))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(button("🔍  녹음 장치 확인", size = Size.MEDIUM) { showDeviceCheck() })
                })
                addView(text("음악에서 따기 — 담는 방법", BODY_STRONG, TEXT).withMargin(top = 24, bottom = 8))
                val modes = listOf("auto", "direct", "mic")
                addView(segmented(listOf("자동", "재생 소리 직접", "마이크로"), modes.indexOf(prefs.getString(MUSIC_SOURCE, "auto")).coerceAtLeast(0)) { i ->
                    prefs.edit().putString(MUSIC_SOURCE, modes[i]).apply(); showSettings()
                })
                addView(text("지금 누르면: " + (if (musicNeedsCapture()) "재생 소리를 직접 담아요" else "마이크로 담아요"), BODY_S, TEXT3).withMargin(top = 10))
            }.withMargin(fill = true))

            addView(label("내 음역대"))
            addView(column().apply {
                background = round(SURFACE, 24)
                setPadding(dp(28), dp(20), dp(28), dp(24))
                val v = voiceRange
                addView(text(if (v.enough) "${VoiceRange.name(v.low!!)}–${VoiceRange.name(v.high!!)} · 허밍 ${prefs.getInt("voice_takes", 0)}번으로 잼"
                             else "아직 몰라요 — 흥얼거리면 쌓여요", BODY, TEXT))
                addView(text("다른 사람이 운전하거나 처음부터 다시 재고 싶으면 지우세요. 노래 목소리 '자동'이 이 값을 따라요.", BODY_S, TEXT3).withMargin(top = 6))
                addView(button(if (rangeResetArmed) "한 번 더 누르면 지워요" else "음역대 지우기", if (rangeResetArmed) Btn.SECONDARY else Btn.PRIMARY,
                               Size.MEDIUM, enabled = v.enough && !driving) {
                    if (!rangeResetArmed) { rangeResetArmed = true; showSettings(); return@button }
                    rangeResetArmed = false
                    prefs.edit().remove("voice_range").remove("voice_takes").apply()
                    showSettings()
                }.withMargin(top = 16))
            }.withMargin(fill = true))

            addView(label("서버 연결"))
            addView(serverCard().withMargin(fill = true))

            addView(label("녹음한 소리"))
            addView(voiceFacts(music = false).withMargin(fill = true))


            addView(label("앱 정보"))
            addView(text("차작 ${BuildConfig.VERSION_NAME} · 서버 ${api.base}", BODY, TEXT2))
            addView(caption("노래는 내 흥얼거림과 오픈 모델(로컬 서버)로 만들어요.", TEXT3))
        }, ::showSettings, tabs = true)
    }

    /** 녹음한 소리가 어디로 가서 무엇에 쓰이는지 — 첫 녹음 안내와 설정에서 같은 문장. 사실만. */
    private fun voiceFacts(music: Boolean) = column().apply {
        background = round(SURFACE, 24)
        setPadding(dp(32), dp(24), dp(32), dp(28))
        listOf(
            "보내는 곳" to "녹음한 ${if (music) "음악" else "흥얼거림"}은 노래를 만드는 차작 서버로 보내 음표로 바꿔요.",
            "남는 것" to "만든 곡과 녹음은 '내 노래'·'내 멜로디'에 남아 다시 듣고 이어서 만들 수 있어요. 필요 없으면 언제든 지울 수 있어요(서버에서도 지워져요).",
            "쓰지 않는 곳" to "AI 학습에 쓰지 않고, 다른 사람에게 공유하지 않아요.",
        ).forEachIndexed { i, (k, v) ->
            addView(text(k, BODY_STRONG, TEXT).withMargin(top = if (i == 0) 0 else 16))
            addView(text(v, BODY, TEXT2).withMargin(top = 4))
        }
    }

    /** 탭 2 · 내 노래: 완성된 노래를 드라이브 앨범(만든 때 — 출근길·퇴근길·밤·주말…)으로 묶어 보여 준다. 누르면 노래 듣기. */
    private fun showMusicList() {
        val songs = library.filter { !it.isNull("title") }
        val tiles = prefs.getString(MUSIC_VIEW, "tile") != "list"
        show({
            onList = true
            songs.groupBy { DriveTheme.momentOf(it.optString("created")) }.forEach { (moment, list) ->
            addView(label("$moment 앨범 · ${list.size}곡"))
            if (!tiles) {  // 리스트형: 한 줄에 한 곡(작은 표지 · 제목 · 길이·조성·날짜)
                list.forEach { item ->
                    val id = item.getString("id")
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = pressable(round(SURFACE, 24), round(TINT, 24))
                        setPadding(dp(20), dp(14), dp(28), dp(14))
                        setOnClickListener { openSong(id, player = true) }
                        addView(thumb(item), LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(24) })
                        addView(column().apply {
                            addView(text(item.getString("title"), BODY_STRONG, TEXT).apply { maxLines = 1 })
                            addView(text("${clock(item.optDouble("final_sec", 0.0).toFloat())} · ${keyOf(item)} · ${dateOf(item)}", BODY_S, TEXT3).withMargin(top = 4))
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(text("▶", TITLE, TEXT3))
                    }.withMargin(bottom = 12, fill = true))
                }
                return@forEach
            }
            addView(grid(list.map { item ->
                val id = item.getString("id")
                column().apply {
                    background = pressable(round(SURFACE, 24), round(TINT, 24))
                    setPadding(dp(16), dp(16), dp(16), dp(20))
                    setOnClickListener { openSong(id, player = true) }
                    addView(thumb(item), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MUSIC_COVER)))
                    addView(text(item.getString("title"), BODY_STRONG, TEXT).apply { maxLines = 1 }.withMargin(top = 12))
                    addView(text("${clock(item.optDouble("final_sec", 0.0).toFloat())} · ${keyOf(item)} · ${dateOf(item)}", BODY_S, TEXT3))
                }
            }, 4).withMargin(fill = true))
            }
        }, ::showMusicList, tabs = true, header = {
            addView(titleRow(headline("내 노래"), segmented(listOf("타일", "리스트"), if (tiles) 0 else 1, width = 360) {
                prefs.edit().putString(MUSIC_VIEW, if (it == 0) "tile" else "list").apply(); showMusicList()
            }))
            addView(body(if (songs.isEmpty()) "아직 완성된 노래가 없어요. 녹음실에서 흥얼거리고 노래 만들기를 눌러 보세요."
                         else "완성된 노래 ${songs.size}곡 · 만든 드라이브별 앨범"))
        })
    }

    /** 탭 3 · 허밍 리스트: 음표로 바꾼 내 멜로디(곡)들. 누르면 곡 화면(트랙·음표 보기·변주). */
    private fun showMelodyList() {
        show({
            onList = true
            library.forEach { item ->
                val id = item.getString("id")
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = pressable(round(SURFACE, 24), round(TINT, 24))
                    setPadding(dp(20), dp(16), dp(28), dp(16))
                    setOnClickListener { openSong(id) }
                    addView(thumb(item), LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(24) })
                    addView(column().apply {
                        addView(text(songName(item), BODY_STRONG, TEXT).apply { maxLines = 1 })
                        item.optJSONObject("mood")?.let { m -> addView(moodLine(m.optString("label"), "${m.optString("label")} · 에너지 ${m.optInt("energy")}").withMargin(top = 4)) }
                        addView(text(listOfNotNull(
                            "트랙 ${item.optInt("tracks")}개", clock(item.optDouble("duration_sec", 0.0).toFloat()),
                            "템포 ${item.optInt("tempo")}", keyOf(item), dateOf(item),
                            if (item.isNull("title")) null else "노래 있음",
                        ).joinToString(" · "), BODY_S, TEXT3).withMargin(top = 4))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text("›", TITLE, TEXT3))
                }.withMargin(top = 16, fill = true))
            }
        }, ::showMelodyList, tabs = true, header = {
            addView(titleRow(headline("내 멜로디"), LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("🗣 대화 보관함 ›", size = Size.MEDIUM) { talkPickFor = null; showTalks() }.withMargin(end = 16))
                addView(button("음악 녹음 보관함 ›", size = Size.MEDIUM) { showMusicLibrary() })
            }))
            addView(body(if (library.isEmpty()) "아직 만든 멜로디가 없어요. 녹음실에서 흥얼거려 보세요."
                         else "음표로 바꾼 내 멜로디 ${if (library.size >= LIBRARY_SIZE) "최근 " else ""}${library.size}개 · 눌러서 트랙·음표 보기"))
        })
    }

    /** 멜로디 성격 칩: "♪ 쓸쓸한 멜로디 ●●○○○ 에너지 28" + 아래에 이유 한 줄. 읽기만(주행 중에도 조작 없음). */
    private fun moodRow(mood: JSONObject?): View? {
        if (mood == null) return null
        val c = Mood.color(mood.optString("label"))
        return column().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round((c and 0x00FFFFFF) or 0x2E000000, 999)
                setPadding(dp(20), dp(8), dp(24), dp(8))
                addView(text("♪ " + Mood.name(mood), BODY_S_STRONG, TEXT))
                addView(text("   " + Mood.dots(mood.optInt("energy")), BODY_S, c))
                addView(text("  에너지 ${mood.optInt("energy")}", BODY_S, TEXT2))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            Mood.why(mood).takeIf { it.isNotEmpty() }?.let { addView(caption(it, TEXT3)) }
        }.withMargin(top = 12)
    }

    /** 성격 색 점 + 한 줄(목록·노래 만들기·완성 화면). */
    private fun moodLine(label: String?, line: String, color: Int = TEXT3) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text("● ", BODY_S, Mood.color(label)))
        addView(text(line, BODY_S, color))
    }

    private fun dateOf(item: JSONObject) = localCreated(item.optString("created"))?.let { c ->
        "%02d.%02d %02d:%02d".format(c.monthValue, c.dayOfMonth, c.hour, c.minute)
    } ?: item.optString("created")

    /** 곡 표지(없으면 ♪). 받아 둔 표지는 다시 받지 않는다. */
    private fun thumb(item: JSONObject): View = FrameLayout(this).apply {
        val img = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(TINT, 16)
            clipToOutline = true
        }
        addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (item.isNull("cover_url")) {
            addView(text("♪", TITLE, TEXT3).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return@apply
        }
        loadCover(item.getString("cover_url"), COVER_THUMB) { img.setImageBitmap(it) }
    }

    private var onHome = false
    /** 다음 화면 맨 위에 한 번만 보여 줄 한 줄(녹음 취소 등). */
    private var notice: String? = null

    private fun noticeView(): View? = notice?.let { n -> notice = null; caption(n, TEXT).withMargin(bottom = 8) }

    /**
     * 1단계 녹음실: 녹음 전에는 아무것도 고르지 않는다(악기·반주는 기본값, 다듬기는 2단계에서). 큰 빨간 버튼 하나.
     * 오른쪽 위 작게 녹음 장치, 오른쪽 아래 작게 "음악에서 따기"(듣던 노래에서 멜로디 따기 — 공유 주의).
     * 정차 중 최근 멜로디가 노래 없이 남아 있으면 "아까 멜로디로 노래 만들까요?" 카드(정차하면 이어서).
     */
    private fun showHome() {
        tab = Tab.STUDIO
        val stacking = stackMode != null && song != null
        show({
            onHome = true
            addView(steps(0))
            // 녹음 장치는 제목 줄 오른쪽 끝에(2026-11-10 사용자: "녹음 장치는 오른쪽 정렬")
            addView(titleRow(headline(if (stacking) (if (stackMode == "overlay") "겹쳐 부르기" else "이어 부르기") else "녹음실"),
                             if (testInputs.isEmpty()) inputPill() else View(this@MainActivity)))
            addView(body(if (stacking) "지금 멜로디 위에 얹을 멜로디를 흥얼거려 주세요. 박자와 조성은 곡에 맞춰 드려요."
                         else "떠오른 한 소절을 흥얼거려 보세요. 듣던 음악은 녹음하는 동안만 멈춰요."))
            addView(caption("5~30초 한 소절이면 가장 잘 돼요(최대 ${MAX_RECORD_SEC}초). 다 부르면 가운데 버튼을 누르세요.", TEXT3))
            if (!stacking) {
                resumeCard()?.let { addView(it) }
                finalJob?.let { job -> addView(homeFinalCard(job)) }
                addView(rangeCard())
            }
        }, ::showHome, tabs = true) {
            // 녹음 세 가지(2026-11-10 사용자): 왼쪽 대화 녹음 · 가운데 허밍 녹음 · 오른쪽 음악 녹음
            // 세 버튼은 가운데로 모은다(2026-11-10 사용자: "너무 멀어서 중앙에 붙여줘") — 양옆 칸 폭이 같아 허밍 버튼은 그대로 한가운데
            val hum = if (!canRecord) recordButton(Rec.DISABLED, "녹음은 정차 후에", onClick = null) else recordButton(Rec.READY, "허밍 녹음") { startRecording() }
            aboveButton(notice?.also { notice = null }, TEXT) {
                if (stacking) zones(button("내 멜로디로") { stackMode = null; showSong(false) }, listOf(hum), musicRecordButton(canRecord))
                else zones(null, listOf(talkRecordButton().fixedWidth(), hum, musicRecordButton(canRecord).fixedWidth()),
                           if (testInputs.isNotEmpty()) text("시험 모드 · 마이크 대신\n${testInputs[testIndex % testInputs.size]}", BODY_S, POSITIVE) else null, gap = 96)
            }
        }
    }

    /** 정차하면 이어서: 하루 안에 만든 멜로디가 노래 없이 남아 있으면 바로 노래 만들기로. 주행 중에는 안 띄운다(소리·움직임 없음). */
    private fun resumeCard(): View? {
        if (driving) return null
        val making = finalJob?.optString("song_id")
        val item = library.filter { it.isNull("title") && it.optString("id") != making && DriveTheme.withinHours(it.optString("created"), 24) }
            .maxByOrNull { it.optString("created") } ?: return null
        val id = item.optString("id")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(SURFACE, 24, POSITIVE)
            setPadding(dp(28), dp(20), dp(20), dp(20))
            addView(column().apply {
                addView(text("아까 만든 멜로디로 노래를 만들까요?", BODY_STRONG, TEXT))
                addView(text("${songName(item)} · ${clock(item.optDouble("duration_sec", 0.0).toFloat())}", BODY_S, TEXT3).withMargin(top = 4))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("노래 만들기 →", Btn.SECONDARY, Size.MEDIUM) { openSong(id, create = true) })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
        }
    }

    private fun showCountdown(n: Int): Unit = show({
        // 0 = '띠링' 이 울리는 순간 — 녹음이 열리기 직전 화면에도 "지금"을 보여 준다
        addView(headline(if (n == 0) "지금 흥얼거리세요!" else "곧 녹음을 시작해요"))
    }, {}) {
        // 시작 전에도 취소는 녹음 중과 같은 자리(왼쪽)에 — 가운데 숫자 버튼을 눌러도 취소된다. 안내 문구는 녹음 버튼 바로 위
        aboveButton("3·2·1 '삑' 소리 뒤에 높은 '띠링' 소리가 나면 바로 흥얼거리세요. 시작 전에 그만두려면 녹음 취소를 누르세요.", TEXT) {
            zones(button("✕  녹음 취소") { cancelCountdown() }, listOf(recordButton(Rec.COUNTDOWN, "녹음 취소", count = n) { cancelCountdown() }))
        }
    }

    private lateinit var remain: TextView
    private lateinit var hearing: TextView

    /** 녹음 중: 소리가 들리는지(hearing 문구)와, 흥얼거림이 들릴 때 녹음 버튼 둘레에 번지는 원으로 반응한다. */
    private fun showRecording(music: Boolean = false, direct: Boolean = false): Unit = show({
        // "녹음 중"·시간은 아래 줄 오른쪽, 소리 상태 문구("잘 들려요"·"마이크에서 소리가 전혀…")는 녹음 버튼 바로 위 —
        // 2026-11-09 사용자: 녹음과 관련된 글은 녹음 버튼 가까이. 위에는 파형·음역만
        // 가운데 목소리 원(2026-11-10 사용자: 테두리 글로우가 소리와 상관없이 늘 보여서 → 대화 녹음과 같은 효과로) + 파형
        recOrb = VoiceOrb(this@MainActivity).also {
            it.color = if (music) POSITIVE else ACTIVE
            it.calm = driving
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(REC_ORB_H)))
        }
        wave = LevelStrip(this@MainActivity).also {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        liveRange = if (music) null else RangeBar(this@MainActivity, faces.getOrPut(BODY_S_STRONG.font) { resources.getFont(BODY_S_STRONG.font) }).also {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        }
        addView(body(when {
            !music -> "다 부르면 가운데 버튼이나 스티어링휠 재생·일시정지 키를 누르세요. 녹음 장치: ${inputDevice?.let(InputDevices::label) ?: DEFAULT_INPUT}"
            direct -> "재생 중인 음악을 그대로 담고 있어요(음악은 멈추지 않아요). 원하는 부분이 끝나면 가운데 버튼을 누르세요. 남의 노래 멜로디는 내 노래로 공유하지 마세요."
            else -> "차량 마이크로 음악을 듣고 있어요. 원하는 부분이 끝나면 가운데 버튼을 누르세요."
        }).withMargin(top = 12))
    }, {}) {
        // 녹음 세 동작: 시작(녹음실 가운데) · 완료(가운데 — 바로 음표로) · 취소(왼쪽 — 녹음을 버리고 돌아감).
        // 오른쪽: 녹음 중 표시와 시간(눈이 녹음 버튼 근처에 머물게)
        orientation = LinearLayout.VERTICAL
        hearing = text("", TITLE, TEXT3).apply { gravity = Gravity.CENTER }
        addView(hearing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
        remain = text("", TITLE, TEXT).apply { gravity = Gravity.END }
        val status = column().apply {
            gravity = Gravity.END
            // 세로 줄에 그냥 넣으면 폭이 MATCH_PARENT 가 되어 시간 글자 폭에 맞춰 잘렸다("●"만 보임) — 둘 다 글자 폭대로
            addView(text(if (music) "●  음악 녹음 중" else "●  녹음 중", HEADLINE, RECORDING).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
            addView(remain, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END; topMargin = dp(4) })
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            zones(button("✕  녹음 취소") { cancelRecording() }, listOf(recordButton(Rec.RECORDING, "녹음 완료") { recorder.stop() }), status)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        hearingState = Hearing.WAITING
        hearing.text = ""
        setHearing(Hearing.WAITING)
    }

    /** 흥얼거림이 들리는 동안 녹음 버튼 둘레로 빨간 고리가 퍼져 나간다. */
    private fun ripple() {
        val frame = halo?.parent as? FrameLayout ?: return
        val ring = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(4), pitchColor) }
            scaleX = HALO_MIN; scaleY = HALO_MIN; alpha = 0.8f
        }
        frame.addView(ring, 1, FrameLayout.LayoutParams(dp(RING), dp(RING), Gravity.CENTER))
        ring.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(RIPPLE_MS).withEndAction { frame.removeView(ring) }.start()
    }

    private var hearingState = Hearing.WAITING
    private var hearingStage = -1

    /**
     * 흥얼거린 시간 단계(2026-11-09): 0 = 5초 전, 1 = 서버가 바라는 12초 전,
     * 2 = 30초 전, 3 = 그 뒤, 4 = 자동으로 멈추기 10초 전. 짧게 부르고 끝내면 멜로디가 모자라 노래가 단조로워진다.
     */
    private fun encourageStage(elapsedMs: Long) = when {
        elapsedMs >= (MAX_RECORD_SEC - 10) * 1000L -> 4
        elapsedMs >= 30_000 -> 3
        elapsedMs >= 12_000 -> 2
        elapsedMs >= 5_000 -> 1
        else -> 0
    }

    private fun setHearing(state: Hearing, elapsedMs: Long = 0) {
        val stage = if (musicCapturing) 0 else encourageStage(elapsedMs)
        if (state == hearingState && stage == hearingStage && hearing.text.isNotEmpty()) return
        hearingState = state
        hearingStage = stage
        // 음악에서 따기는 흥얼거리는 게 아니라 재생 소리를 담는다 — 문구만 음악용으로(2026-11-07 "흥얼거려 보세요"가 떴다)
        val msg = when {
            musicCapturing -> state.music
            state == Hearing.VOICE -> ENCOURAGE[stage]
            state == Hearing.QUIET && stage == 0 -> QUIET_TOO_SHORT
            else -> state.message
        }
        // 초록(ACTIVE) 글자는 바탕 대비 2.2:1 이라 읽기 어렵다 — 글자는 진하게, 앞의 ● 점만 초록(UX 가이드 4-2)
        hearing.text = if (state == Hearing.VOICE) dot(msg, ACTIVE) else msg
        hearing.setTextColor(if (state == Hearing.VOICE) TEXT else TEXT3)
    }

    private fun noSignalMic() = "마이크에서 소리가 전혀 들어오지 않았어요. 흥얼거림이 작아서가 아니라, 마이크가 연결되지 않았거나 소리가 안 들어오는 장치가 골라져 있을 때 이래요. " +
        "녹음 장치 확인에서 소리가 들어오는 장치를 골라 주세요." +
        (if (DeviceCheck.emulator) " (에뮬레이터: PC 에 실제 마이크가 연결돼 있고 Windows 기본 녹음 장치로 잡혀 있는지 확인해 주세요.)" else "")

    /** 녹음·변환에서 난 오류면 가운데 녹음 버튼으로 같은 설정 그대로 다시 부른다. 변주·트랙 빼기 오류면 곡으로 돌아간다. */
    private fun showError(message0: String, deviceHelp: Boolean = false, settingsHelp: Boolean = false) {
        val retry = song == null || stackMode != null
        val music = lastUploadMusic
        // 음악 녹음 실패인데 "흥얼거려 주세요"가 나왔다(2026-11-10 사용자) — 음악용 안내로
        val message = if (music && message0.contains("멜로디를 찾지 못했어요"))
            "음악에서 멜로디를 찾지 못했어요. 노래(목소리)가 또렷한 부분을 10초 넘게 담아 주세요. 이어폰 마이크는 소음 제거로 스피커 음악을 지울 수 있어요 — 차 안에서 튼 음악은 '재생 소리 직접'으로 담으면 정확해요."
            else message0
        show({
            addView(headline("다시 한 번 해 볼까요?"))
            // 다시 녹음할 수 있는 오류는 이유를 녹음 버튼 바로 위에(2026-11-09 사용자) — 여기서는 제목만
            if (!retry) addView(text(message, BODY, TEXT))
        }, { showError(message0, deviceHelp, settingsHelp) }, tabs = true) {  // 탭은 그대로(2026-11-10 사용자: 탭이 사라져 당황)
            val back = when {
                settingsHelp -> button("⚙  설정") { showSettings() }
                deviceHelp -> button("🔍  녹음 장치 확인") { showDeviceCheck() }
                song != null && stackMode != null -> button("곡으로 돌아가기") { stackMode = null; showSong(false) }
                song != null -> button("곡으로 돌아가기") { showSong(false) }
                else -> button("← 녹음실") { showHome() }
            }
            when {
                !retry -> zones(null, listOfNotNull(back.withMargin()))
                else -> aboveButton(message, TEXT) { zones(back, listOf(
                    if (music) recordButton(Rec.READY, "다시 음악 녹음") { startMusicRecording() }
                    else recordButton(Rec.READY, "다시 녹음 시작") { startRecording() })) }
            }
        }
    }

    /**
     * 흥얼거리기 요령 네 가지 + 음 따라 부르기 연습(노래방 정밀 채점처럼). 처음 켰을 때 한 번 저절로 뜬다.
     * 연습: 안내 멜로디를 막대와 함께 먼저 들려줌 → 3·2·1 → 막대를 보며 따라 부름(소리는 안 냄 — 마이크에 섞이지 않게)
     * → 음표마다 맞음(초록)·아깝다(노랑)·틀림(빨강) + 점수. 연습 녹음은 서버로 보내지 않고 지운다.
     */
    private fun showTutorial() {
        fun done() { cancelDrill(); prefs.edit().putBoolean(TUTORIAL_SEEN, true).apply(); showHome() }
        val roll = drillRoll ?: newDrillRoll().also { drillRoll = it }
        show({
            addView(headline("흥얼거리기 요령"))
            addView(grid(TUTORIAL.mapIndexed { i, (title, desc) ->
                column().apply {
                    background = round(SURFACE, 24)
                    setPadding(dp(28), dp(24), dp(28), dp(28))
                    addView(text("${i + 1}  $title", BODY_STRONG, TEXT))
                    addView(text(desc, BODY_S, TEXT3).withMargin(top = 8))
                }
            }, 4).withMargin(top = 16, fill = true))
            addView(label("음 따라 부르기 연습"))
            (roll.parent as? ViewGroup)?.removeView(roll)
            addView(roll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(DRILL_ROLL)))
            drillStatus = text("", if (drill == Drill.RESULT) TITLE else BODY_STRONG, if (drill == Drill.RESULT) TEXT else TEXT2).withMargin(top = 16)
                .apply { text = if (drill == Drill.RESULT) dot("●  " + drillText(), if (drillGood) ACTIVE else RECORDING) else drillText() }
            addView(drillStatus)
        }, ::showTutorial) {
            zones(button(if (prefs.getBoolean(TUTORIAL_SEEN, false)) "돌아가기" else "건너뛰기") { done() },
                listOf(when {
                    driving -> recordButton(Rec.DISABLED, "연습은 정차 후에", onClick = null)
                    drill == Drill.GUIDE -> recordButton(Rec.BUSY, "먼저 들어요", onClick = null)
                    drill == Drill.COUNT -> recordButton(Rec.COUNTDOWN, "따라 부를 준비", count = drillCount) { cancelDrill(); showTutorial() }
                    drill == Drill.SING -> recordButton(Rec.RECORDING, "연습 완료") { recorder.stop() }
                    else -> recordButton(Rec.READY, if (drill == Drill.RESULT) "다시 연습" else "듣고 따라 부르기") { startDrill() }
                }),
                button("시작하기", Btn.SECONDARY) { done() })
        }
    }

    private fun drillText() = when (drill) {
        Drill.IDLE -> "가운데 버튼을 누르면 멜로디를 먼저 들려 드려요. 3·2·1 뒤에 막대를 보며 같은 음으로 '음~' 따라 불러 보세요. 연습 녹음은 서버로 보내지 않아요."
        Drill.GUIDE -> "잘 들어 보세요. 빨간 막대가 지금 나오는 음이에요."
        Drill.COUNT -> "이제 따라 불러요. 소리는 나오지 않으니 막대를 보며 부르세요."
        Drill.SING -> "막대에 맞춰 '음~' · 파란 점이 내가 낸 음이에요."
        Drill.RESULT -> drillMessage
    }

    private fun newDrillRoll() = NoteRoll(this, PRACTICE, PRACTICE.maxOf { it.start + it.dur }, faces.getOrPut(BODY_S_STRONG.font) { resources.getFont(BODY_S_STRONG.font) })
        .apply { background = round(SURFACE, 24) }

    enum class Drill { IDLE, GUIDE, COUNT, SING, RESULT }

    private var drill = Drill.IDLE
    private var drillCount = 3
    private var drillRoll: NoteRoll? = null
    private var drillStatus: TextView? = null
    private var drillMessage = ""
    private var drillGood = false
    private var drillToken = 0
    private var guide: AudioTrack? = null

    /** 1) 안내 멜로디를 막대와 함께 들려준다. 차 음악은 오디오 포커스로 잠깐 멈춘다. */
    private fun startDrill() {
        if (driving) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_DRILL)
        stopPlayback()
        if (!holdFocus()) return showError(FOCUS_DENIED)
        val token = ++drillToken
        val roll = newDrillRoll().also { drillRoll = it }
        drill = Drill.GUIDE
        showTutorial()
        guide = GuideTone.play(PRACTICE)
        val t0 = System.currentTimeMillis()
        val end = PRACTICE.maxOf { it.start + it.dur }
        fun tick() {
            if (token != drillToken) return
            val t = (System.currentTimeMillis() - t0) / 1000f
            roll.playhead = t
            if (t < end + 0.3f) {
                ui.postDelayed(::tick, 30)
                return
            }
            guide?.release(); guide = null
            roll.playhead = -1f
            countDrill(token, 3)
        }
        tick()
    }

    /** 2) 3·2·1 */
    private fun countDrill(token: Int, n: Int) {
        if (token != drillToken) return
        if (n == 0) {
            beep(true)
            ui.postDelayed({ if (token == drillToken) singDrill(token) }, BEEP_GAP_MS)
            return
        }
        drill = Drill.COUNT
        drillCount = n
        showTutorial()
        beep(false)
        ui.postDelayed({ countDrill(token, n - 1) }, 1000)
    }

    /**
     * 3) 따라 부르기: 녹음하면서 50ms마다 잡힌 음높이를 지금 음표와 비교한다(옥타브는 무시 — 낮은 목소리도 같은 음으로 본다).
     * 반음의 절반(±50센트) 안이면 맞음, 한 반음 안이면 아깝다. 음표 시간 중 맞은 비율로 막대 색을 정한다.
     */
    private fun singDrill(token: Int) {
        drill = Drill.SING
        showTutorial()
        val roll = drillRoll ?: return
        roll.showCurrent = false
        val notes = roll.notes
        val frames = IntArray(notes.size)
        val good = IntArray(notes.size)
        val close = IntArray(notes.size)
        var offSum = 0f
        var offN = 0
        val end = notes.maxOf { it.start + it.dur }
        val file = File(cacheDir, "drill.wav")
        val t0 = System.currentTimeMillis()
        fun grade(i: Int) {
            val ratio = (good[i] + 0.5f * close[i]) / maxOf(1, frames[i])
            roll.grades[i] = if (ratio >= 0.6f) NoteRoll.GOOD else if (ratio >= 0.3f) NoteRoll.CLOSE else NoteRoll.MISS
        }
        recording = true
        rec.execute {
            try {
                recorder.record(file, end.toInt() + 2, device = inputDevice) { db, midi ->
                    ui.post {
                        if (token != drillToken) return@post
                        val t = (System.currentTimeMillis() - t0) / 1000f
                        roll.playhead = t
                        val i = notes.indexOfFirst { t >= it.start && t < it.start + it.dur }
                        if (i >= 0) {
                            frames[i]++
                            if (midi != null && db > VOICE_DB) {
                                val off = ((midi - notes[i].midi) % 12 + 18) % 12 - 6
                                roll.addSung(t, notes[i].midi + off)
                                if (abs(off) <= 0.5f) good[i]++ else if (abs(off) <= 1f) close[i]++
                                offSum += off; offN++
                                drillStatus?.text = "지금 ${NoteRoll.NAMES[midi.roundToInt().mod(12)]} · 목표 ${NoteRoll.NAMES[notes[i].midi % 12]}"
                            }
                        }
                        notes.indices.filter { notes[it].start + notes[it].dur < t && it !in roll.grades }.forEach(::grade)
                        if (t > end + 0.2f) recorder.stop()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "연습 녹음 실패", e)
            }
            file.delete()
            ui.post {
                recording = false
                if (token != drillToken) return@post
                notes.indices.filter { it !in roll.grades }.forEach(::grade)
                val score = (notes.indices.sumOf { i -> minOf(1f, (good[i] + 0.5f * close[i]) / maxOf(1, frames[i]) / 0.8f).toDouble() } / notes.size * 100).roundToInt()
                val off = if (offN > 0) offSum / offN else 0f
                drillGood = score >= 80
                drillMessage = when {
                    offN < 10 -> "소리가 잘 안 들렸어요. 마이크 가까이에서 '음~' 하고 불러 보세요."
                    drillGood -> "정확도 ${score}점! 음을 잘 맞췄어요."
                    off < -0.35f -> "정확도 ${score}점. 전체적으로 조금 낮아요. 살짝 높여 불러 보세요."
                    off > 0.35f -> "정확도 ${score}점. 전체적으로 조금 높아요. 살짝 낮춰 불러 보세요."
                    else -> "정확도 ${score}점. 빨간 막대 음을 다시 듣고 따라 해 보세요."
                }
                drill = Drill.RESULT
                releaseFocus()
                showTutorial()
            }
        }
    }

    /** 연습 중단(화면 이탈·건너뛰기·카운트다운 취소). */
    private fun cancelDrill() {
        if (drill == Drill.IDLE || drill == Drill.RESULT) return
        drillToken++
        guide?.release(); guide = null
        if (drill == Drill.SING) recorder.stop()
        releaseFocus()
        drill = Drill.IDLE
        drillRoll = null
    }

    private var scoreRoll: NoteRoll? = null
    private var scoreNow: TextView? = null
    private var scoreToken = 0

    /** 음표 보기(노래방 음정 막대처럼): 트랙 음표를 계이름 막대로, 들으면 지금 음을 빨갛게 + 오른쪽에 크게. */
    private fun showScore(t: JSONObject) {
        val n = t.getInt("n")
        val m = t.getJSONArray("score")
        val notes = (0 until m.length()).map { m.getJSONObject(it).let { o -> NoteRoll.Note(o.getDouble("start").toFloat(), o.getDouble("dur").toFloat(), o.getInt("midi")) } }
        val key = "track$n"
        show({
            addView(headline("음표 보기 · 트랙 $n"))
            addView(body("막대 하나가 음 하나예요. 위로 갈수록 높은 음이에요. 들으면 지금 음이 빨갛게 표시돼요."))
            val roll = NoteRoll(this@MainActivity, notes, maxOf(1f, notes.maxOfOrNull { it.start + it.dur } ?: 1f), faces.getOrPut(BODY_S_STRONG.font) { resources.getFont(BODY_S_STRONG.font) })
                .apply { background = round(SURFACE, 24) }
            scoreRoll = roll
            scoreNow = text("", COUNT, RECORDING).apply { gravity = Gravity.CENTER }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(roll, LinearLayout.LayoutParams(0, dp(ROLL), 1f))
                addView(column().apply {
                    gravity = Gravity.CENTER
                    background = round(SURFACE, 24)
                    addView(text("지금 음", BODY_S_STRONG, TEXT3))
                    addView(scoreNow)
                }, LinearLayout.LayoutParams(dp(240), dp(ROLL)).apply { marginStart = dp(24) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        }, { showScore(t) }, tabs = true) {
            zones(button("← 내 멜로디") { stopPlayback(); showSong(false) },
                listOf(button(if (playing == key) "■  멈춤" else "▶  이 트랙 듣기", Btn.SECONDARY) { toggle(key, t.getString("audio_url")) }))
        }
        val token = scoreToken
        fun tick() {
            if (token != scoreToken) return
            val pos = if (playing == key && prepared) activePlayer?.currentPosition?.div(1000f) ?: -1f else -1f
            scoreRoll?.playhead = pos
            scoreNow?.text = scoreRoll?.current?.let { NoteRoll.NAMES[it.midi % 12] } ?: ""
            ui.postDelayed(::tick, 40)
        }
        tick()
    }

    /**
     * 2단계 내 멜로디: 왼쪽 = 내 멜로디(트랙·음표 보기·전체 듣기), 오른쪽 = AI 제안 + 다듬기 + 더 만들기(접힘).
     * 아래 가운데 큰 버튼은 "노래로 만들기 →"(3단계). 만드는 중(pending)이면 새 트랙 자리를 먼저 그리고 다른 동작은 잠근다.
     */
    private fun showSong(autoplay: Boolean): Unit {
        val s = song
        val p = pending
        if (s == null && p == null) return showHome()
        val tracks = s?.getJSONArray("tracks")
        val count = tracks?.length() ?: 0
        val idle = p == null && !finalSubmitting
        show({
            noticeView()?.let { addView(it) }
            addView(when {
                s == null || p != null -> steps(1, busy = true)
                s.optJSONObject("final") != null -> steps(3, done = true)
                else -> steps(1)
            })
            val left = column()
            left.addView(headline(s?.let { songName(it, "내 멜로디") } ?: "새 멜로디"))
            left.addView(body(if (s == null) "흥얼거림을 음표로 바꾸고 있어요"
                              else "템포 ${s.optInt("tempo")} · ${keyOf(s)} · 트랙 ${count}개"))
            // 음표에서 잰 멜로디 성격 — 노래 만들기가 이 느낌에 맞춰 스타일·가사 말투를 고른다
            moodRow(s?.optJSONObject("mood"))?.let { left.addView(it) }
            trackBars.clear()
            songBar = null
            if (s != null) {
                val bar = progressBar()
                songBar = bar
                songTime = text("0:00 / ${clock(s.optDouble("duration_sec", 0.0).toFloat())}", BODY_S_STRONG, TEXT3)
                left.addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(bar, LinearLayout.LayoutParams(0, dp(8), 1f).apply { marginEnd = dp(16) })
                    addView(songTime)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            }
            val final = s?.optJSONObject("final")
            if (final != null) {
                left.addView(label("완성된 노래"))
                left.addView(finalRow(final))
            }
            left.addView(label("트랙 · 음표로 바꾼 멜로디 · 최신 순"))
            // 새로 만든 것이 맨 위(2026-11-10 사용자: 새로 만들었는데 가장 오래된 원본부터 나와 불편) — 만드는 중인 트랙도 맨 위
            if (p != null) left.addView(pendingRow(p))
            for (i in count - 1 downTo 0) left.addView(trackRow(tracks!!.getJSONObject(i)))

            val right = column()
            // AI 제안은 설명을 읽고 골라야 해서 주행 중에는 숨긴다(정차하면 다시 보인다)
            if (s != null && !driving) right.addView(suggestCard(Suggest.melody(s), idle))
            val polish = variations.filter { it.id in POLISH }
            if (polish.isNotEmpty()) {
                right.addView(label("다듬기"))
                right.addView(grid(polish.map { v -> button(v.name, Btn.PRIMARY, size = Size.MEDIUM, enabled = idle) { addVariation(v) }.apply { setPadding(dp(16), 0, dp(16), 0) } }, 2))
                right.addView(caption("멜로디 완성하기: 음계·박자를 맞추고 끝을 닫아요. AI로 살리기: 허밍은 참고만, AI가 더 노래다운 8마디로 새로 써요(약 10초).", TEXT3))
            }
            right.addView(button(if (showMore) "더 만들기 접기 ▴" else "더 만들기 · 악기 · 겹쳐 부르기 · 변주 ▾", size = Size.MEDIUM) {
                showMore = !showMore; refresh()
            }.withMargin(top = 24))
            if (showMore) right.addView(morePanel(idle, count))
            right.addView(button("새로 흥얼거리기", size = Size.MEDIUM, enabled = idle) { stopPlayback(); song = null; stackMode = null; showHome() }.withMargin(top = 16))
            if (s != null) {
                val key = "song:" + s.optString("id")
                val made = s.optJSONArray("finals")?.length() ?: (if (s.optJSONObject("final") != null) 1 else 0)
                right.addView(button(if (deleteArmed == key) "한 번 더 누르면 지워요" else "이 멜로디 지우기", size = Size.MEDIUM, enabled = idle && !driving) {
                    if (deleteArmed != key) { deleteArmed = key; refresh(); return@button }
                    deleteArmed = null; deleteSong(s.optString("id"))
                }.withMargin(top = 16))
                if (deleteArmed == key) right.addView(caption(
                    (if (made > 0) "이 멜로디로 만든 노래 ${made}개도 함께 지워져요. " else "") + "지운 뒤에는 되돌릴 수 없어요.", RECORDING).withMargin(top = 8))
            }

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { marginEnd = dp(64) })
                addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }, { showSong(false) }, tabs = true) {
            val play = s?.let { button(if (playing == "mix") "■  멈춤" else "▶  전체 듣기") { toggle("mix", it.optString("audio_url")) } }
            val done = s?.optJSONObject("final") != null
            zones(play,
                listOf(when {
                    s == null || !idle -> recordButton(Rec.BUSY, if (p != null) "음표로 바꾸는 중" else "노래 만드는 중", onClick = null)
                    done -> button("완성된 노래 →", Btn.SECONDARY) { showPlayer(false) }
                    // 버튼 한 번: 주제는 서버가 아무거나·자동 목소리로 바로 만든다(주행 중에도 됨). 바꾸고 싶으면 오른쪽 작은 버튼
                    else -> button("바로 노래 만들기", Btn.SECONDARY) { quickCreate() }
                }),
                if (s != null && idle) button(if (done) "새 버전 만들기" else "주제·목소리 정하기", size = Size.MEDIUM) { showCreate() } else null)
        }
        // 방금 만든 결과는 새 트랙부터 들려준다(전에는 전체 섞은 소리를 원본 트랙부터 틀었다)
        if (autoplay && s != null) {
            val last = s.optJSONArray("tracks")?.let { a -> if (a.length() > 0) a.getJSONObject(a.length() - 1) else null }
            if (last != null && (s.optJSONArray("tracks")?.length() ?: 0) > 1) toggle("track" + last.optInt("n"), last.optString("audio_url"))
            else toggle("mix", s.optString("audio_url"))
        }
        trackProgress(s?.optDouble("duration_sec", 0.0)?.toFloat() ?: 0f)
    }

    private var showMore = false

    /** 내 멜로디에서 바로 노래 만들기: 이 곡 주제를 아직 안 정했으면 비워 둔 채(서버가 아무 주제나 고른다) 곧장 만든다. */
    private fun quickCreate() {
        val s = song ?: return
        if (promptFor != s.optString("id")) { prompt = ""; themeFromDrive = false; promptFor = s.optString("id") }
        createFinal()
    }

    /** 더 만들기(접힘): 다음에 얹을 악기 · 겹쳐/이어 부르기 · 변주 · 마지막 트랙 빼기. 주행 중에는 잠근다(정차해서). */
    private fun morePanel(idle: Boolean, count: Int) = column().apply {
        background = round(SURFACE, 24)
        setPadding(dp(28), dp(8), dp(28), dp(24))
        val ok = idle && !driving
        if (driving) addView(caption("주행 중에는 듣기와 다듬기만 돼요. 정차한 뒤 더 만들어 보세요.", TEXT3))
        addView(label("얹을 악기"))
        addView(grid(INSTRUMENTS.mapIndexed { i, ins -> button(ins.first, if (i == instrument) Btn.SECONDARY else Btn.PRIMARY, Size.MEDIUM) { instrument = i; saveDraft(); refresh() } }, 3))
        addView(label("한 소절 더 얹기"))
        addView(row(
            segmented(listOf("겹쳐 부르기", "이어 부르기"), if (stackChoice == "overlay") 0 else 1, width = STACK_TOGGLE) {
                stackChoice = if (it == 0) "overlay" else "append"; refresh()
            },
            button("흥얼거려 얹기", size = Size.MEDIUM, enabled = ok && canRecord) { stackMode = stackChoice; showHome() },
        ))
        addView(label("변주 · 다시 안 불러도 돼요"))
        addView(grid(variations.filter { it.id !in POLISH }.map { v -> button(v.name, size = Size.MEDIUM, enabled = ok) { addVariation(v) }.apply { setPadding(dp(16), 0, dp(16), 0) } }, 3))
        if (count > 1) addView(button("마지막 트랙 빼기", size = Size.MEDIUM, enabled = ok) { deleteLast() }.withMargin(top = 16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    /** AI 제안 카드: 고칠 점 2~3개, 줄마다 바로 적용 버튼. */
    private fun suggestCard(list: List<Suggestion>, enabled: Boolean, onRemix: ((String) -> Unit)? = null) = column().apply {
        background = round(SURFACE, 24, POSITIVE)
        setPadding(dp(28), dp(20), dp(28), dp(20))
        addView(text("✦ AI 제안", BODY_S_STRONG, POSITIVE))
        list.forEach { sg ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(sg.text, BODY_S, TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(16) })
                addView(button(sg.label, size = Size.MEDIUM, enabled = enabled) { applySuggestion(sg, onRemix) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun applySuggestion(sg: Suggestion, onRemix: ((String) -> Unit)?) {
        Log.i(tag, "AI 제안 적용: ${sg.action}")
        when {
            sg.action == "create" -> showCreate()
            sg.action == "rehum" -> { stopPlayback(); song = null; stackMode = null; showHome() }
            sg.action.startsWith("remix:") -> onRemix?.invoke(sg.action.removePrefix("remix:"))
            else -> variations.firstOrNull { it.id == sg.action }?.let { addVariation(it) }
        }
    }

    /**
     * 3단계 노래 만들기: 주제(오늘 드라이브로 미리 채움 · 🎲 · 🎤) + 목소리(자동 = 내 음역 쪽) + 길이, 세부(접힘).
     * 아래 가운데 큰 버튼 Create music. 만드는 동안 진행 카드(다른 화면 봐도·주행해도 계속).
     */
    private fun showCreate() {
        val s = song ?: return showHome()
        val job = finalJob?.takeIf { it.optString("song_id") == s.optString("id") }
        val making = job != null || finalSubmitting
        // 다른 곡의 노래 만들기를 처음 열면 주제를 오늘 드라이브로 채운다(남아 있던 지난 주제 대신). 같은 곡에서 고친 주제는 그대로
        // 주제는 미리 채우지 않는다(2026-11-11 사용자: 요일·시간으로 채우니 곡이 다 비슷하다) — 비우면 서버가 아무 주제나, 아래 예시를 눌러도 된다
        if (!making && promptFor != s.optString("id")) { prompt = ""; themeFromDrive = false; promptFor = s.optString("id"); themeExamples = emptyList() }
        show({
            addView(steps(2, busy = making))
            addView(headline("노래 만들기"))
            addView(body("${songName(s, "내 멜로디")} · 템포 ${s.optInt("tempo")} · ${keyOf(s)} → 2분 노래(가사·앨범 표지)"))
            if (job != null || finalSubmitting) {
                // 만드는 동안엔 설정 칸을 감추고 진행만 크게(다른 화면을 봐도·주행을 시작해도 계속)
                addView(label("만드는 중 · 주제: $prompt"))
                autoPickNote?.let { addView(caption(it, POSITIVE).withMargin(bottom = 8)) }
                if (job != null) addView(finalPendingRow(job)) else addView(body("서버에 보내는 중이에요…"))
                return@show
            }
            addView(label("주제"))
            addView(promptRow(enabled = !making))
            addView(caption("비워 두면 아무 주제나 골라요. 아래 예시를 누르거나 🎤 로 말해도 돼요.", TEXT3))
            addView(themeExampleRow(enabled = !making))
            addView(talkCard(s))
            s.optJSONObject("mood")?.let { m ->
                addView(moodLine(m.optString("label"),
                    if (followMood) "${Mood.name(m)}에 맞춰 ${m.optString("genre")} 쪽으로 만들어요. 주제에 장르를 적으면 그 장르를 따라요."
                    else "멜로디 느낌은 따르지 않고 주제만 보고 만들어요(세부 설정).", TEXT2).withMargin(top = 12))
            }
            val type = voiceRange.type()
            addView(label("목소리" + (type?.let { " · 자동이면 내 음역(${it.name} 쪽)에 맞춰 ${if (it.male) "남성" else "여성"}" } ?: "")))
            val voices = VOICE_NAMES.keys.toList()
            addView(segmented(VOICE_NAMES.values.toList(), voices.indexOf(voice), width = VOICE_W) { voice = voices[it]; saveDraft(); refresh() })
            if (voice == "rap") addView(caption("랩: 허밍 멜로디 대신 새 힙합 비트 위에서 빠르게 랩해요. 가사도 줄 끝 각운을 맞춘 랩 가사로 써요.", TEXT3))
            addView(label("곡 길이"))
            addView(segmented(listOf("1분", "2분", "3분"), finalSeconds / 60 - 1, width = OPTION_W) { finalSeconds = (it + 1) * 60; saveDraft(); refresh() })
            addView(button(if (showOptions) "세부 설정 접기 ▴" else "세부 설정 · 멜로디 반영 $melodyWeight · 스타일 강도 $styleWeight ▾", size = Size.MEDIUM) {
                showOptions = !showOptions; refresh()
            }.withMargin(top = 24))
            if (showOptions) addView(column().apply {
                background = round(SURFACE, 24)
                setPadding(dp(28), dp(8), dp(28), dp(24))
                addView(weightSlider("내 멜로디 반영", "70 이상: 내 허밍 그대로 · 33~69: 허밍은 참고만, AI가 더 좋은 멜로디로 · 33 미만: AI가 자유롭게", melodyWeight) { melodyWeight = it; saveDraft() })
                addView(weightSlider("스타일 강도", "높을수록 주제의 장르·분위기를 진하게", styleWeight) { styleWeight = it; saveDraft() })
                addView(label("멜로디 느낌"))
                addView(segmented(listOf("멜로디 느낌 따르기", "주제만 보기"), if (followMood) 0 else 1, width = OPTION_W) {
                    followMood = it == 0; saveDraft(); refresh()
                })
                addView(caption("따르면 음표에서 잰 느낌(빠르기·도약·장단조)으로 장르·악기·템포·창법과 가사 말투를 고르고, 주제는 가사의 장면으로 써요.", TEXT3))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(caption("한 소절을 전주·절·후렴·브리지로 늘리고, 주제로 가사·제목을 쓰고, 앨범 표지를 그려 노래로 만들어요. 약 3분 — 주행을 시작해도 계속 만들어요. " +
                            "'가사 먼저 보기'로 가사를 보고 고친 뒤 만들 수도 있어요(약 1분).", TEXT3).withMargin(top = 16))
            s.optJSONObject("final")?.let { addView(button("완성된 노래 →", size = Size.MEDIUM) { showPlayer(false) }.withMargin(top = 16)) }
        }, ::showCreate, tabs = true) {
            zones(button("← 내 멜로디") { showSong(false) },
                listOf(if (making) recordButton(Rec.BUSY, "만드는 중" + queueTail(), onClick = null)
                       else button("노래 만들기", Btn.SECONDARY) { createFinal() }),
                if (job != null) button("＋ 하나 더(대기열)") { createFinal() }
                else button("가사 먼저 보기") { lyricDraft = null; showLyricsPreview() })
        }
    }

    // ------------------------------------------------------------------ 대화로 가사 만들기(저니맵 02) · 대화 보관함

    private val talks by lazy { TalkStore(this) }
    private var talkDirection = "그대로"
    /** 노래 만들기에 붙인 대화(보관함 항목)와 그 곡. */
    private var talkResult: JSONObject? = null
    private var talkFor: String? = null
    /** 보관함에서 대화를 고르면 이 곡에 붙인다(노래 만들기 화면에서 열었을 때). */
    private var talkPickFor: String? = null
    private var talkCancelled = false
    private var talkTime: TextView? = null
    private var talkLeft: TextView? = null
    private var showTranscript = false
    private var talkDeleteArmed: String? = null
    /** 플로우 2 가 고른 멜로디 설명(만드는 화면에 한 줄). */
    private var autoPickNote: String? = null

    private fun talkPayload(s: JSONObject): JSONObject? {
        val t = talkResult?.takeIf { talkFor == s.optString("id") && it.optBoolean("enough", true) } ?: return null
        return JSONObject().put("story", t.optString("story")).put("feeling", t.optString("feeling"))
            .put("phrases", t.optJSONArray("phrases") ?: org.json.JSONArray()).put("direction", t.optString("direction", "그대로"))
            // 장면 재료·훅·나온 사람도 넘긴다(전에는 빠져서 서버가 대화 속 재료로 장면을 못 그렸다 — 2026-11-11)
            .put("details", t.optJSONArray("details") ?: org.json.JSONArray()).put("hooks", t.optJSONArray("hooks") ?: org.json.JSONArray())
            .put("names", t.optJSONArray("names") ?: org.json.JSONArray())
    }

    /** 노래 만들기 화면의 대화 칸: 붙인 대화가 있으면 요약 + 빼기, 없으면 보관함에서 고르기 · 새로 녹음. */
    private fun talkCard(s: JSONObject): View = column().apply {
        val t = talkResult?.takeIf { talkFor == s.optString("id") }
        background = round(SURFACE, 24, if (t != null) POSITIVE else null)
        setPadding(dp(28), dp(20), dp(28), dp(20))
        if (t != null) {
            addView(text("", BODY_STRONG, TEXT).apply { text = dot("●  대화로 가사를 써요 · ${t.optString("direction")}", POSITIVE) })
            addView(text(t.optString("theme"), BODY, TEXT2).withMargin(top = 6))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("내용 보기", size = Size.MEDIUM) { showTalkDetail(t) }.withMargin(end = 16))
                addView(button("대화 빼기", size = Size.MEDIUM) { talkResult = null; talkFor = null; refresh() })
            }.withMargin(top = 12))
        } else {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(column().apply {
                    addView(text("🗣  대화로 가사 만들기", BODY_STRONG, TEXT))
                    addView(text("차 안에서 나눈 이야기로 가사를 써요. 대화 보관함 ${talks.list().size}개", BODY_S, TEXT3).withMargin(top = 4))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(button("보관함에서 고르기", size = Size.MEDIUM) { talkPickFor = s.optString("id"); showTalks() }.withMargin(end = 16))
                addView(button("새로 녹음", size = Size.MEDIUM, enabled = !driving) { talkPickFor = s.optString("id"); showTalkIntro() })
            })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
    }

    /** 대화 보관함: 이 기기 안에만 있는 분석한 대화 목록. 누르면 상세(노래 만들기 두 가지). */
    private fun showTalks() {
        val list = talks.list()
        val pickFor = talkPickFor
        show({
            onList = true
            if (list.isEmpty()) addView(body("아직 보관한 대화가 없어요. 아래 녹음 버튼으로 차 안 대화를 녹음해 보세요(30분까지).").withMargin(top = 24))
            list.forEach { t ->
                val date = java.text.SimpleDateFormat("MM.dd HH:mm", java.util.Locale.KOREA).format(java.util.Date(t.optLong("created")))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = pressable(round(SURFACE, 24), round(TINT, 24))
                    setPadding(dp(28), dp(18), dp(28), dp(18))
                    setOnClickListener {
                        if (pickFor != null && song?.optString("id") == pickFor) {  // 노래 만들기에서 고르러 왔으면 바로 붙인다
                            talkResult = t; talkFor = pickFor; talkPickFor = null
                            t.optString("theme").takeIf { it.isNotEmpty() }?.let { prompt = it; themeFromDrive = false; saveDraft() }
                            showCreate()
                        } else showTalkDetail(t)
                    }
                    addView(text("🗣", TITLE, TEXT2).withMargin(end = 20))
                    addView(column().apply {
                        addView(text(t.optString("title").ifEmpty { "대화" }, BODY_STRONG, TEXT).apply { maxLines = 1 })
                        addView(text(t.optString("theme"), BODY_S, TEXT2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }.withMargin(top = 2))
                        addView(text("$date · ${clock(t.optDouble("seconds", 0.0).toFloat())} 대화 · ${t.optString("direction")}" +
                                     (t.optJSONArray("songs")?.length()?.takeIf { it > 0 }?.let { " · 노래 ${it}곡" } ?: ""), BODY_S, TEXT3).withMargin(top = 2))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text(if (pickFor != null) "고르기" else "›", BODY_STRONG, if (pickFor != null) POSITIVE else TEXT3))
                }.withMargin(top = 12, fill = true))
            }
        }, ::showTalks, tabs = true, header = {
            addView(headline(if (pickFor != null) "노래에 쓸 대화 고르기" else "대화 보관함"))
            addView(body("녹음한 대화를 받아쓰고 정리한 것만 이 기기에 남아요. 녹음 파일과 서버에는 남지 않아요. ${list.size}개"))
        }) {
            zones(if (pickFor != null) button("← 노래 만들기") { talkPickFor = null; showCreate() } else button("← 내 멜로디") { openTab(Tab.MELODIES) },
                  listOf(recordButton(if (driving) Rec.DISABLED else Rec.READY, if (driving) "녹음은 정차 후에" else "대화 녹음",
                                      onClick = if (driving) null else ({ showTalkIntro() }))))
        }
    }

    /** 녹음 전: 차 안 모든 사람에게 알리기 + 어떤 노래로(방향). 녹음 버튼 자리는 다른 녹음 화면과 같다. */
    private fun showTalkIntro(notice: String? = null) {
        show({
            addView(headline("대화 녹음"))
            addView(body("차 안에서 나눈 이야기를 녹음해 받아쓰고, 노래 이야기로 정리해 대화 보관함에 넣어요. 최대 30분."))
            addView(column().apply {
                background = round(SURFACE, 24, POSITIVE)
                setPadding(dp(32), dp(24), dp(32), dp(28))
                addView(text("녹음하기 전에 차 안의 모든 분께 알려 주세요", BODY_STRONG, TEXT))
                addView(text("녹음 파일은 받아쓴 뒤 바로 지우고, 받아쓴 글과 정리는 이 기기에만 남아요. 대화에 나온 이름은 가사에 그대로 들어갈 수 있어요. AI 학습에 쓰지 않아요. " +
                             "다른 화면을 봐도 녹음은 계속돼요(알림에 '녹음 중'). 음악을 끄면 더 잘 받아써요.", BODY, TEXT2).withMargin(top = 8))
            }.withMargin(top = 24, fill = true))
            addView(label("어떤 노래로 만들까요?"))
            val dirs = TALK_DIRECTIONS.keys.toList()
            addView(segmented(dirs, dirs.indexOf(talkDirection).coerceAtLeast(0), width = 1176) { talkDirection = dirs[it]; showTalkIntro(notice) })
            addView(caption(TALK_DIRECTIONS[talkDirection] + " 방향은 나중에 바꿀 수 있어요.", TEXT3))
        }, { showTalkIntro(notice) }, tabs = true) {
            aboveButton(notice, TEXT) {
                zones(button("← 대화 보관함") { showTalks() },
                      listOf(recordButton(if (driving) Rec.DISABLED else Rec.READY, if (driving) "정차 후에" else "알리고 녹음 시작",
                                          onClick = if (driving) null else ({ startTalk() }))))
            }
        }
    }

    private fun startTalk() {
        if (recording || counting || TalkRecordService.recording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_TALK); return
        }
        stopPlayback()
        // 시험 모드: 마이크 대신 앱 files 폴더의 대화 녹음(am start --es test_talk talk.m4a)
        intent.getStringExtra("test_talk")?.let { File(filesDir, it) }?.takeIf { it.exists() }?.let { src ->
            val copy = File(cacheDir, "talk_test." + src.extension)
            src.copyTo(copy, overwrite = true)
            return uploadTalk(copy)
        }
        talkCancelled = false
        TalkRecordService.result = null
        TalkRecordService.onDone = { onTalkRecorded() }
        TalkRecordService.start(this, File(cacheDir, "talk_${System.currentTimeMillis()}.m4a"), inputDevice?.id)
        showTalkRecording()
    }

    /** 녹음 서비스가 끝났을 때(완료·취소·30분). 앱이 멈춰 있었으면 돌아올 때(onResume) 부른다. */
    private fun onTalkRecorded() {
        val r = TalkRecordService.result ?: return
        TalkRecordService.result = null
        val f = r.file
        when {
            talkCancelled -> { talkCancelled = false; f?.delete(); showTalkIntro("녹음을 취소했어요. 방금 녹음은 저장하지 않았어요.") }
            f == null || r.seconds < 0 -> showTalkIntro("마이크를 열지 못했어요. 설정 탭의 녹음 장치를 확인해 주세요.")
            r.seconds < TALK_MIN_SEC -> { f.delete(); showTalkIntro("너무 짧아요. ${TALK_MIN_SEC}초 넘게 이야기해 주세요.") }
            // 30분이 차서 저절로 끝났으면 정리 뒤 가사·노래까지 자동으로
            else -> uploadTalk(f, autoMake = r.seconds >= TalkRecordService.MAX_MS / 1000 - 3)
        }
    }

    /**
     * 녹음 중 화면: 가운데 목소리에 반응하는 원(2026-11-10 사용자: 음성 인식 표시를 화면 가운데에) + 소리 막대,
     * 아래 줄 위에 남은 시간(마지막 1분은 빨갛게), 오른쪽에 "12:34 / 30:00".
     */
    private var talkOrb: VoiceOrb? = null
    private var recOrb: VoiceOrb? = null

    private fun showTalkRecording() {
        val max = TalkRecordService.MAX_MS / 1000
        show({
            addView(headline("대화 녹음 중"))
            addView(body("평소처럼 이야기하세요. 다른 화면을 봐도 계속 녹음해요. 다 이야기했으면 가운데 버튼을 누르세요."))
            talkOrb = VoiceOrb(this@MainActivity).also {
                it.color = ACTIVE
                it.calm = driving
                addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TALK_ORB_H)).apply { topMargin = dp(8) })
            }
            wave = LevelStrip(this@MainActivity).also {
                addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }, ::showTalkRecording) {
            orientation = LinearLayout.VERTICAL
            talkLeft = text("녹음을 준비하는 중…", TITLE, TEXT3).apply { gravity = Gravity.CENTER }
            addView(talkLeft, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
            talkTime = text("00:00 / ${clock(max.toFloat())}", TITLE, TEXT)
            val status = column().apply {
                gravity = Gravity.END
                addView(text("●  대화 녹음 중", HEADLINE, RECORDING).apply { maxLines = 1 },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
                addView(talkTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END; topMargin = dp(4) })
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                zones(button("✕  녹음 취소") { talkCancelled = true; TalkRecordService.stop(this@MainActivity) },
                      listOf(recordButton(Rec.RECORDING, "녹음 완료") { TalkRecordService.stop(this@MainActivity) }), status)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val token = scoreToken
        var waited = 0
        fun tick() {
            if (token != scoreToken) return
            // 서비스가 마이크를 여는 데 잠깐 걸린다 — 전에는 그 사이에 갱신을 그만둬 시간이 00:00 에 멈춰 있었다(2026-11-10 사용자 화면)
            if (!TalkRecordService.recording) {
                if (TalkRecordService.result != null || waited++ > 50) return
                ui.postDelayed({ tick() }, 100); return
            }
            val el = TalkRecordService.elapsedMs() / 1000
            val left = max - el
            val db = TalkRecordService.levelDb()
            wave?.push(db, if (db > VOICE_DB) ACTIVE else TEXT3)
            talkOrb?.level(db)
            talkTime?.text = "%02d:%02d / %s".format(el / 60, el % 60, clock(max.toFloat()))
            talkLeft?.text = if (left <= 60) "${left}초 뒤 녹음이 끝나요" else "남은 시간 %d분 %02d초".format(left / 60, left % 60)
            talkLeft?.setTextColor(if (left <= 60) RECORDING else TEXT3)
            ui.postDelayed({ tick() }, 100)
        }
        tick()
    }

    // 대화 정리(받아쓰기·정리)는 뒤에서 — 그동안 다른 탭을 자유롭게 쓴다(2026-11-10 사용자). 오른쪽 위에 작은 표시.
    /** 진행 중인 대화 정리: stage(upload·decode·stt·digest·done·error), eta, 끝나면 결과 대화. */
    private var talkJobStage: String? = null
    private var talkJobEta = 0
    private var talkJobDone: JSONObject? = null
    private var talkJobError: String? = null

    private fun talkJobLine(): String = when (talkJobStage) {
        "upload" -> "녹음을 올리는 중"
        "stt" -> "받아쓰는 중" + (if (talkJobEta > 0) " · 약 ${clock(talkJobEta.toFloat())}" else "")
        "digest" -> "노래 이야기로 정리하는 중"
        else -> "녹음을 읽는 중"
    }

    /** 오른쪽 위 대화 정리 표시: 진행 중 → 단계, 끝 → "보관함에 넣었어요 · 보기", 실패 → 이유. 누르면 보관함/상세. */
    private fun talkChip(): View? {
        val done = talkJobDone
        val err = talkJobError
        if (talkJobStage == null && done == null && err == null) return null
        if (TalkRecordService.recording || recording || counting) return null
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(round(SURFACE, 24, if (err != null) RECORDING else POSITIVE), round(TINT, 24))
            elevation = dp(8).toFloat()
            setPadding(dp(20), dp(16), dp(12), dp(16))
            setOnClickListener {
                when {
                    done != null -> { talkJobDone = null; showTalkDetail(done) }
                    err != null -> {
                        talkJobError = null
                        val pending = talkPendingFile
                        if (pending != null && pending.exists()) uploadTalk(pending, talkPendingAuto) else showTalkIntro(err)
                    }
                    else -> { talkPickFor = null; showTalks() }
                }
            }
            addView(text("🗣", TITLE, TEXT2).withMargin(end = 16))
            addView(column().apply {
                addView(text(when { done != null -> "대화 정리 끝"; err != null -> "대화 정리 실패"; else -> "대화 정리 중" }, BODY_STRONG, if (err != null) RECORDING else TEXT))
                addView(text(when { done != null -> "보관함에 넣었어요 · ${done.optString("title").ifEmpty { "대화" }}"; err != null -> err.take(40); else -> talkJobLine() },
                             BODY_S, TEXT3).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(if (done != null) "보기 ›" else if (err != null) (if (talkPendingFile?.exists() == true) "다시 보내기 ›" else "다시 ›") else "",
                         BODY_S_STRONG, if (err != null) RECORDING else POSITIVE))
            if (done != null || err != null) addView(iconButton("✕", "닫기") { talkJobDone = null; talkJobError = null; refresh() })
        }
    }

    /** 녹음 올리기 → 서버 분석 작업(받아쓰기 → 정리)을 뒤에서 기다려 보관함에 넣는다. 화면은 바로 돌려준다. */
    /** 올리지 못한 대화 녹음(다시 보내기용). 올리고 나면 지운다. */
    private var talkPendingFile: File? = null
    private var talkPendingAuto = false
    private var talkPollToken = 0
    private var talkPollThread: Thread? = null

    /**
     * 대화 녹음 올리기 → 정리 결과 기다리기. 전용 스레드에서 한다(2026-11-11, 44번 #3: 전에는 io 단일 스레드를 수십 초~수 분
     * 붙잡아 그동안 목록·노래 만들기 같은 다른 요청이 모두 멈췄다). 올리기가 실패하면 녹음을 지우지 않고 '다시 보내기'를 띄운다
     * (전에는 바로 지워 30분 대화를 잃을 수 있었다). 상태 조회는 실패해도 간격을 늘려 8번까지 다시 묻고, 20분이 넘으면 그만둔다.
     */
    private fun uploadTalk(file: File, autoMake: Boolean = false) {
        val direction = talkDirection
        talkJobStage = "upload"; talkJobEta = 0; talkJobDone = null; talkJobError = null
        talkPendingFile = null
        // 바로 보관함(노래 만들기에서 왔으면 노래 만들기)으로 — 정리되는 동안 다른 탭을 써도 된다(오른쪽 위 표시)
        if (talkPickFor != null && song?.optString("id") == talkPickFor) showCreate() else showTalks()
        val token = ++talkPollToken
        talkPollThread?.interrupt()
        talkPollThread = Thread {
            val id = try {
                api.talkStart(file, direction).getString("id")
            } catch (e: Exception) {
                Log.w(tag, "대화 녹음 올리기 실패: ${e.message}")
                ui.post {
                    if (token != talkPollToken || isDestroyed) return@post
                    talkPendingFile = file; talkPendingAuto = autoMake
                    talkJobStage = null
                    talkJobError = (if (e is ApiException) e.message else null) ?: "녹음을 올리지 못했어요. 눌러서 다시 보내 주세요."
                    if (active && !recording && !counting) refresh()
                }
                return@Thread
            }
            file.delete()  // 서버가 받았으니 녹음 파일은 앱에도 남기지 않는다
            var lastStage = ""
            var failures = 0
            val deadline = System.currentTimeMillis() + 20 * 60_000L
            try {
                while (token == talkPollToken && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(if (failures == 0) 2000L else minOf(15000L, failures * 2500L))
                    if (System.currentTimeMillis() > deadline) throw ApiException("TIMEOUT", "대화 정리가 너무 오래 걸려요. 잠시 후 다시 녹음해 주세요.")
                    val st = try { api.talkStatus(id).also { failures = 0 } } catch (e: ApiException) {
                        throw e
                    } catch (e: Exception) {
                        if (++failures >= 8) throw ApiException("OFFLINE", "서버와 연결이 끊겨 대화 정리 결과를 받지 못했어요.")
                        continue
                    }
                    when (st.optString("status")) {
                        "done" -> {
                            val r = st.getJSONObject("result")
                            val t = JSONObject(r.toString()).put("id", java.util.UUID.randomUUID().toString().take(12))
                                .put("created", System.currentTimeMillis()).put("songs", org.json.JSONArray())
                            t.remove("timing")
                            talks.save(t)
                            ui.post {
                                if (token != talkPollToken || isDestroyed) return@post
                                talkJobStage = null
                                talkJobDone = t
                                val pick = talkPickFor
                                if (pick != null && song?.optString("id") == pick && t.optBoolean("enough", true)) {
                                    talkResult = t; talkFor = pick; talkPickFor = null
                                    t.optString("theme").takeIf { it.isNotEmpty() }?.let { prompt = it; themeFromDrive = false; saveDraft() }
                                }
                                android.widget.Toast.makeText(this@MainActivity, "대화 정리가 끝났어요 — 대화 보관함에 넣었어요", android.widget.Toast.LENGTH_LONG).show()
                                // 30분이 차서 저절로 끝난 녹음은 이어서 가사·노래까지 자동으로(2026-11-10 사용자) — AI 가 멜로디를 고른다
                                if (autoMake && t.optBoolean("enough", true) && !recording && !counting && finalJob == null && !finalSubmitting) {
                                    talkJobDone = null
                                    autoSong(t)
                                } else if (active && !recording && !counting) refresh()
                            }
                            return@Thread
                        }
                        "error" -> throw ApiException(st.optString("error_code"), st.optString("error", "대화를 정리하지 못했어요."))
                        else -> {
                            val stage = st.optString("stage")
                            if (stage != lastStage) {
                                lastStage = stage
                                val eta = st.optInt("eta_sec")
                                ui.post { if (token == talkPollToken) { talkJobStage = stage; talkJobEta = eta; if (active && !recording && !counting) refresh() } }
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                return@Thread
            } catch (e: Exception) {
                ui.post {
                    if (token != talkPollToken || isDestroyed) return@post
                    talkJobStage = null
                    talkJobError = e.message ?: "대화를 정리하지 못했어요."
                    if (active && !recording && !counting) refresh()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /** 보관한 대화 하나: 정리 내용 · 방향 바꾸기 · 받아쓴 대화 · 노래 만들기 두 가지(멜로디 골라서 / AI 로 바로) · 지우기. */
    private fun showTalkDetail(t: JSONObject) {
        val enough = t.optBoolean("enough", true)
        val date = java.text.SimpleDateFormat("MM월 dd일 HH:mm", java.util.Locale.KOREA).format(java.util.Date(t.optLong("created")))
        show({
            addView(headline(t.optString("title").ifEmpty { "대화로 만든 이야기" }))
            addView(body("$date · ${clock(t.optDouble("seconds", 0.0).toFloat())} 대화"))
            addView(column().apply {
                background = round(SURFACE, 24, if (enough) POSITIVE else RECORDING)
                setPadding(dp(32), dp(24), dp(32), dp(28))
                if (!enough) addView(text("노래로 만들 이야기가 부족해요. 조금 더 이야기한 뒤 다시 녹음해 주세요.", BODY_STRONG, RECORDING).withMargin(bottom = 12))
                listOf("노래 주제" to t.optString("theme"), "무슨 일이 있었나" to t.optString("story"), "감정" to t.optString("feeling"),
                       "나온 사람" to (t.optJSONArray("names")?.let { a -> (0 until a.length()).joinToString(" · ") { a.optString(it) } } ?: ""),
                       "가사에 살릴 말" to (t.optJSONArray("phrases")?.let { a -> (0 until a.length()).joinToString(" · ") { "‘${a.optString(it)}’" } } ?: ""))
                    .filter { it.second.isNotBlank() }
                    .forEachIndexed { i, (k, v) ->
                        addView(text(k, BODY_S_STRONG, TEXT3).withMargin(top = if (i == 0) 0 else 16))
                        addView(text(v, BODY, TEXT).withMargin(top = 4))
                    }
            }.withMargin(top = 20, fill = true))
            addView(label("방향 · 바꾸면 받아쓴 대화로 다시 정리해요(약 10초)"))
            val dirs = TALK_DIRECTIONS.keys.toList()
            addView(segmented(dirs, dirs.indexOf(t.optString("direction")).coerceAtLeast(0), width = 1176) { i -> redigest(t, dirs[i]) })
            addView(button(if (showTranscript) "받아쓴 대화 접기 ▴" else "받아쓴 대화 보기 ▾", size = Size.MEDIUM) { showTranscript = !showTranscript; showTalkDetail(t) }.withMargin(top = 20))
            if (showTranscript) addView(text(t.optString("transcript").take(3000), BODY_S, TEXT2).withMargin(top = 12))
            addView(caption("녹음 파일은 지웠고, 이 내용은 이 기기에만 있어요.", TEXT3).withMargin(top = 12))
            addView(button(if (talkDeleteArmed == t.optString("id")) "한 번 더 누르면 지워요" else "이 대화 지우기", size = Size.MEDIUM, enabled = !driving) {
                if (talkDeleteArmed != t.optString("id")) { talkDeleteArmed = t.optString("id"); showTalkDetail(t); return@button }
                talkDeleteArmed = null
                talks.delete(t.optString("id"))
                if (talkResult?.optString("id") == t.optString("id")) { talkResult = null; talkFor = null }
                showTalks()
            }.withMargin(top = 20))
        }, { showTalkDetail(t) }, tabs = true) {
            zones(button("← 보관함") { showTalks() },
                  listOf(if (enough) button("✦ AI로 바로 노래", Btn.SECONDARY) { autoSong(t) } else button("다시 녹음") { showTalkIntro() }),
                  if (enough) button("멜로디 골라 만들기") { showPickMelody(t) } else null)
        }
    }

    /** 방향을 바꿔 다시 정리(받아쓴 줄로, 서버에 남지 않는다). */
    private fun redigest(t: JSONObject, direction: String) {
        if (direction == t.optString("direction")) return
        val lines = t.optJSONArray("lines") ?: return
        show({ addView(headline("다시 정리하는 중")); addView(body("‘$direction’ 방향으로 정리하고 있어요. 약 10초.")) }, {}) {
            zones(null, listOf(recordButton(Rec.BUSY, "정리하는 중", onClick = null)))
        }
        io.execute {
            try {
                val d = api.talkDigest(lines, direction)
                d.keys().forEach { k -> t.put(k, d.get(k)) }
                talks.save(t)
                if (talkResult?.optString("id") == t.optString("id")) talkResult = t
                ui.post { if (active) showTalkDetail(t) }
            } catch (e: Exception) {
                ui.post { if (active) showError(e.message ?: "다시 정리하지 못했어요.") }
            }
        }
    }

    /** 플로우 1 — 멜로디 고르기: 내 멜로디 목록에서 하나. */
    private fun showPickMelody(t: JSONObject) {
        show({
            onList = true
            if (library.isEmpty()) addView(body("아직 멜로디가 없어요. 녹음실에서 한 소절 흥얼거리거나 ‘AI로 바로 노래’를 눌러 보세요.").withMargin(top = 24))
            library.forEach { item ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = pressable(round(SURFACE, 24), round(TINT, 24))
                    setPadding(dp(20), dp(14), dp(28), dp(14))
                    setOnClickListener { useTalkWith(t, item.getString("id"), preview = true) }
                    addView(thumb(item), LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(24) })
                    addView(column().apply {
                        addView(text(songName(item), BODY_STRONG, TEXT).apply { maxLines = 1 })
                        item.optJSONObject("mood")?.let { m -> addView(moodLine(m.optString("label"), "${m.optString("label")} · 에너지 ${m.optInt("energy")}").withMargin(top = 4)) }
                        addView(text("템포 ${item.optInt("tempo")} · ${keyOf(item)} · ${dateOf(item)}", BODY_S, TEXT3).withMargin(top = 4))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text("고르기", BODY_STRONG, POSITIVE))
                }.withMargin(top = 12, fill = true))
            }
        }, { showPickMelody(t) }, tabs = true, header = {
            addView(headline("어떤 멜로디로 부를까요?"))
            addView(body("‘${t.optString("title").ifEmpty { "대화" }}’ 이야기를 이 멜로디에 맞춰 가사로 써요. 대화 분위기(${t.optString("direction")} · ${t.optString("feeling")})에 맞는 멜로디를 골라 보세요."))
        }) {
            zones(button("← 대화") { showTalkDetail(t) }, listOf(button("✦ AI가 골라 주기", Btn.SECONDARY) { autoSong(t) }))
        }
    }

    /** 대화를 곡에 붙여 노래 만들기 준비: 곡을 받아 오고 주제를 대화 주제로. preview 면 구간 미리보기, 아니면 바로 만든다. */
    private fun useTalkWith(t: JSONObject, songId: String, preview: Boolean) {
        stopPlayback()
        io.execute {
            try {
                val s = api.song(songId)
                ui.post {
                    song = s; stackMode = null; playerVersion = null
                    talkResult = t; talkFor = songId
                    t.optString("theme").takeIf { it.isNotEmpty() }?.let { prompt = it; themeFromDrive = false; promptFor = songId; saveDraft() }
                    if (preview) showFormPreview(t) else { createFinal(); rememberTalkSong(t, songId) }
                }
            } catch (e: Exception) {
                ui.post { showError(e.message ?: "멜로디를 열지 못했어요.") }
            }
        }
    }

    private fun rememberTalkSong(t: JSONObject, songId: String) {
        val a = t.optJSONArray("songs") ?: org.json.JSONArray().also { t.put("songs", it) }
        if ((0 until a.length()).none { a.optString(it) == songId }) a.put(songId)
        talks.save(t)
    }

    /** 플로우 1 — 멜로디 분석 · 가사 구간 · 줄마다 글자 수 미리보기 → 이대로 만들기. */
    private fun showFormPreview(t: JSONObject, form: JSONObject? = null) {
        val s = song ?: return showTalks()
        if (form == null) {
            show({ addView(headline("멜로디를 살펴보는 중")); addView(body("가사가 들어갈 구간과 줄마다 글자 수를 재고 있어요.")) }, {}) {
                zones(null, listOf(recordButton(Rec.BUSY, "살펴보는 중", onClick = null)))
            }
            io.execute {
                val f = runCatching { api.songForm(s.getString("id"), finalSeconds) }.getOrNull()
                ui.post { if (active) showFormPreview(t, f ?: JSONObject()) }
            }
            return
        }
        val names = mapOf("intro" to "전주", "verse" to "절", "verse2" to "절 2", "chorus" to "후렴", "chorus2" to "후렴", "bridge" to "브리지", "interlude" to "간주", "outro" to "아웃트로")
        show({
            addView(steps(2))
            addView(headline("이렇게 불러요"))
            addView(body("${songName(s, "내 멜로디")} · 템포 ${s.optInt("tempo")} · ${keyOf(s)} → ${finalSeconds / 60}분 노래 · ${t.optString("direction")}"))
            s.optJSONObject("mood")?.let { m -> addView(moodLine(m.optString("label"), "${Mood.name(m)} 멜로디 · ${m.optString("genre")}", TEXT2).withMargin(top = 8)) }
            addView(label("가사 구간과 줄마다 글자 수(멜로디의 숨 쉬는 자리로 나눴어요)"))
            val secs = form.optJSONArray("sections")
            if (secs == null || secs.length() == 0) addView(caption("구성을 미리 보지 못했어요. 그래도 만들 수 있어요.", TEXT3))
            else addView(column().apply {
                background = round(SURFACE, 24)
                setPadding(dp(28), dp(20), dp(28), dp(20))
                for (i in 0 until secs.length()) {
                    val sec = secs.getJSONObject(i)
                    val name = names[sec.optString("name")] ?: sec.optString("name")
                    val syl = sec.optJSONArray("syllables")
                    val line = if (!sec.optBoolean("sung") || syl == null || syl.length() == 0) "연주만 · ${sec.optInt("bars")}마디"
                               else "${syl.length()}줄 · 줄마다 ${(0 until syl.length()).joinToString("·") { syl.optInt(it).toString() }}자"
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(text(name, BODY_STRONG, if (sec.optBoolean("sung")) TEXT else TEXT3), LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
                        addView(text(line, BODY, if (sec.optBoolean("sung")) TEXT2 else TEXT3))
                    }.withMargin(top = if (i == 0) 0 else 10))
                }
            }.withMargin(fill = true))
            addView(caption("가사가 정해지면 줄마다 음표 수를 글자 수에 정확히 맞춰 불러요(낱말이 줄을 넘어가지 않게).", TEXT3))
            addView(label("주제"))
            addView(text(t.optString("theme"), BODY, TEXT))
            addView(label("곡 길이"))
            addView(segmented(listOf("1분", "2분", "3분"), finalSeconds / 60 - 1, width = OPTION_W) { finalSeconds = (it + 1) * 60; saveDraft(); showFormPreview(t) })
        }, { showFormPreview(t, form) }, tabs = true) {
            zones(button("← 멜로디 다시 고르기") { showPickMelody(t) },
                  listOf(button("이대로 노래 만들기", Btn.SECONDARY) { createFinal(); rememberTalkSong(t, s.getString("id")) }),
                  button("가사 먼저 보기") { lyricDraft = null; showLyricsPreview() })
        }
    }

    /**
     * 플로우 2 — AI 로 바로: 대화 방향·감정에 맞는 멜로디를 내 멜로디에서 골라 바로 만든다.
     * 에너지 목표: 통쾌하게 75 · 유쾌하게 65 · 담담하게 30 · 그대로는 감정 낱말로(분노·신남 70, 불안·슬픔 35, 그 밖 50).
     */
    private fun autoSong(t: JSONObject) {
        val candidates = library.filter { it.optJSONObject("mood") != null }
        if (candidates.isEmpty()) return showTalkDetail(t).also { notice = "멜로디가 없어요. 녹음실에서 한 소절 흥얼거린 뒤 다시 눌러 주세요." }
        val feeling = t.optString("feeling")
        val target = when (t.optString("direction")) {
            "통쾌하게" -> 75; "유쾌하게" -> 65; "담담하게" -> 30
            else -> when {
                listOf("분노", "화", "통쾌", "신남", "설렘", "유쾌").any { it in feeling } -> 70
                listOf("불안", "슬픔", "우울", "피로", "그리움", "외로").any { it in feeling } -> 35
                else -> 50
            }
        }
        val wantMinor = target <= 40
        val pick = candidates.minByOrNull { item ->
            val m = item.getJSONObject("mood")
            val minor = m.optString("key").contains("m") || keyOf(item).contains("단조")
            Math.abs(m.optInt("energy") - target) + (if (minor != wantMinor) 15 else 0)
        } ?: candidates.first()
        val m = pick.getJSONObject("mood")
        autoPickNote = "대화 분위기(${t.optString("direction")} · ${feeling.ifEmpty { "-" }})에 맞춰 ‘${songName(pick)}’(${m.optString("label")} · 에너지 ${m.optInt("energy")}) 멜로디를 골랐어요."
        useTalkWith(t, pick.getString("id"), preview = false)
    }

    /** 주제가 오늘 드라이브에서 자동으로 왔는지(안내 문구용). 사용자가 고치거나 🎲 를 누르면 false. */
    private var themeFromDrive = false
    /** 지금 주제가 어느 곡의 것인지. */
    private var promptFor: String? = null

    // ------------------------------------------------------------------ 내 노래(목록)

    private var library = listOf<JSONObject>()

    /** 서버에 닿지 않을 때 화면 맨 위에 띄울 문장(닿으면 null). 전에는 목록이 비어 '노래 없음'과 구분되지 않았다(44번 #11). */
    private var serverDown: String? = null

    private fun loadLibrary() {
        io.execute {
            try {
                val a = api.songs(LIBRARY_SIZE).getJSONArray("songs")
                val list = (0 until a.length()).map { a.getJSONObject(it) }
                ui.post {
                    val was = serverDown
                    library = list; serverDown = null
                    if (was != null || onList || (onHome && !recording && !counting)) refresh()
                }
            } catch (e: Exception) {
                Log.w(tag, "내 노래 목록을 못 받음: ${e.message}")
                val msg = if (e is ApiException && e.code == "UNAUTHORIZED") e.message ?: "이 서버를 쓸 수 있는 앱이 아니에요."
                          else "서버에 연결되지 않아요(${api.base}). 서버 PC 가 켜져 있는지, 설정의 서버 주소를 확인해 주세요."
                ui.post { if (serverDown != msg) { serverDown = msg; if (!recording && !counting) refresh() } }
            }
        }
    }

    /** 화면 맨 위 '서버 연결 안 됨' 띠 + 다시 연결. */
    private fun serverDownBar(): View? = serverDown?.let { msg ->
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(SURFACE, 24, RECORDING)
            setPadding(dp(28), dp(16), dp(16), dp(16))
            addView(text(msg, BODY_S, RECORDING), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("다시 연결", Btn.PRIMARY, Size.MEDIUM) { loadLibrary(); io.execute { loadCatalog() } },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(Size.MEDIUM.height)).apply { marginStart = dp(16) })
        }
    }

    private fun openSong(id: String, player: Boolean = false, create: Boolean = false) {
        stopPlayback()
        io.execute {
            try {
                val s = api.song(id)
                ui.post {
                    song = s; stackMode = null; playerVersion = null
                    when {
                        player && s.optJSONObject("final") != null -> showPlayer(false)
                        create -> showCreate()
                        else -> showSong(false)
                    }
                }
            } catch (e: Exception) {
                ui.post { showError(e.message ?: "곡을 열지 못했어요.") }
            }
        }
    }

    // ------------------------------------------------------------------ 노래로 만들기(최종 노래)

    /** 노래 주제. 비워 두고 만들면 서버가 주사위로 고른다. */
    private var prompt = ""
    /** 만들고 있는 최종 노래 작업(서버 job). null 이면 없음. */
    private var finalJob: JSONObject? = null
    private var finalSubmitting = false
    @Volatile private var finalPollToken = 0
    private var finalPollThread: Thread? = null

    private fun rememberFinalJob(job: JSONObject?) {
        finalJob = job
        prefs.edit().apply {
            if (job == null) remove("active_final_job") else putString("active_final_job", job.toString())
        }.apply()
    }

    private fun resumeFinalJob() {
        if (finalPollThread?.isAlive == true) return
        val job = finalJob ?: runCatching {
            prefs.getString("active_final_job", null)?.let { JSONObject(it) }
        }.getOrNull() ?: return
        finalJob = job
        val songId = job.optString("song_id")
        val jobId = job.optString("id")
        if (songId.isNotEmpty() && jobId.isNotEmpty()) pollFinal(songId, jobId)
    }

    /** 주제 입력 + 주사위. 주행 중에는 글자 입력을 막는다(주사위·만들기는 된다). */
    private fun promptRow(enabled: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val input = EditText(this@MainActivity).apply {
            setText(prompt)
            hint = if (listening) "말해 주세요… 예: 비 오는 밤 퇴근길에 듣는 시티팝" else "어떤 노래? 예: 비 오는 밤 퇴근길, 잔잔한 시티팝"
            promptInput = this
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY.size.toFloat())
            typeface = faces.getOrPut(BODY.font) { resources.getFont(BODY.font) }
            setTextColor(TEXT)
            setHintTextColor(TEXT3)
            maxLines = 2
            background = round(SURFACE, 24, TINT_PRESSED)
            setPadding(dp(28), dp(16), dp(28), dp(16))
            isEnabled = enabled && !driving
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    if (e.toString() != prompt) themeFromDrive = false
                    prompt = e.toString(); saveDraft()
                }
            })
        }
        addView(input, LinearLayout.LayoutParams(0, dp(100), 1f).apply { marginEnd = dp(16) })
        addView(iconButton(if (listening) "■" else "🎤", if (listening) "말로 주제 넣기 멈추기" else "말로 주제 넣기") { if (enabled) voicePrompt(input) }.apply {
            if (listening) { background = round(RECORDING, 20); setTextColor(Color.WHITE) }
        }, LinearLayout.LayoutParams(dp(100), dp(100)).apply { marginEnd = dp(16) })
        addView(iconButton("🎲", "주사위 — 아무 주제나") { if (enabled) rollTheme(input) }, LinearLayout.LayoutParams(dp(100), dp(100)))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private var listening = false
    private var recognizer: SpeechRecognizer? = null

    /**
     * 말로 주제 넣기: Pleos 기기 음성 인식(RecognitionService, 에뮬레이터는 ai.umos.ambientai)으로 받아 적는다.
     * 듣는 동안 차 음악은 오디오 포커스로 잠깐 멈추고, 한 번 더 누르면 그만. 말한 내용은 기기 인식 서비스로만 간다.
     */
    /** onFinal: 말이 끝나 최종 인식 결과가 나왔을 때(중간 결과 말고) 한 번 — 말로 바꾸기의 주행 중 자동 실행에 쓴다. */
    private fun voicePrompt(input: EditText, onText: (String) -> Unit = { prompt = it }, onFinal: ((String) -> Unit)? = null) {
        if (listening) { recognizer?.stopListening(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VOICE)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { input.hint = "이 차에서는 음성 인식을 쓸 수 없어요"; return }
        stopPlayback()
        if (!holdFocus()) { input.hint = "지금은 말로 넣을 수 없어요(통화·안내 중)"; return }
        listening = true
        heardText = ""
        heardState = "듣고 있어요"
        refresh()
        fun done(text: String?) {
            listening = false
            releaseFocus()
            recognizer?.destroy(); recognizer = null
            if (!text.isNullOrBlank()) onText(text)
            voiceOrb = null
            refresh()
            if (!text.isNullOrBlank()) onFinal?.invoke(text)
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { Log.i(tag, "음성 인식 준비"); setHeard(state = "듣고 있어요 · 말씀하세요") }
                override fun onBeginningOfSpeech() { setHeard(state = "듣고 있어요") }
                // 인식 서비스가 주는 소리 크기(대략 -2~10) → 가운데 원(-60~-15dB 기준)
                override fun onRmsChanged(rmsdB: Float) { voiceOrb?.level(-60.0 + (rmsdB + 2) * 3.75) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { setHeard(state = "알아들었어요 · 정리하는 중") }
                override fun onError(error: Int) { Log.w(tag, "음성 인식 오류 $error"); done(null) }
                override fun onResults(results: Bundle?) = done(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                override fun onPartialResults(partial: Bundle?) {
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onText(it); input.setText(it); setHeard(text = it) }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        }
    }

    private var promptInput: EditText? = null

    // ---------- 음성 인식 표시(VUI) — 듣는 동안 화면 가운데 ----------
    private var heardText = ""
    private var heardState = ""
    private var voiceOrb: VoiceOrb? = null
    private var heardView: TextView? = null
    private var heardStateView: TextView? = null

    private fun setHeard(text: String? = null, state: String? = null) {
        text?.let { heardText = it; heardView?.text = it.ifEmpty { "…" } }
        state?.let { heardState = it; heardStateView?.text = it }
    }

    /**
     * 말로 넣는 동안 화면 가운데에 뜨는 표시(2026-11-10 사용자: "음성 인식하면 표시해 주는 것 — 화면 중앙에").
     * 목소리에 반응하는 원 · 상태(듣고 있어요 → 알아들었어요) · 알아들은 말 실시간 · 그만 버튼. 바탕은 어둡게 깔아 무엇을 하는지 분명히.
     * show() 가 화면을 다시 그려도 듣는 중이면 다시 얹는다.
     */
    private fun voiceOverlay(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.argb(0xB0, 0x13, 0x14, 0x17))
        isClickable = true  // 뒤 화면을 누르지 않게
        addView(column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = round(SURFACE, 32)
            setPadding(dp(48), dp(32), dp(48), dp(36))
            voiceOrb = VoiceOrb(this@MainActivity).also { o ->
                o.color = ACTIVE; o.calm = driving
                addView(o, LinearLayout.LayoutParams(dp(VOICE_ORB), dp(VOICE_ORB)))
            }
            heardStateView = text(heardState, BODY_STRONG, ACTIVE).apply { gravity = Gravity.CENTER }
            addView(heardStateView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            heardView = text(heardText.ifEmpty { "…" }, HEADLINE, TEXT).apply { gravity = Gravity.CENTER; maxLines = 3 }
            addView(heardView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(text("말이 끝나면 저절로 넣어요", BODY_S, TEXT3).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(button("그만", size = Size.MEDIUM) { recognizer?.cancel(); recognizer?.destroy(); recognizer = null
                listening = false; releaseFocus(); voiceOrb = null; refresh() }.withMargin(top = 20))
        }, FrameLayout.LayoutParams(dp(VOICE_PANEL_W), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** 주제 예시(누르면 주제 칸에 넣는다). 서버가 장면 × 분위기로 섞어 주고, 안 되면 앱 목록에서. */
    private var themeExamples: List<String> = emptyList()

    private fun themeExampleRow(enabled: Boolean): View {
        if (themeExamples.isEmpty()) {
            themeExamples = THEMES.shuffled().take(3)
            io.execute {
                val ex = try { api.themes().optJSONArray("examples")?.let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { null }
                if (!ex.isNullOrEmpty()) ui.post { themeExamples = ex.take(3); refresh() }
            }
        }
        return android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            themeExamples.forEach { ex ->
                addView(button(ex, Btn.PRIMARY, Size.MEDIUM, enabled = enabled && !driving) {
                    prompt = ex; themeFromDrive = false; promptInput?.setText(ex); saveDraft()
                }.withMargin(end = 12))
            }
        })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
    }

    /** 주사위: 서버 주제 목록에서 아무거나(지금과 다른 것). 서버가 안 되면 앱에 있는 목록. */
    private fun rollTheme(input: EditText) {
        io.execute {
            val list = try {
                api.themes().getJSONArray("themes").let { a -> (0 until a.length()).map { a.getString(it) } }
            } catch (e: Exception) {
                THEMES
            }
            val pick = list.filter { it != prompt }.randomOrNull() ?: THEMES.filter { it != prompt }.random()
            ui.post { prompt = pick; input.setText(pick); themeFromDrive = false; saveDraft() }
        }
    }

    // 세부 설정(가중치): 곡 길이 · 목소리 · 내 멜로디 반영 · 스타일 강도
    private var showOptions = false
    private var finalSeconds = 120
    private var voice = "auto"
    private var melodyWeight = 80
    private var styleWeight = 50
    /** 노래 만들기가 멜로디 성격(음표 분석)을 따를지. 끄면 예전처럼 주제만 본다. */
    private var followMood = true

    private fun optionSummary() =
        "${finalSeconds / 60}분 · ${VOICE_NAMES.getValue(voice)} · 멜로디 $melodyWeight · 스타일 $styleWeight"

    private fun optionsPanel() = column().apply {
        background = round(SURFACE, 24)
        setPadding(dp(28), dp(8), dp(28), dp(24))
        addView(label("곡 길이"))
        addView(segmented(listOf("1분", "2분", "3분"), finalSeconds / 60 - 1, width = OPTION_W) { finalSeconds = (it + 1) * 60; saveDraft(); refresh() })
        addView(label("목소리"))
        val voices = VOICE_NAMES.keys.toList()
        addView(segmented(VOICE_NAMES.values.toList(), voices.indexOf(voice), width = VOICE_W) { voice = voices[it]; saveDraft(); refresh() })
            if (voice == "rap") addView(caption("랩: 허밍 멜로디 대신 새 힙합 비트 위에서 빠르게 랩해요. 가사도 줄 끝 각운을 맞춘 랩 가사로 써요.", TEXT3))
        addView(weightSlider("내 멜로디 반영", "70 이상: 내 허밍 그대로 · 33~69: 허밍은 참고만, AI가 더 좋은 멜로디로 · 33 미만: AI가 자유롭게", melodyWeight) { melodyWeight = it; saveDraft() })
        addView(weightSlider("스타일 강도", "높을수록 주제의 장르·분위기를 진하게", styleWeight) { styleWeight = it; saveDraft() })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    /** 가중치 막대(0~100). 손을 떼면 값을 저장한다(다시 그리지 않아 끌기가 끊기지 않음). */
    private fun weightSlider(name: String, help: String, value: Int, onSet: (Int) -> Unit) = column().apply {
        val title = text("$name  $value", BODY_S_STRONG, TEXT3).withMargin(top = 24, bottom = 4)
        addView(title)
        addView(SeekBar(this@MainActivity).apply {
            contentDescription = name
            max = 100
            progress = value
            progressTintList = ColorStateList.valueOf(GRAY_600)
            progressBackgroundTintList = ColorStateList.valueOf(TINT_PRESSED)
            smallThumb(GRAY_600)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) { title.text = "$name  $p" }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) { onSet(progress) }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        addView(text(help, BODY_S, TEXT3))
    }

    /**
     * 서버에 보낼 목소리: '자동'은 내 음역(남성/여성) 쪽, '랩'은 내 음역에 맞춘 남성·여성 래퍼(2026-11-10 랩 옵션 —
     * 서버가 허밍 악보 없이 힙합 비트·랩 가사로 만든다. 악보를 따르면 노래처럼 불렀다).
     */
    private fun sungVoice(): String {
        val male = voiceRange.type()?.male
        return when (voice) {
            "auto" -> male?.let { if (it) "male" else "female" } ?: "auto"
            "rap" -> if (male == false) "rap_female" else "rap_male"
            else -> voice
        }
    }

    private fun createFinal(from: String? = null, keepLyrics: Boolean = false, lyrics: JSONObject? = null, keepCover: Boolean = false, edit: String? = null,
                            extra: JSONObject? = null) {
        val s = song ?: return
        if (finalSubmitting || recording || counting || TalkRecordService.recording) return
        // 만드는 중에 또 누르면 대기열로(2026-11-10 사용자) — 서버가 차례대로 만든다. 화면은 그대로 두고 알려만 준다
        val queueIt = finalJob != null
        finalSubmitting = true
        saveDraft()
        stopPlayback()
        val promptSnapshot = prompt
        // 목소리 '자동'이면 내 음역대 쪽(남성/여성)으로 — 따라 부르기 편한 노래가 되게
        val sung = sungVoice()
        val options = JSONObject().put("seconds", finalSeconds).put("voice", sung)
            .put("melody_weight", melodyWeight).put("style_weight", styleWeight).put("follow_mood", followMood)
        if (from != null) options.put("from", from).put("keep_lyrics", keepLyrics).put("keep_cover", keepCover)
        if (lyrics != null) options.put("lyrics", lyrics)
        extra?.keys()?.forEach { k -> options.put(k, extra.get(k)) }
        if (!edit.isNullOrBlank()) options.put("edit", edit)
        if (from == null) talkPayload(s)?.let { options.put("talk", it) }
        if (talkResult == null || talkFor != s.optString("id")) autoPickNote = null
        if (!queueIt) { playerVersion = null; showCreate() }
        io.execute {
            try {
                val j = api.startFinal(s.getString("id"), promptSnapshot, options)
                ui.post {
                    if (!isDestroyed && queueIt) {
                        finalSubmitting = false
                        queuedJobs.add(j); saveQueue()
                        android.widget.Toast.makeText(this, "대기열에 넣었어요 · ${queuedJobs.size}번째로 만들어요", android.widget.Toast.LENGTH_LONG).show()
                        if (active && !recording && !counting) refresh()
                    } else if (!isDestroyed) {
                        finalSubmitting = false
                        rememberFinalJob(j)
                        prompt = j.optString("prompt", promptSnapshot)
                        saveDraft()
                        if (active) showCreate()
                        pollFinal(s.getString("id"), j.getString("id"))
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    finalSubmitting = false
                    if (!isDestroyed && active) showError(e.message ?: "노래를 만들기 시작하지 못했어요.")
                }
            }
        }
    }

    // ------------------------------------------------------------------ 가사 미리 보기(2026-11-10 사용자: 가사가 별로 — 보고 고친 뒤 노래로)

    /** 서버가 쓴 가사 초안: {title, style, cover, sections, lines(줄마다 글자 수 목표), seconds}. */
    private var lyricDraft: JSONObject? = null
    private var lyricEdits = mutableMapOf<String, EditText>()
    private var lyricTitle: EditText? = null

    private fun showLyricsPreview() {
        val s = song ?: return showHome()
        val d = lyricDraft
        if (d == null) {
            show({
                addView(steps(2, busy = true))
                addView(headline("가사를 쓰는 중"))
                addView(body("주제 · 멜로디 느낌" + (if (talkPayload(s) != null) " · 대화 이야기" else "") + "로 가사를 쓰고 있어요. 약 1분. 다 쓰면 보고 고칠 수 있어요."))
            }, {}) { zones(button("← 노래 만들기") { showCreate() }, listOf(recordButton(Rec.BUSY, "가사 쓰는 중", onClick = null))) }
            val body = JSONObject().put("prompt", prompt).put("seconds", finalSeconds).put("follow_mood", followMood).put("voice", sungVoice())
            talkPayload(s)?.let { body.put("talk", it) }
            io.execute {
                try {
                    val r = api.writeLyrics(s.getString("id"), body)
                    ui.post { lyricDraft = r; if (active && song?.optString("id") == s.optString("id")) showLyricsPreview() }
                } catch (e: Exception) {
                    ui.post { if (active) showError(e.message ?: "가사를 쓰지 못했어요.") }
                }
            }
            return
        }
        val names = mapOf("verse" to "절", "verse2" to "절 2", "chorus" to "후렴", "bridge" to "브리지")
        lyricEdits = mutableMapOf()
        show({
            addView(steps(2))
            addView(headline("이 가사로 부를까요?"))
            addView(body("줄을 고치거나 다시 쓸 수 있어요. 줄마다 글자 수는 대략이면 돼요 — 멜로디를 가사에 맞춰 불러요." + (if (driving) " 고치기는 정차 중에만 돼요." else "")))
            addView(label("제목"))
            lyricTitle = EditText(this@MainActivity).apply {
                setText(d.optString("title")); isSingleLine = true; isEnabled = !driving
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE.size.toFloat()); setTextColor(TEXT)
                background = round(SURFACE, 24, TINT_PRESSED); setPadding(dp(28), dp(16), dp(28), dp(16))
            }
            addView(lyricTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val secs = d.optJSONObject("sections") ?: JSONObject()
            val lines = d.optJSONObject("lines") ?: JSONObject()
            secs.keys().forEach { k ->
                val a = secs.optJSONArray(k) ?: return@forEach
                val want = lines.optJSONArray(k)
                addView(label((names[k] ?: k) + (want?.let { w -> " · 줄마다 대략 " + (0 until w.length()).joinToString("·") { w.optInt(it).toString() } + "자" } ?: "")))
                val e = EditText(this@MainActivity).apply {
                    setText((0 until a.length()).joinToString("\n") { a.optString(it) })
                    isEnabled = !driving
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY.size.toFloat()); setTextColor(TEXT)
                    typeface = faces.getOrPut(BODY.font) { resources.getFont(BODY.font) }
                    background = round(SURFACE, 24, TINT_PRESSED); setPadding(dp(28), dp(16), dp(28), dp(16))
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    isSingleLine = false; minLines = a.length()
                }
                lyricEdits[k] = e
                addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }, { showLyricsPreview() }, tabs = true) {
            zones(button("다시 쓰기") { lyricDraft = null; showLyricsPreview() },
                  listOf(button("이 가사로 노래 만들기", Btn.SECONDARY) { useLyricDraft() }),
                  button("← 노래 만들기") { showCreate() })
        }
    }

    /** 고친 가사 그대로 노래 만들기(제목·스타일·표지 장면도 미리 쓴 것으로). */
    private fun useLyricDraft() {
        val d = lyricDraft ?: return
        val lyr = JSONObject()
        lyricEdits.forEach { (k, e) ->
            val ls = e.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (ls.isNotEmpty()) lyr.put(k, org.json.JSONArray(ls))
        }
        if (lyr.length() == 0) return
        val extra = JSONObject().put("title", lyricTitle?.text?.toString()?.trim().orEmpty().ifEmpty { d.optString("title") })
            .put("style", d.optString("style")).put("cover", d.optString("cover"))
        createFinal(lyrics = lyr, extra = extra)
    }

    /** 대기열: 지금 만드는 것 뒤에 서버에 넣어 둔 노래 작업들(앱을 다시 켜도 이어지게 prefs 에). */
    private val queuedJobs: MutableList<JSONObject> by lazy {
        runCatching { org.json.JSONArray(prefs.getString(QUEUED_JOBS, "[]")) }.getOrNull()
            ?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() } ?: mutableListOf()
    }

    private fun saveQueue() = prefs.edit().putString(QUEUED_JOBS, org.json.JSONArray(queuedJobs).toString()).apply()

    /** 앞 작업이 끝나면 다음 대기 작업의 진행을 이어서 본다(서버는 이미 차례대로 만들고 있다). */
    private fun startNextQueued() {
        val next = queuedJobs.removeFirstOrNull() ?: return
        saveQueue()
        rememberFinalJob(next)
        pollFinal(next.optString("song_id"), next.optString("id"))
    }

    /** 1초마다 작업 단계를 묻는다(약 3분). 끝나면 곡을 다시 받아 표지를 내려받고 노래 듣기 화면으로. 다른 서버 호출을 막지 않게 따로 돈다. */
    private fun pollFinal(songId: String, jobId: String) {
        val token = ++finalPollToken
        finalPollThread?.interrupt()
        finalPollThread = Thread {
            var failures = 0
            var lastStage = ""
            while (token == finalPollToken && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(if (failures == 0) 1000L else minOf(15000L, failures * 2000L))
                    val j = api.job(jobId)
                    when (j.getString("status")) {
                        "done" -> {
                            val completed = api.song(songId)
                            val final = completed.optJSONObject("final")
                            ui.post {
                                if (token == finalPollToken && !isDestroyed) {
                                    rememberFinalJob(null)
                                    startNextQueued()
                                    val sameSong = song?.optString("id") == songId
                                    if (sameSong) song = completed
                                    if (active && sameSong && !recording && !counting && drill == Drill.IDLE) {
                                        // 방금 만든 결과는 한 번 들려준다 — 미리 듣기 경로라 끝나면 듣던 차 음악으로 돌아간다
                                        playOnce = true
                                        showPlayer(autoplay = true)
                                        playOnce = false  // 첫 재생이 안 일어났으면(화면이 바뀐 경우 등) 다음 재생에 남지 않게
                                    } else if (active) {
                                        android.widget.Toast.makeText(this, "노래가 완성됐어요. 내 노래에서 확인하세요.", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    loadLibrary()
                                }
                            }
                            return@Thread
                        }
                        "failed" -> {
                            ui.post {
                                if (token == finalPollToken && !isDestroyed) {
                                    rememberFinalJob(null)
                                    startNextQueued()
                                    val message = "노래를 만들지 못했어요. ${j.optString("error").take(80)}"
                                    if (active && !recording && !counting) showError(message)
                                    else Log.w(tag, message)
                                }
                            }
                            return@Thread
                        }
                        else -> {
                            val stage = j.optString("stage")
                            val changed = stage != lastStage
                            lastStage = stage
                            failures = 0
                            ui.post {
                                if (token == finalPollToken && !isDestroyed) {
                                    finalJob = j
                                    if (active && !recording && !counting && drill == Drill.IDLE) {
                                        if (changed) refresh() else if (finalElapsed != null || (onHome && homeFinalText != null)) updateFinalPending(j)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (e: Exception) {
                    Log.w(tag, "노래 작업 상태 확인 실패: ${e.message}")
                    if (e is ApiException && e.code == "NOT_FOUND") {
                        ui.post {
                            if (token == finalPollToken && !isDestroyed) {
                                rememberFinalJob(null)
                                startNextQueued()
                                if (active && !recording && !counting) showError("이전 생성 작업을 찾지 못했어요. 내 노래에서 저장된 결과를 확인해 주세요.")
                                loadLibrary()
                            }
                        }
                        return@Thread
                    }
                    failures++
                    if (failures >= 8) {
                        ui.post {
                            if (token == finalPollToken && active && !isDestroyed) {
                                android.widget.Toast.makeText(this, "서버 연결을 확인해 주세요. 작업 상태 확인 버튼으로 다시 연결할 수 있어요.", android.widget.Toast.LENGTH_LONG).show()
                                if (!recording && !counting) refresh()
                            }
                        }
                        return@Thread
                    }
                }
            }
        }.apply { name = "Chajak-final-job"; isDaemon = true; start() }
    }

    private var finalElapsed: TextView? = null
    private var finalPendingBar: ProgressBar? = null

    /** 만드는 중 카드: 네 단계(멜로디 → 가사 → 표지 → 노래)에 지금 단계 표시 + 지난 시간 / 남은 시간. */
    private fun finalPendingRow(j: JSONObject): View {
        val box = column().apply {
            background = round(SURFACE, 24, POSITIVE)
            setPadding(dp(32), dp(24), dp(32), dp(28))
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 표지가 그려지면 먼저 보여 준다(첫 결과가 준비되면 알림 — 3분 기다림이 덜 지루하게)
            j.optString("cover_url").takeIf { it.isNotEmpty() }?.let { url ->
                addView(ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; background = round(TINT, 16); clipToOutline = true
                    loadCover(url, COVER_SMALL) { setImageBitmap(it) }
                }, LinearLayout.LayoutParams(dp(112), dp(112)).apply { marginEnd = dp(20) })
            }
            addView(column().apply {
                addView(text(j.optString("title").ifEmpty { j.optString("prompt") }, BODY_STRONG, TEXT))
                j.optString("edit_summary").takeIf { it.isNotEmpty() }?.let { addView(text("✦ $it", BODY_S, POSITIVE).withMargin(top = 4)) }
                j.optJSONObject("lyrics")?.let { l -> (l.optJSONArray("chorus") ?: l.optJSONArray("verse"))?.let { a ->
                    addView(text((0 until minOf(2, a.length())).joinToString("\n") { a.getString(it) }, BODY_S, TEXT2).withMargin(top = 6))
                } }
            })
        })
        if (j.optString("stage") == "edit") box.addView(text("●  말씀하신 내용을 설정으로 바꾸는 중", BODY_S_STRONG, POSITIVE).withMargin(top = 8))
        // 말로 고치기 해석 중(edit)이면 아래 네 단계는 아직 시작 전
        val now = if (j.optString("stage") == "edit") -1 else FINAL_STAGES.indexOfFirst { it.first == j.optString("stage") }.coerceAtLeast(0)
        FINAL_STAGES.forEachIndexed { i, (_, name) ->
            box.addView(text((if (i < now) "✓  " else if (i == now) "●  " else "○  ") + name,
                             if (i == now) BODY_S_STRONG else BODY_S, if (i == now) POSITIVE else if (i < now) TEXT2 else TEXT3).withMargin(top = 8))
        }
        finalPendingBar = progressBar()
        box.addView(finalPendingBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(16) })
        finalElapsed = text("", BODY_S, TEXT3).withMargin(top = 8)
        box.addView(finalElapsed)
        box.addView(button("작업 상태 다시 확인", size = Size.MEDIUM) {
            resumeFinalJob()
            android.widget.Toast.makeText(this, "생성 상태를 확인하고 있어요.", android.widget.Toast.LENGTH_SHORT).show()
        }.withMargin(top = 12))
        updateFinalPending(j)
        return box.withMargin(bottom = 16, fill = true)
    }

    private fun updateFinalPending(j: JSONObject) {
        val elapsed = (System.currentTimeMillis() / 1000.0 - j.optDouble("created", System.currentTimeMillis() / 1000.0)).toFloat()
        val eta = j.optDouble("eta_sec", 180.0).toFloat()
        finalPendingBar?.progress = (elapsed / eta * 1000).toInt().coerceIn(0, 980)
        finalElapsed?.text = "${clock(elapsed)} 지남 · 약 ${clock(maxOf(0f, eta - elapsed))} 남음 · 다른 화면을 봐도 계속 만들어요"
        homeFinalBar?.progress = (elapsed / eta * 1000).toInt().coerceIn(0, 980)
        homeFinalText?.text = homeFinalLine(j, elapsed, eta)
    }

    private var homeFinalBar: android.widget.ProgressBar? = null
    private var homeFinalText: TextView? = null

    private fun homeFinalLine(j: JSONObject, elapsed: Float, eta: Float): String {
        if (j.optString("stage") == "queue") return "차례를 기다리는 중 · 앞에 ${j.optInt("queue_ahead", 1)}개" + queueTail()
        val stage = FINAL_STAGES.firstOrNull { it.first == j.optString("stage") }?.second?.substringBefore(" (") ?: "준비하는 중"
        return "$stage · 약 ${clock(maxOf(0f, eta - elapsed))} 남음" + queueTail()
    }

    private fun queueTail() = if (queuedJobs.isNotEmpty()) " · 대기 ${queuedJobs.size}개" else ""

    /**
     * 녹음실의 "만드는 노래" 카드(2026-11-09 시나리오 점검): 전에는 "만드는 노래 · 상태 보기" 버튼 하나라 눌러야 진행을 알았다.
     * 지금 단계 · 남은 시간 · 진행 막대를 바로 보여 주고, 카드를 누르면 만드는 화면으로 간다.
     */
    private fun homeFinalCard(job: JSONObject): View = column().apply {
        background = pressable(round(SURFACE, 24, POSITIVE), round(TINT, 24))
        setPadding(dp(28), dp(20), dp(28), dp(20))
        contentDescription = "만드는 노래 상태 보기"
        setOnClickListener {
            resumeFinalJob()
            job.optString("song_id").takeIf { it.isNotEmpty() }?.let { openSong(it, create = true) }
        }
        val elapsed = (System.currentTimeMillis() / 1000.0 - job.optDouble("created", System.currentTimeMillis() / 1000.0)).toFloat()
        val eta = job.optDouble("eta_sec", 180.0).toFloat()
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(column().apply {
                addView(text(dot("●  노래 만드는 중", POSITIVE).toString(), BODY_STRONG, TEXT).apply { text = dot("●  노래 만드는 중", POSITIVE) })
                homeFinalText = text(homeFinalLine(job, elapsed, eta), BODY, TEXT2).withMargin(top = 4)
                addView(homeFinalText)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("보기 →", BODY_S_STRONG, POSITIVE))
        })
        homeFinalBar = android.widget.ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = (elapsed / eta * 1000).toInt().coerceIn(0, 980)
            progressTintList = android.content.res.ColorStateList.valueOf(POSITIVE)
        }
        addView(homeFinalBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(12) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
    }

    /** 완성된 노래 카드: 표지 · 제목 · 주제 · 길이 → 누르면 노래 듣기 화면. */
    private fun finalRow(f: JSONObject): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(round(SURFACE, 24), round(TINT, 24))
            setPadding(dp(20), dp(20), dp(24), dp(20))
            setOnClickListener { showPlayer(autoplay = false) }
        }
        box.addView(coverView(), LinearLayout.LayoutParams(dp(140), dp(140)).apply { marginEnd = dp(24) })
        val info = column()
        info.addView(text(f.getString("title"), TITLE, TEXT))
        info.addView(text(f.getString("prompt"), BODY_S, TEXT3).apply { maxLines = 1 }.withMargin(top = 4))
        info.addView(text("${clock(f.getDouble("duration_sec").toFloat())} · 내 멜로디 + 가사 + 표지", BODY_S, TEXT3).withMargin(top = 4))
        box.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(16) })
        box.addView(iconButton("▶", "${f.getString("title")} 듣기") { showPlayer(autoplay = true) })
        return box.withMargin(bottom = 16, fill = true)
    }

    /** 앨범 표지(둥근 모서리). 버전마다 표지가 달라 주소로 기억해 두고, 없으면 받아 온다. */
    private fun coverView(f: JSONObject? = song?.optJSONObject("final")): ImageView {
        val v = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(TINT, 20)
            clipToOutline = true
        }
        val url = f?.optString("cover_url")?.takeIf { it.isNotEmpty() } ?: return v
        loadCover(url, COVER_LARGE) { v.setImageBitmap(it) }
        return v
    }

    // ------------------------------------------------------------------ 표지 받기(한 곳)

    /** 받아 둔 표지(크기별). 큰 그림 몇 장으로 메모리가 차지 않게 24MB 까지만. */
    private val coverMem = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** 받는 중인 표지 → 다 받으면 그릴 칸들. 화면이 다시 그려질 때마다 같은 표지를 또 받던 것(2026-11-09, 터널에서 목록 1분)을 막는다. */
    private val coverWaiting = HashMap<String, MutableList<(Bitmap) -> Unit>>()

    /**
     * 표지 하나를 px 크기로 받아 onReady 에 준다(ui 스레드). 서버가 ?size= 로 줄인 JPEG 를 준다(1024px PNG 1.4MB → 384px 25KB).
     * 다 만든 노래의 표지는 디스크(cacheDir/covers)에도 남겨 앱을 다시 켜도 바로 뜬다. 만드는 중 표지(/v1/jobs/…)는 남기지 않는다.
     */
    private fun loadCover(url: String, px: Int, onReady: (Bitmap) -> Unit) {
        val key = url + (if ('?' in url) "&" else "?") + "size=$px"
        coverMem.get(key)?.let { return onReady(it) }
        coverWaiting[key]?.let { it.add(onReady); return }
        coverWaiting[key] = mutableListOf(onReady)
        val keep = !url.startsWith("/v1/jobs/")
        val file = File(cacheDir, "covers/" + sha1(api.base + key) + ".jpg")
        images.execute {
            val bmp = try {
                val bytes = if (keep && file.exists()) file.readBytes()
                            else api.bytes(key).also { if (keep) { file.parentFile?.mkdirs(); file.writeBytes(it) } }
                // 깨진·빈 그림이면 decode 가 null — 그대로 두면 Kotlin null 검사에서 앱이 죽는다
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run { file.delete(); null }
            } catch (e: Exception) {
                Log.w(tag, "표지 못 받음: ${e.message}")
                null
            }
            ui.post {
                val waiting = coverWaiting.remove(key) ?: return@post
                if (bmp != null) { coverMem.put(key, bmp); waiting.forEach { it(bmp) } }
            }
        }
    }

    private fun sha1(s: String) = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    /** 노래 듣기 화면에서 고른 버전(null 이면 가장 최근). */
    private var playerVersion: String? = null
    private var showRemix = false
    private var keepLyrics = true
    private var keepCover = false
    private var lyricsDraft: String? = null

    private var editText = ""

    /**
     * 말로 바꾸기(사용자는 결과만 말하고 작업 종류는 서버가 고른다): "더 신나게, 멜로디는 살려줘" 한 문장 → 서버의 로컬 LLM 이 설정으로 바꿔 새 버전.
     * 🎤 로 말해도 된다(운전 중 손 대신). 무엇을 바꿨는지는 만드는 중 카드·노래 화면에 한 줄로 알린다.
     */
    private fun editRow(f: JSONObject) = column().apply {
        background = round(SURFACE, 24)
        setPadding(dp(28), dp(16), dp(28), dp(20))
        addView(text(if (driving) "말로 바꾸기 · 🎤 누르고 말하면 바로 새 버전 · 예: 드럼 빼고 피아노만"
                     else "말로 바꾸기 · 예: 더 신나게, 멜로디는 살려줘", BODY_S_STRONG, TEXT3))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val input = EditText(this@MainActivity).apply {
                setText(editText)
                hint = if (listening) "말해 주세요…" else "어떻게 바꿀까요?"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_S.size.toFloat())
                setTextColor(TEXT); setHintTextColor(TEXT3)
                background = round(BG, 20, TINT_PRESSED)
                setPadding(dp(24), dp(12), dp(24), dp(12))
                maxLines = 2
                isEnabled = !driving && !finalSubmitting
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: Editable?) { editText = e.toString() }
                })
            }
            addView(input, LinearLayout.LayoutParams(0, dp(88), 1f).apply { marginEnd = dp(12) })
            fun submit(req: String) {
                if (req.isBlank() || finalSubmitting) return
                editText = ""
                prompt = f.optString("prompt", prompt)
                createFinal(from = f.optString("id").ifEmpty { null }, keepLyrics = true, keepCover = true, edit = req.trim())
            }
            // 주행 중에는 🎤 한 번 누르고 말하면 끝 — 말이 끝나면 버튼 없이 바로 새 버전(손·눈을 쓰는 단계를 하나 줄인다).
            // 정차 중에는 받아 적은 문장을 보고 고친 뒤 "새 버전"을 누른다
            addView(iconButton(if (listening) "■" else "🎤", if (listening) "말로 바꾸기 — 듣기 멈추기" else "말로 바꾸기 — 말하기") {
                voicePrompt(input, { editText = it }, onFinal = { if (driving) submit(it) })
            },
                LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(12) })
            addView(button("새 버전", Btn.SECONDARY, Size.MEDIUM, enabled = !finalSubmitting) { submit(editText) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    /** AI 제안의 "새 버전": 이 버전 설정을 바탕으로 하나만 바꿔 다시 만든다(표지는 그대로). */
    private fun remixWith(f: JSONObject, change: String) {
        val o = f.optJSONObject("options") ?: JSONObject()
        prompt = f.optString("prompt", prompt)
        finalSeconds = o.optInt("seconds", 120); voice = o.optString("voice", "auto")
        melodyWeight = o.optInt("melody_weight", 80); styleWeight = o.optInt("style_weight", 50)
        val (what, value) = change.split("=").let { it[0] to it.getOrElse(1) { "" } }
        var keep = true
        when (what) {
            "lyrics" -> keep = false
            "voice" -> voice = value
            "style" -> styleWeight = value.toIntOrNull() ?: 70
            "melody" -> melodyWeight = value.toIntOrNull() ?: 50
            "seconds" -> { finalSeconds = value.toIntOrNull() ?: 120; keep = false }
            "theme" -> { prompt = THEMES.filter { it != prompt }.random(); keep = false }
        }
        saveDraft()
        createFinal(from = f.optString("id").ifEmpty { null }, keepLyrics = keep, keepCover = what != "theme")
    }

    /** 가사 편집 칸 글 ↔ 구간별 가사. 같은 이름 구간(후렴)은 한 번만 적는다. */
    private fun lyricsText(f: JSONObject): String {
        val lyr = f.optJSONObject("lyrics") ?: return ""
        return lyr.keys().asSequence().joinToString("\n\n") { k ->
            "[${LYRIC_KEYS[k] ?: k}]\n" + lyr.getJSONArray(k).let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } }
        }
    }

    private fun parseLyrics(text: String): JSONObject {
        val back = LYRIC_KEYS.entries.associate { (k, v) -> v to k }
        val out = JSONObject()
        for (block in text.trim().split(Regex("\\n\\s*\\n"))) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val head = lines.firstOrNull()?.removeSurrounding("[", "]") ?: continue
            val key = back[head] ?: head
            out.put(key, org.json.JSONArray(lines.drop(1)))
        }
        return out
    }

    /** 가사 줄(시작 초, 끝 초, 글). 줄 = 2마디 — 곡 계획의 구간 시작·마디 수·템포로 계산한다. */
    private class LyricLine(val start: Float, val end: Float, val view: TextView)
    private var lyricViews = listOf<LyricLine>()
    /** 주행 중 가사 자리: 지금 부르는 줄 하나. 정차 중이면 null(전체 가사). */
    private var nowLine: TextView? = null

    /**
     * 노래 듣기 화면: 큰 앨범 표지 · 제목 · 주제, 위치를 옮길 수 있는 재생 막대, 가사(구간별).
     * 재생 중에는 지금 부르는 구간 가사를 진하게(빨강) — 곡 계획의 구간 시작 시각을 쓴다.
     */
    private fun showPlayer(autoplay: Boolean) {
        val s = song ?: return showHome()
        val finals = s.optJSONArray("finals")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: listOfNotNull(s.optJSONObject("final"))
        val f = finals.firstOrNull { it.optString("id") == playerVersion } ?: s.optJSONObject("final") ?: return showSong(false)
        val total = f.optDouble("duration_sec", 0.0).toFloat().coerceAtLeast(1f)
        val key = "final:" + f.optString("id")
        val meta = JSONObject().put("song", s.optString("id")).put("version", f.optString("id")).put("title", f.optString("title"))
            .put("cover", f.optString("cover_url"))
        lateinit var seek: SeekBar
        lateinit var time: TextView
        show({
            onPlayer = true
            addView(steps(3, done = true))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val left = column()
                left.addView(coverView(f), LinearLayout.LayoutParams(dp(PLAYER_COVER), dp(PLAYER_COVER)))
                if (finals.size > 1) {
                    left.addView(label("버전 ${finals.size}개"))
                    finals.forEachIndexed { i, v ->
                        val on = v.optString("id") == f.optString("id")
                        left.addView(button("v${i + 1}  ${v.getString("title")}", if (on) Btn.SECONDARY else Btn.PRIMARY, Size.MEDIUM) {
                            stopPlayback(); playerVersion = v.optString("id"); lyricsDraft = null; showPlayer(false)
                        }.apply { maxLines = 1; gravity = Gravity.CENTER_VERTICAL or Gravity.START }.withMargin(bottom = 12, fill = true))
                    }
                }
                run {
                    val key = "final:" + f.optString("id")
                    left.addView(button(if (deleteArmed == key) "한 번 더 누르면 지워요" else if (finals.size > 1) "이 버전 지우기" else "이 노래 지우기",
                        size = Size.MEDIUM, enabled = !driving && !finalSubmitting) {
                        if (deleteArmed != key) { deleteArmed = key; showPlayer(false); return@button }
                        deleteArmed = null; deleteFinal(s.optString("id"), f.optString("id"))
                    }.withMargin(top = 12, fill = true))
                    if (deleteArmed == key) left.addView(caption(if (finals.size > 1) "이 버전의 노래와 표지만 지워요. 멜로디와 다른 버전은 남아요."
                                                                else "노래와 표지를 지워요. 멜로디는 남아서 다시 만들 수 있어요.", RECORDING).withMargin(top = 8))
                }
                // 가사는 줄이 짧아 표지 아래 좁은 칸에, 넓은 오른쪽 칸은 듣기·다시 만들기에 쓴다(가사가 길어도 다시 만들기가 밀려나지 않게)
                left.addView(label(if (driving) "지금 부르는 줄" else "가사"))
                nowLine = if (driving) text("♪", TITLE, TEXT).also { left.addView(it) } else null
                val views = mutableListOf<LyricLine>()
                val lyr = f.optJSONObject("lyrics") ?: JSONObject()
                val plan = f.getJSONObject("plan")
                val barSec = 240f / plan.optDouble("tempo", 120.0).toFloat()
                val sections = plan.getJSONArray("sections")
                for (i in 0 until sections.length()) {
                    val sec = sections.getJSONObject(i)
                    if (!sec.getBoolean("sung")) continue
                    val name = sec.getString("name")
                    val lines = lyr.optJSONArray(name) ?: lyr.optJSONArray(name.trimEnd('2')) ?: continue
                    if (lines.length() == 0) continue
                    if (nowLine == null) left.addView(text(SECTION_NAMES[name.trimEnd('2')] ?: name, BODY_S_STRONG, TEXT3).withMargin(top = 12, bottom = 4))
                    val start = sec.getDouble("start_sec").toFloat()
                    val lineSec = sec.optInt("bars", 2 * lines.length()) * barSec / lines.length()
                    for (j in 0 until lines.length()) {
                        val tv = text(lines.getString(j), BODY, TEXT3).withMargin(bottom = 4)
                        views += LyricLine(start + j * lineSec, start + (j + 1) * lineSec, tv)
                        if (nowLine == null) left.addView(tv)
                    }
                }
                if (views.isEmpty()) left.addView(text("연주곡이에요(가사 없음).", BODY, TEXT3))
                lyricViews = views
                addView(left, LinearLayout.LayoutParams(dp(PLAYER_COVER), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(56) })
                val col = column()
                col.addView(headline(f.getString("title")))
                col.addView(text(f.getString("prompt"), BODY, TEXT2))
                f.optJSONObject("mood")?.let { m ->
                    col.addView(moodLine(m.optString("label"), "${Mood.name(m)}에 맞춘 " +
                        if (m.optBoolean("genre_applied", true)) m.optString("genre") else "분위기").withMargin(top = 4))
                }
                f.optString("edit_summary").takeIf { it.isNotEmpty() && it != "null" }?.let { col.addView(text("✦ 이번 버전: $it", BODY_S, POSITIVE).withMargin(top = 4)) }
                // 재생 위치 막대·시간은 아래 줄에서 재생 버튼과 함께(2026-11-10 사용자: "재생 관련된 건 하단 바에 함께")
                // 다시 만들기 한 곳: 말로 바꾸기 + AI 제안 단추 + 세부 설정(접힘)
                run {  // 만드는 중에도 다시 만들기 — 누르면 대기열로
                    col.addView(label("다시 만들기 · 마음에 안 드는 점을 말하거나 제안을 누르세요"))
                    col.addView(editRow(f))
                    if (!driving) col.addView(suggestCard(Suggest.song(f, s, voiceRange), !finalSubmitting) { remix -> remixWith(f, remix) }.withMargin(top = 12))
                }
                // 다시 만들기(설정 다시 쓰기 · 다른 편곡 · 가사 고치기): 이 버전을 바탕으로 가중치·주제·가사를 바꿔 새 버전
                col.addView(button(if (showRemix) "다시 만들기 접기 ▴" else "세부 설정으로 다시 만들기 · 가중치·가사·표지 ▾", size = Size.MEDIUM) {
                    showRemix = !showRemix
                    if (showRemix) { prompt = f.getString("prompt"); f.optJSONObject("options")?.let { o ->
                        finalSeconds = o.optInt("seconds", 120); voice = o.optString("voice", "auto")
                        melodyWeight = o.optInt("melody_weight", 80); styleWeight = o.optInt("style_weight", 50) } }
                    showPlayer(false)
                }.withMargin(top = 16))
                if (showRemix) {
                    val panel = column().apply { background = round(SURFACE, 24); setPadding(dp(28), dp(8), dp(28), dp(28)) }
                    panel.addView(caption("지금: ${f.optString("style")} · 멜로디 ${f.optJSONObject("options")?.optInt("melody_weight")} · 스타일 ${f.optJSONObject("options")?.optInt("style_weight")}", TEXT3))
                    panel.addView(label("주제"))
                    panel.addView(promptRow(enabled = true))
                    panel.addView(optionsPanel())
                    panel.addView(label("가사"))
                    panel.addView(segmented(listOf("가사 그대로", "새로 쓰기", "직접 고치기"), when { lyricsDraft != null -> 2; keepLyrics -> 0; else -> 1 }, width = OPTION_W) {
                        keepLyrics = it == 0
                        lyricsDraft = if (it == 2) lyricsText(f) else null
                        showPlayer(false)
                    })
                    lyricsDraft?.let { draft ->
                        panel.addView(EditText(this@MainActivity).apply {
                            setText(draft)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_S.size.toFloat())
                            setTextColor(TEXT)
                            background = round(BG, 20, TINT_PRESSED)
                            setPadding(dp(24), dp(16), dp(24), dp(16))
                            isEnabled = !driving
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                                override fun afterTextChanged(e: Editable?) { lyricsDraft = e.toString() }
                            })
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
                        panel.addView(caption("[절 1] [후렴] 같은 머리말은 그대로 두고 줄만 고치세요. 줄마다 음 개수와 비슷한 글자 수가 좋아요.", TEXT3))
                    }
                    panel.addView(label("표지"))
                    panel.addView(segmented(listOf("표지 그대로", "새로 그리기"), if (keepCover) 0 else 1, width = OPTION_W) { keepCover = it == 0; showPlayer(false) })
                    panel.addView(button("Create music · 새 버전", Btn.SECONDARY) {
                        showRemix = false
                        createFinal(from = f.optString("id").ifEmpty { null }, keepLyrics = keepLyrics && lyricsDraft == null,
                                    lyrics = lyricsDraft?.let { parseLyrics(it) }, keepCover = keepCover)
                    }.withMargin(top = 24, fill = true))
                    col.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
                }
                addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }, { showPlayer(false) }, tabs = true) {
            // 아래 줄: 위에 재생 위치 막대(지난 시간 · 막대 · 전체 시간), 그 아래 ← 내 멜로디 · 재생 · 처음부터
            orientation = LinearLayout.VERTICAL
            time = text("0:00", BODY_S_STRONG, TEXT2)
            seek = SeekBar(this@MainActivity).apply {
                contentDescription = "재생 위치"
                max = 1000
                progressTintList = ColorStateList.valueOf(POSITIVE)
                progressBackgroundTintList = ColorStateList.valueOf(TINT_PRESSED)
                smallThumb(POSITIVE)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(b: SeekBar?) {}
                    override fun onStopTrackingTouch(b: SeekBar?) {
                        if (playing == key && prepared) activePlayer?.seekTo((progress / 1000f * total * 1000).toLong())
                    }
                })
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(time, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(seek, LinearLayout.LayoutParams(0, dp(56), 1f))
                addView(text(clock(total), BODY_S_STRONG, TEXT3).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                zones(button("← 내 멜로디") { showSong(false) },
                    listOf(button(if (playing == key) "■  멈춤" else "▶  노래 듣기", Btn.SECONDARY) { nowPlaying = meta; toggle(key, f.getString("audio_url")) }),
                    button("처음부터") { stopPlayback(); nowPlaying = meta; toggle(key, f.getString("audio_url")) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (autoplay) { nowPlaying = meta; toggle(key, f.getString("audio_url")) }
        val token = scoreToken
        fun tick() {
            if (token != scoreToken) return
            val pos = if (playing == key && prepared) (activePlayer?.currentPosition ?: 0) / 1000f else 0f
            if (!seek.isPressed) seek.progress = (pos / total * 1000).toInt()
            time.text = clock(pos)
            // 지금 부르는 줄만 빨갛게, 지난 줄은 진하게, 남은 줄은 옅게(구간 사이 간주에는 빨간 줄 없음)
            nowLine?.text = lyricViews.firstOrNull { pos >= it.start && pos < it.end }?.view?.text ?: "♪"
            lyricViews.forEach { l ->
                l.view.setTextColor(when {
                    pos <= 0f || pos < l.start -> TEXT3
                    pos < l.end -> RECORDING
                    else -> TEXT2
                })
            }
            ui.postDelayed(::tick, 200)
        }
        tick()
    }

    /** 트랙 카드의 재생 막대(트랙 번호, 곡 안 시작 초, 길이). */
    private class TrackBar(val n: Int, val start: Float, val dur: Float, val bar: ProgressBar)

    private val trackBars = mutableListOf<TrackBar>()
    private var songBar: ProgressBar? = null
    private var songTime: TextView? = null

    private fun progressBar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(POSITIVE)
        progressBackgroundTintList = ColorStateList.valueOf(TINT_PRESSED)
    }

    private fun clock(sec: Float) = "%d:%02d".format(sec.toInt() / 60, sec.toInt() % 60)

    /**
     * 0.1초마다 재생 위치를 막대에 옮긴다. 트랙 하나를 들으면 그 트랙 막대만, 전체 듣기면 곡 막대와 함께
     * 각 트랙 막대가 곡 안 자기 자리(이어 붙이기면 start_sec 부터)에서 움직인다 — 지금 어느 트랙이 나오는지 보인다.
     */
    private fun trackProgress(total: Float) {
        val token = scoreToken
        fun tick() {
            if (token != scoreToken) return
            val pos = if (prepared) (activePlayer?.currentPosition ?: 0) / 1000f else 0f
            val mix = playing == "mix"
            songBar?.progress = if (mix && total > 0) (pos / total * 1000).toInt() else 0
            songTime?.text = "${clock(if (mix) pos else 0f)} / ${clock(total)}"
            for (t in trackBars) {
                val f = when (playing) {
                    "track${t.n}" -> pos / t.dur
                    "mix" -> (pos - t.start) / t.dur
                    else -> 0f
                }
                t.bar.progress = (f.coerceIn(0f, 1f) * 1000).toInt()
            }
            ui.postDelayed(::tick, 100)
        }
        tick()
    }

    /** 만들고 있는 트랙 자리: 트랙 카드와 같은 모양 + 파란 테두리 · 진행 막대 · 지금 단계 문구. */
    private fun pendingRow(p: Pending): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(SURFACE, 24, POSITIVE)
            setPadding(dp(32), dp(24), dp(24), dp(24))
        }
        val info = column()
        info.addView(text(p.title, BODY_STRONG, TEXT))
        pendingStep = text(p.steps[pendingIndex], BODY_S, POSITIVE).withMargin(top = 4)
        info.addView(pendingStep)
        info.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(POSITIVE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(12) })
        box.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(24) })
        box.addView(FrameLayout(this).apply {
            background = round(TINT, 20)
            addView(ProgressBar(this@MainActivity).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(TEXT3) },
                    FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(80), dp(80)))
        return box.withMargin(bottom = 16, fill = true)
    }

    private var pending: Pending? = null
    private var pendingStep: TextView? = null
    private var pendingIndex = 0
    private var pendingToken = 0

    /** 곡 화면에 새 트랙 자리를 띄우고, 서버가 끝낼 때까지 단계 문구를 차례로 넘긴다(마지막 단계에서 멈춤). */
    private fun startPending(p: Pending) {
        pending = p
        pendingIndex = 0
        val token = ++pendingToken
        showSong(false)
        fun next() {
            if (token != pendingToken || pendingIndex >= p.steps.size - 1) return
            pendingStep?.text = p.steps[++pendingIndex]
            ui.postDelayed(::next, PENDING_STEP_MS)
        }
        ui.postDelayed(::next, PENDING_STEP_MS)
    }

    private fun trackRow(t: JSONObject): View {
        val n = t.getInt("n")
        val what = when {
            t.has("variation") -> "변주 · " + (variations.firstOrNull { it.id == t.getString("variation") }?.name ?: t.getString("variation")) + " (트랙 ${t.getInt("source")})"
            t.optString("input") == "music" -> "음악에서 뽑은 멜로디" + if (n == 1) "" else " · ${t.getString("melody")}"
            n == 1 -> "내 허밍"
            else -> "내 허밍 · ${t.getString("melody")}"
        }
        val mode = if (n == 1) null else if (t.getString("mode") == "overlay") "겹치기" else "이어 붙이기 ${"%.1f".format(t.getDouble("start_sec"))}초부터"
        val chords = t.optJSONArray("chords")?.let { c -> (0 until c.length()).joinToString(" ") { c.getString(it) } } ?: ""
        val fit = if (t.optInt("transpose") != 0) " · 조성 맞춤 ${t.getInt("transpose")}반음" else ""
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(SURFACE, 24)
            setPadding(dp(32), dp(24), dp(24), dp(24))
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(text("$n  ${instrumentLabel(t.getString("instrument"))} · $what", BODY_STRONG, TEXT))
        val dur = t.optDouble("duration_sec", 0.0).toFloat()
        // 음 개수·화음 이름 같은 기술 정보는 빼고(음표 보기 ♪ 에 있다) 길이와 쌓은 방식만
        info.addView(text(listOfNotNull(clock(dur), mode, if (chords.isNotEmpty()) "반주 붙음" else null, fit.removePrefix(" · ").ifEmpty { null })
            .joinToString(" · "), BODY_S, TEXT3).withMargin(top = 4))
        val bar = progressBar()
        if (dur > 0) trackBars += TrackBar(n, t.optDouble("start_sec", 0.0).toFloat(), dur, bar)
        info.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(12) })
        box.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(24) })
        if ((t.optJSONArray("score")?.length() ?: 0) > 0)
            box.addView(iconButton("♪", "트랙 $n 음표 보기") { stopPlayback(); showScore(t) }, LinearLayout.LayoutParams(dp(80), dp(80)).apply { marginEnd = dp(16) })
        t.optString("hum_url").takeIf { it.isNotEmpty() && it != "null" }?.let { url ->
            // 전/후 들어보기: 내가 흥얼거린 원본 ↔ 음표로 바꾼 연주
            box.addView(button(if (playing == "hum$n") "■ 원본" else "원본", size = Size.MEDIUM) { toggle("hum$n", url) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
        }
        box.addView(iconButton(if (playing == "track$n") "■" else "▶", if (playing == "track$n") "트랙 $n 멈추기" else "트랙 $n 듣기") { toggle("track$n", t.getString("audio_url")) })
        return box.withMargin(bottom = 16, fill = true)
    }

    // ------------------------------------------------------------------ 동작

    /**
     * 음악 녹음: 듣던 음악을 멈추지 않고, 재생 중인 소리를 그대로 담는다(AudioPlaybackCapture — 동의 한 번으로 마지막 녹음 뒤 10분).
     * 동의를 못 받거나 차가 지원하지 않으면 차량 마이크로 듣는다. 다 담으면 허밍과 똑같이 음표로 바꿔 트랙·곡이 된다
     * (서버 SheetSage 가 노래 속 멜로디를 뽑는다). 원본은 음악 녹음 보관함에도 남는다.
     * stack: 곡 화면에서 누르면 그 곡에 겹쳐/이어 쌓고, 그 밖에서는 지금 쌓기 방식(stackMode) 그대로.
     */
    private fun startMusicRecording(stack: String? = stackMode) {
        if (recording || counting || pending != null || !active) return
        if (!prefs.getBoolean(VOICE_NOTICE_SEEN, false) && !driving) { stackMode = stack; return showVoiceNotice(music = true) }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            musicPermissionPending = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MUSIC_RECORD)
            return
        }
        stopPlayback()
        stackMode = stack
        if (android.os.Build.VERSION.SDK_INT < 29) return record(music = true)  // 재생 소리 녹음은 Android 10 부터
        // 차 안(앱들)에서 재생 중인 소리가 없으면 직접 담을 게 없다 — PC·폰 스피커로 트는 음악은 마이크로 담는다(2026-11-08:
        // 동의를 받아 두면 늘 재생 소리만 담아서, 에뮬레이터 밖 PC 에서 튼 음악이 무음으로 녹음됐다)
        if (!musicNeedsCapture()) { Log.i(tag, "음악에서 따기: 마이크로(${prefs.getString(MUSIC_SOURCE, "auto")})"); return record(music = true, viaMic = true) }
        // 이미 동의받은 게 살아 있으면(마지막 녹음 뒤 10분 안) 다시 묻지 않고 바로 담는다
        if (projection != null) { ui.removeCallbacks(captureIdle); return record(music = true) }
        if (driving) return  // 버튼이 "처음은 정차 후에"로 꺼져 있다 — 긴 설명과 시스템 창을 주행 중에 띄우지 않는다
        // 시스템 동의 창은 앱이 꾸밀 수 없다(문구·모양 고정) — 그래서 뜨기 전에 우리 화면으로 무엇을 묻는 창인지 먼저 알린다
        showCapturePrimer()
    }

    /** 시스템 "녹화 또는 전송" 창 앞의 설명 화면. 2026-11-07 사용자: "너무 갑자기 다른 UI가 나온다". */
    private fun showCapturePrimer() {
        show({
            addView(steps(0))
            addView(headline("듣던 음악에서 멜로디 따기"))
            addView(body("지금 재생 중인 노래를 그대로 담아 멜로디를 음표로 바꿔요. 음악은 멈추지 않아요."))
            addView(column().apply {
                background = round(SURFACE, 24)
                setPadding(dp(32), dp(24), dp(32), dp(28))
                addView(text("다음에 안드로이드 확인 창이 한 번 떠요", BODY_STRONG, TEXT))
                addView(text("창에는 '녹화 또는 전송'이라고 나오지만, 차작은 화면을 녹화하지 않고 재생 중인 소리만 담아요. " +
                             "창에 적힌 비밀번호·메시지 같은 화면 정보는 쓰지 않아요.", BODY, TEXT2).withMargin(top = 12))
                addView(text("'시작하기'를 누르면 바로 녹음이 시작돼요. 차작을 쓰는 동안(마지막 녹음 뒤 10분)은 다시 묻지 않아요.", BODY, TEXT2).withMargin(top = 12))
            }.withMargin(top = 24, fill = true))
            addView(caption("남의 노래 멜로디는 연습·참고용으로만 쓰고, 내 노래로 공유하지 마세요.", TEXT3).withMargin(top = 16))
        }, ::showCapturePrimer, tabs = true) {
            zones(button("취소") { showHome() },
                listOf(button("확인 창 열기", Btn.SECONDARY) { requestCapture() }),
                null)
        }
    }

    private fun requestCapture() {
        if (recording || counting || pending != null || !active) return
        try {
            capturePending = true
            startActivityForResult(projections.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: Exception) {
            capturePending = false
            Log.w(tag, "재생 소리 녹음 동의 창을 열 수 없음 — 차량 마이크로: ${e.message}")
            record(music = true)
        }
    }

    private val projections by lazy { getSystemService(android.media.projection.MediaProjectionManager::class.java) }
    private var projection: android.media.projection.MediaProjection? = null
    /** 동의 창이 떠서 화면이 잠깐 가려진 동안(onPause 에서 재생을 멈추지 않게). */
    private var capturePending = false

    /** 동의 결과. 포그라운드 서비스(mediaProjection)가 뜬 뒤에야 MediaProjection 을 받을 수 있다. */
    private fun onCaptureConsent(resultCode: Int, data: Intent?) {
        capturePending = false
        if (resultCode != RESULT_OK || data == null) {
            android.widget.Toast.makeText(this, "녹음 동의를 하지 않아 차량 마이크로 들을게요.", android.widget.Toast.LENGTH_LONG).show()
            ui.post { if (active) record(music = true) }
            return
        }
        startForegroundService(Intent(this, CaptureService::class.java))
        val until = android.os.SystemClock.elapsedRealtime() + 3000
        fun begin() {
            if (!CaptureService.started && android.os.SystemClock.elapsedRealtime() < until) { ui.postDelayed(::begin, 50); return }
            val p = runCatching { projections.getMediaProjection(resultCode, data) }.onFailure { Log.w(tag, "MediaProjection 실패: ${it.message}") }.getOrNull()
            p?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() { if (projection === p) projection = null }
            }, ui)
            projection = p
            if (active) record(music = true) else stopCapture()
        }
        begin()
    }

    /** 동의 한 번을 다시 쓰는 시간: 마지막 음악 녹음 뒤 이만큼 안 쓰면 놓는다(상태 표시줄 표시·알림도 꺼진다). */
    private val captureIdle = Runnable { if (!recording && !counting) stopCapture() }

    private fun holdCapture() {
        ui.removeCallbacks(captureIdle)
        ui.postDelayed(captureIdle, CAPTURE_IDLE_MS)
    }

    private fun stopCapture() {
        ui.removeCallbacks(captureIdle)
        projection?.stop()
        projection = null
        stopService(Intent(this, CaptureService::class.java))
    }

    /** 재생 중인 음악(미디어·게임·기타 소리)을 그대로 담는 녹음 원본. 차작 자기 소리는 뺀다. */
    @androidx.annotation.RequiresApi(29)
    private fun captureSource(p: android.media.projection.MediaProjection): (android.media.AudioFormat, Int) -> android.media.AudioRecord = { format, buffer ->
        @Suppress("MissingPermission")
        android.media.AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(buffer)
            .setAudioPlaybackCaptureConfig(android.media.AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(android.os.Process.myUid())
                .build())
            .build()
    }

    /** 음악 녹음 원본을 보관함에 남긴다(파일 이름 = 시각). */
    private fun keepMusicRecording(file: File) {
        val dir = File(filesDir, "music-recordings")
        if (dir.isDirectory || dir.mkdirs()) runCatching { file.copyTo(File(dir, "music-${System.currentTimeMillis()}.wav")) }
            .onFailure { Log.w(tag, "음악 녹음 보관 실패: ${it.message}") }
    }

    private fun musicFiles(): List<File> = File(filesDir, "music-recordings").listFiles()
        ?.filter { it.isFile && it.extension == "wav" && it.length() > 44 }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun showMusicLibrary() {
        val files = musicFiles()
        var bar: SeekBar? = null
        var now: TextView? = null
        var nowTotal = 1f
        show({
            if (files.isEmpty()) addView(body("빨간 녹음 버튼 오른쪽의 작은 ‘음악 녹음’ 버튼으로 첫 소리를 담아 보세요.").withMargin(top = 24))
            files.forEach { file ->
                val key = "recording:${file.name}"
                val duration = (file.length() - 44) / 96000f
                val date = java.text.SimpleDateFormat("MM.dd HH:mm", java.util.Locale.KOREA).format(java.util.Date(file.lastModified()))
                val name = prefs.getString(REC_NAME + file.name, null)
                val box = column().apply {
                    background = round(SURFACE, 24)
                    setPadding(dp(24), dp(16), dp(24), dp(16))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(column().apply {
                            addView(text(name ?: "$date 녹음", BODY_STRONG, TEXT).apply { maxLines = 1 })
                            addView(text(if (name != null) "$date · ${clock(duration)}" else clock(duration), BODY_S, TEXT3).withMargin(top = 2))
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        // 이름 바꾸기(2026-11-10 사용자) — 글자 입력이라 정차 중에만
                        addView(button("✎ 이름", size = Size.MEDIUM, enabled = !driving) { renameRecording(file, name ?: "$date 녹음") })
                    })
                    // 듣는 중이면 칸 안에 재생 위치 막대(2026-11-10 사용자: "슬롯 안에 프로그래스바가 없어 불편")
                    if (playing == key) {
                        nowTotal = duration.coerceAtLeast(1f)
                        now = text("0:00", BODY_S_STRONG, TEXT2)
                        bar = SeekBar(this@MainActivity).apply {
                            contentDescription = "녹음 재생 위치"
                            max = 1000
                            progressTintList = ColorStateList.valueOf(POSITIVE)
                            progressBackgroundTintList = ColorStateList.valueOf(TINT_PRESSED)
                            smallThumb(POSITIVE)
                            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {}
                                override fun onStartTrackingTouch(b: SeekBar?) {}
                                override fun onStopTrackingTouch(b: SeekBar?) {
                                    if (playing == key && prepared) activePlayer?.seekTo((progress / 1000f * nowTotal * 1000).toLong())
                                }
                            })
                        }
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(now, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
                            addView(bar, LinearLayout.LayoutParams(0, dp(48), 1f))
                            addView(text(clock(duration), BODY_S_STRONG, TEXT3).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
                        }.withMargin(top = 8))
                    }
                    addView(row(
                        button(if (playing == key) "■ 멈춤" else "▶ 녹음 듣기", size = Size.MEDIUM) { toggle(key, file.absolutePath) },
                        button("파일로 저장", size = Size.MEDIUM, enabled = !driving) { exportRecording(file) },
                        button("멜로디로 만들기", size = Size.MEDIUM, enabled = !driving && pending == null) {
                            stopPlayback(); stackMode = null; song = null; upload(file, music = true)
                        }
                    ).withMargin(top = 12))
                }
                val delete = text("삭제", BODY_STRONG, Color.WHITE).apply {
                    gravity = Gravity.CENTER
                    background = pressable(round(RECORDING, 24), round(Color.parseColor("#C8231B"), 24))
                    contentDescription = "녹음 삭제"
                    setOnClickListener { deleteRecording(file, key) }
                }
                // 주행 중에는 밀어서 지우기를 막는다(파일로 저장과 같은 규칙)
                addView(SwipeRow(this@MainActivity, box, delete, dp(180)).apply { enabledSwipe = !driving }
                    .withMargin(top = 16, fill = true))
            }
        }, ::showMusicLibrary, tabs = true, header = {
            addView(headline("음악 녹음 보관함"))
            addView(body("음악 녹음 버튼으로 담아 둔 소리예요. ${files.size}개 보관 중 · 멜로디로 만들면 허밍처럼 트랙이 돼요."))
            if (files.isNotEmpty() && !driving) addView(caption("지울 녹음은 왼쪽으로 밀어 삭제를 누르세요.", TEXT3))
        }) {
            zones(button("홈으로") { showHome() }, listOf(musicRecordButton(!driving)))
        }
        val token = scoreToken
        fun tick() {
            if (token != scoreToken) return
            val b = bar ?: return
            val pos = if (prepared) (activePlayer?.currentPosition ?: 0) / 1000f else 0f
            if (!b.isPressed) b.progress = (pos / nowTotal * 1000).toInt().coerceIn(0, 1000)
            now?.text = clock(pos)
            ui.postDelayed({ tick() }, 250)
        }
        tick()
    }

    /** 음악 녹음 이름 바꾸기(파일은 그대로, 이름은 기기 설정에). 비우면 날짜 이름으로 돌아간다. */
    private fun renameRecording(file: File, current: String) {
        if (driving) return
        val input = EditText(this).apply {
            setText(current); setSelection(current.length)
            maxLines = 1; isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY.size.toFloat())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("녹음 이름")
            .setView(LinearLayout(this).apply { setPadding(dp(32), dp(8), dp(32), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
            .setPositiveButton("저장") { _, _ ->
                val v = input.text.toString().trim().take(40)
                prefs.edit().apply { if (v.isEmpty()) remove(REC_NAME + file.name) else putString(REC_NAME + file.name, v) }.apply()
                showMusicLibrary()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteRecording(file: File, key: String) {
        if (driving) return
        if (playing == key) stopPlayback()
        if (!file.delete()) return showError("녹음을 지우지 못했어요.")
        prefs.edit().remove(REC_NAME + file.name).apply()
        Log.i(tag, "음악 녹음 삭제: ${file.name}")
        showMusicLibrary()
    }

    private var exportSource: File? = null

    @Suppress("DEPRECATION")
    private fun exportRecording(file: File) {
        if (driving) return
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            io.execute {
                val result = runCatching {
                    val resolver = contentResolver
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "audio/wav")
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Chajak")
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("저장 위치를 만들지 못했어요.")
                    try {
                        val stream = resolver.openOutputStream(uri) ?: error("파일을 열지 못했어요.")
                        stream.use { out -> file.inputStream().use { it.copyTo(out) } }
                        values.clear()
                        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                }
                ui.post {
                    if (!isDestroyed) android.widget.Toast.makeText(this,
                        if (result.isSuccess) "다운로드/Chajak에 녹음을 저장했어요." else "파일 저장에 실패했어요. 원본은 보관함에 있어요.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        exportSource = file
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/wav"
                putExtra(Intent.EXTRA_TITLE, file.name)
            }, REQ_EXPORT_RECORDING)
        } catch (e: Exception) {
            exportSource = null
            android.widget.Toast.makeText(this, "이 기기에서 파일 저장 화면을 열 수 없어요.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Framework callback retained for this dependency-free app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) return onCaptureConsent(resultCode, data)
        if (requestCode != REQ_EXPORT_RECORDING) return
        val file = exportSource
        exportSource = null
        val uri = data?.data
        if (resultCode != RESULT_OK || file == null || uri == null) return
        io.execute {
            val result = runCatching {
                val stream = contentResolver.openOutputStream(uri, "wt") ?: error("저장 위치를 열지 못했어요.")
                stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            }
            ui.post {
                if (!isDestroyed) android.widget.Toast.makeText(this,
                    if (result.isSuccess) "녹음 파일을 저장했어요." else "파일 저장에 실패했어요. 원본 녹음은 보관함에 있어요.",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 작성 중인 생성 설정은 화면/프로세스가 바뀌어도 유지한다. */
    private fun saveDraft() {
        prefs.edit().putString("draft_prompt", prompt).putInt("draft_seconds", finalSeconds)
            .putString("draft_voice", voice).putInt("draft_melody", melodyWeight)
            .putInt("draft_style", styleWeight).putInt("draft_instrument", instrument)
            .putString("draft_melody_mode", melody).putBoolean("draft_accompaniment", accompaniment)
            .putBoolean("draft_follow_mood", followMood).apply()
    }

    private fun restoreDraft() {
        prompt = prefs.getString("draft_prompt", "") ?: ""
        finalSeconds = prefs.getInt("draft_seconds", 120).takeIf { it in listOf(60, 120, 180) } ?: 120
        voice = (prefs.getString("draft_voice", "auto") ?: "auto").takeIf { it in VOICE_NAMES } ?: "auto"
        melodyWeight = prefs.getInt("draft_melody", 80).coerceIn(0, 100)
        styleWeight = prefs.getInt("draft_style", 50).coerceIn(0, 100)
        followMood = prefs.getBoolean("draft_follow_mood", true)
        instrument = prefs.getInt("draft_instrument", 0).coerceIn(0, INSTRUMENTS.lastIndex)
        melody = prefs.getString("draft_melody_mode", "그대로")?.takeIf { it in listOf("그대로", "다듬기") } ?: "그대로"
        accompaniment = prefs.getBoolean("draft_accompaniment", true)
    }

    private fun startRecording() {
        stopPlayback()
        cancelled = false
        if (testInputs.isNotEmpty()) {
            val f = File(filesDir, testInputs[testIndex++ % testInputs.size])
            return upload(f)
        }
        if (!prefs.getBoolean(VOICE_NOTICE_SEEN, false) && !driving) return showVoiceNotice(music = false)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!holdFocus()) return showError(FOCUS_DENIED)
        countdown(3)
    }

    /**
     * 첫 녹음 전에 한 번: 녹음한 소리가 어디로 가서 무엇에 쓰이는지 알린다(2026-11-09).
     * 사실만 적는다 — 서버로 보내 음표로 바꾸고, 곡과 녹음은 내 노래에 남고, AI 학습·공유에는 쓰지 않는다.
     * 녹음 버튼은 녹음실과 같은 자리(탭 있는 틀) — 한 번 더 누르면 바로 녹음이 시작된다.
     */
    private fun showVoiceNotice(music: Boolean) {
        fun go() {
            prefs.edit().putBoolean(VOICE_NOTICE_SEEN, true).apply()
            if (music) startMusicRecording() else startRecording()
        }
        show({
            addView(steps(0))
            addView(headline("녹음한 소리는 노래를 만드는 데만 써요"))
            addView(voiceFacts(music).withMargin(top = 24, fill = true))
            addView(caption("이 안내는 처음 한 번만 보여요. 설정 탭에서 다시 볼 수 있어요.", TEXT3).withMargin(top = 16))
        }, { showVoiceNotice(music) }, tabs = true) {
            zones(button("취소") { showHome() },
                listOf(recordButton(Rec.READY, "확인하고 녹음") { go() }))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when {
            requestCode == REQ_DRILL -> if (granted) startDrill() else showTutorial()
            requestCode == REQ_VOICE -> if (granted) promptInput?.let { voicePrompt(it) }
            requestCode == REQ_TALK -> if (granted) startTalk() else showTalkIntro("대화를 녹음하려면 마이크 권한이 필요해요.")
            requestCode == REQ_MUSIC_RECORD -> {
                musicPermissionPending = false
                // Permission dialogs may finish before onResume; require an explicit second tap.
                if (granted && active) startMusicRecording()
                else if (!granted) showError("음악 녹음에는 마이크 권한이 필요해요.")
            }
            granted -> if (holdFocus()) countdown(3) else showError(FOCUS_DENIED)
            else -> showError("마이크 권한이 있어야 흥얼거림을 들을 수 있어요.")
        }
    }

    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var focus: AudioFocusRequest? = null

    /**
     * 카운트다운부터 녹음 끝까지 오디오 포커스를 잠깐 빌려 차에서 나오던 음악을 멈춘다(12번 시나리오 3-1).
     * 거절되면(통화 중 등) false — 녹음하지 않는다. 녹음 중 통화·안내가 포커스를 가져가면 녹음을 멈춘다
     * (그 소리가 허밍에 섞이면 멜로디를 못 뽑는다). 줄여 달라는(duck) 정도는 그대로 둔다.
     */
    private fun holdFocus(): Boolean {
        releaseFocus()
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(FOCUS_USAGE).build())
            .setOnAudioFocusChangeListener({ change ->
                Log.i(tag, "녹음 포커스 변화 $change")
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) interruptRecording()
            }, ui)
            .build()
        val granted = audio.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(tag, "오디오 포커스 요청: ${if (granted) "받음" else "거절"}")
        focus = if (granted) r else null
        return granted
    }

    /** 통화·안내가 끼어들었다: 카운트다운이면 취소, 녹음 중이면 버리고 알린다. 연습·말로 주제는 그냥 멈춘다. */
    private fun interruptRecording() {
        when {
            counting -> { cancelCountdown(); showError(FOCUS_LOST) }
            recording && drill == Drill.IDLE -> { interrupted = true; cancelled = true; recorder.stop() }
            drill != Drill.IDLE -> { cancelDrill(); showTutorial() }
            listening -> recognizer?.stopListening()
        }
    }

    private var interrupted = false

    // 주행 규칙(기획서 v4): 녹음·듣기·다듬기·🎲·🎤·Create music 은 주행 중에도(버튼 한 번), 글자 입력·더 만들기·파일 정리는 정차에서
    private val canRecord get() = true

    private fun releaseFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it); Log.i(tag, "오디오 포커스 반납") }
        focus = null
    }

    private fun countdown(n: Int, token: Int = ++countdownToken) {
        if (token != countdownToken) return
        if (!active) {
            cancelCountdown()
            return
        }
        if (n == 0) {
            // 시작음이 끝난 뒤 녹음을 연다 — 효과음이 녹음에 섞이지 않게
            showCountdown(0)
            beep(true)
            ui.postDelayed({ if (token == countdownToken) { counting = false; record() } }, BEEP_GAP_MS)
            return
        }
        counting = true
        showCountdown(n)
        beep(false)
        ui.postDelayed({ countdown(n - 1, token) }, 1000)
    }

    /** 카운트다운 효과음. 재생이 끝나면 놓아준다. */
    private fun beep(start: Boolean) {
        val t = GuideTone.beep(start)
        ui.postDelayed({ t.release() }, 1000)
    }

    private fun cancelCountdown() {
        countdownToken++
        counting = false
        releaseFocus()
        notice = CANCELLED
        showHome()
    }

    // ------------------------------------------------------------------ 음역대

    private var liveRange: RangeBar? = null
    private var wave: LevelStrip? = null
    /** 지금 음높이 색(녹음 링·퍼지는 고리). */
    private var pitchColor = RECORDING
    private val voiceRange: VoiceRange get() = VoiceRange.decode(prefs.getString("voice_range", null))

    /** 허밍 한 번이 1초 넘게 들렸으면 내 음역대에 더한다. */
    private fun rememberRange(take: VoiceRange) {
        if (!take.enough) return
        val all = voiceRange.apply { addAll(take) }
        prefs.edit().putString("voice_range", all.encode()).putInt("voice_takes", prefs.getInt("voice_takes", 0) + 1).apply()
        Log.i(tag, "음역대 갱신: ${all.low?.let(VoiceRange::name)}–${all.high?.let(VoiceRange::name)} · ${all.type()?.name}")
    }

    /** 허밍 녹음실의 "내 음역대" 카드: 막대 + 낮은~높은 음 · 유형 쪽 · 편한 음. */
    private fun rangeCard(): View = column().apply {
        background = round(SURFACE, 24)
        setPadding(dp(28), dp(20), dp(28), dp(16))
        val v = voiceRange
        val type = v.type()
        addView(text("내 음역대", BODY_S_STRONG, TEXT3))
        if (!v.enough) {
            // 아직 모르면 한 줄만(막대는 녹음 화면에서 보인다)
            addView(text("흥얼거리면 내 음역대가 여기에 쌓여요.", BODY_S, TEXT2).withMargin(top = 4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
            return@apply
        } else {
            addView(text(listOfNotNull("${VoiceRange.name(v.low!!)}–${VoiceRange.name(v.high!!)}", type?.let { "${it.name} 쪽" },
                "편한 음 ${VoiceRange.name(v.comfortLow!!)}–${VoiceRange.name(v.comfortHigh!!)}").joinToString(" · "), BODY_STRONG, TEXT).withMargin(top = 8))
        }
        addView(RangeBar(this@MainActivity, faces.getOrPut(BODY_S_STRONG.font) { resources.getFont(BODY_S_STRONG.font) }).apply { range = v },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        if (v.enough) addView(text("허밍 ${prefs.getInt("voice_takes", 0)}번으로 잰 값이에요. 노래로 부를 때보다 좁게 나올 수 있어요." +
            (type?.let { " 노래 만들기 목소리가 '자동'이면 ${if (it.male) "남성" else "여성"} 목소리로 불러요." } ?: ""), BODY_S, TEXT3))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
    }

    // ------------------------------------------------------------------ 녹음 장치

    /** 고른 녹음 장치(지금 연결돼 있을 때). null = 기본 마이크. */
    private val inputDevice: android.media.AudioDeviceInfo? get() = InputDevices.chosen(audio, prefs)

    /** 폰·헤드셋·USB 마이크가 붙거나 빠지면 녹음실의 목록을 다시 그린다. */
    private val deviceWatch = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { if ((onHome || onDeviceCheck) && !recording && probing == null) refresh() }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { if ((onHome || onDeviceCheck) && !recording && probing == null) refresh() }
    }

    /**
     * 4단계 진행 막대: 흥얼거리기 → 음표로 바꾸기 → 노래 만들기 → 완성.
     * 지난 단계는 파랑, 지금 단계는 진회색(만드는 중이면 '…'), 남은 단계는 옅게. done 이면 모두 파랑.
     */
    private fun steps(at: Int, busy: Boolean = false, done: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        STEPS.forEachIndexed { i, name ->
            val past = done || i < at
            val now = !done && i == at
            addView(column().apply {
                // 갈 수 있는 단계는 눌러서 이동: 1 녹음실 · 2 내 멜로디(곡 있음) · 3 노래 만들기(곡 있음) · 4 완성(노래 있음)
                val s = song
                val go: (() -> Unit)? = when (i) {
                    0 -> if (now) null else ({ stackMode = null; showHome() })
                    1 -> if (s != null && !now) ({ showSong(false) }) else null
                    2 -> if (s != null && !now) ({ showCreate() }) else null
                    else -> if (s?.optJSONObject("final") != null) ({ showPlayer(false) }) else null
                }
                if (go != null && !recording && !counting) setOnClickListener { go() }
                addView(View(this@MainActivity).apply { background = round(if (past) POSITIVE else if (now) GRAY_600 else TINT_PRESSED, 4) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
                addView(text("${i + 1} $name${if (now && busy) " …" else ""}", if (now) BODY_S_STRONG else BODY_S,
                    if (past) POSITIVE else if (now) TEXT else TEXT3).withMargin(top = 8))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(12) })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(24) }
    }

    /** 제목 + 오른쪽 위 작은 조작(녹음 장치 등). */
    private fun titleRow(title: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(right)
    }

    /**
     * 오른쪽 위 작은 "🎤 녹음 장치 ▾". 누르면 연결된 입력 장치 목록이 알약 아래 오른쪽 정렬로
     * 뜨고 고른 것에 ✓. 맨 아래 한 줄은 폰 마이크 안내(누를 수 없음).
     */
    private fun inputPill(): View {
        val chosen = inputDevice
        lateinit var pill: TextView
        pill = text("🎤  ${chosen?.let(InputDevices::label) ?: "녹음 장치"}  ▾", BODY_S_STRONG, if (driving) TEXT_DISABLED else TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = pressable(round(TINT, 40), round(TINT_PRESSED, 40))
            contentDescription = "녹음 장치 고르기"
            isEnabled = !driving
            setOnClickListener {
                val devices = listOf<android.media.AudioDeviceInfo?>(null) + InputDevices.list(audio)
                val names = devices.map { it?.let(InputDevices::label) ?: DEFAULT_INPUT }
                val selected = devices.indexOfFirst { (it == null && chosen == null) || (it != null && chosen != null && InputDevices.key(it) == InputDevices.key(chosen)) }
                val popup = android.widget.ListPopupWindow(this@MainActivity)
                popup.anchorView = pill
                popup.width = dp(INPUT_MENU_W)
                popup.setDropDownGravity(Gravity.END)
                popup.verticalOffset = dp(8)
                popup.isModal = true
                popup.setBackgroundDrawable(round(SURFACE, 20, TINT_PRESSED))
                popup.setAdapter(object : android.widget.BaseAdapter() {
                    override fun getCount() = names.size + 1
                    override fun getItem(i: Int) = names.getOrNull(i)
                    override fun getItemId(i: Int) = i.toLong()
                    override fun isEnabled(i: Int) = true
                    override fun getView(i: Int, convert: View?, parent: ViewGroup?): View =
                        if (i == names.size) column().apply {
                            setPadding(dp(24), dp(16), dp(24), dp(16))
                            addView(text("🔍  인식된 장치 전부 보기 · 소리 확인", BODY_S_STRONG, POSITIVE))
                            addView(text("폰 마이크는 차가 인식할 때만 보여요.", BODY_S, TEXT3).withMargin(top = 4))
                        }
                        else LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(24), dp(16), dp(24), dp(16))
                            addView(text("🎤", BODY_S, TEXT3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(14) })
                            addView(text(names[i], if (i == selected) BODY_S_STRONG else BODY_S, TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            if (i == selected) addView(text("✓", BODY_S_STRONG, TEXT))
                        }
                })
                popup.setOnItemClickListener { _, _, i, _ ->
                    if (i >= devices.size) { popup.dismiss(); showDeviceCheck(); return@setOnItemClickListener }
                    InputDevices.choose(prefs, devices[i])
                    Log.i(this@MainActivity.tag, "녹음 장치: ${names[i]}")
                    popup.dismiss()
                    refresh()
                }
                popup.show()
            }
        }
        return pill
    }

    /** 음악에서 따기를 "재생 소리 직접"으로 할지. 자동이면 차 안에서 재생 중인 소리가 있을 때만. */
    private fun musicNeedsCapture(): Boolean = when (prefs.getString(MUSIC_SOURCE, "auto")) {
        "direct" -> true
        "mic" -> false
        // 가상 폰 블루투스 음악(두아 리파)이 나오는데 '자동'이 마이크를 골랐다(2026-11-10) — 음악 스트림이 켜져 있으면 차 안 재생으로 본다
        else -> DeviceCheck.capturablePlaying(audio) || audio.isMusicActive
    }

    private var onDeviceCheck = false
    /** 소리 재는 중인 장치 키(기본 마이크 = DEFAULT_KEY). 화면이 바뀌면 멈춘다. */
    private var probing: String? = null
    private val probeResults = mutableMapOf<String, String>()
    private var probeMeters: Map<String, TextView> = emptyMap()

    /**
     * 녹음 장치 확인 화면(정차 중, 녹음 장치 알약 → "인식된 장치 전부 보기"). 2026-11-08 사용자: "지금 뭐가 인식되는지 모르겠다".
     * 입력 장치 전부 · 장치마다 소리 확인(몇 초 재기만, 저장 안 함) · 음악에서 따기 상태와 담는 방법.
     */
    private fun showDeviceCheck() {
        val all = DeviceCheck.inputs(audio)
        val chosen = inputDevice
        val meters = mutableMapOf<String, TextView>()
        show({
            onDeviceCheck = true
            addView(headline("녹음 장치 확인"))
            addView(body("차가 알려 주는 입력 장치 ${all.size}개예요. '소리 확인'을 누르면 몇 초 동안 소리 크기만 재요(녹음은 저장하지 않아요)."))
            if (DeviceCheck.emulator) addView(column().apply {
                background = round(SURFACE, 24, POSITIVE)
                setPadding(dp(28), dp(20), dp(28), dp(20))
                addView(text("에뮬레이터예요", BODY_STRONG, TEXT))
                addView(text("'차량 마이크'(Built-In Mic)와 '뒷좌석 마이크'(Built-In Back Mic)는 지금은 둘 다 PC 마이크로 들어와요. " +
                             "실제 차에서는 각각 앞·뒤 실내 마이크예요. 소리가 안 들어오면 에뮬레이터 설정의 마이크(PC 오디오 입력 사용)를 켜 주세요.", BODY_S, TEXT2).withMargin(top = 6))
            }.withMargin(top = 16, fill = true))

            addView(label("입력 장치"))
            fun deviceRow(d: android.media.AudioDeviceInfo?) = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(SURFACE, 24)
                setPadding(dp(28), dp(20), dp(20), dp(20))
                val key = d?.let(InputDevices::key) ?: DEFAULT_KEY
                val usable = d == null || DeviceCheck.usable(d)
                val picked = (d == null && chosen == null) || (d != null && chosen != null && InputDevices.key(d) == InputDevices.key(chosen))
                addView(column().apply {
                    addView(text((if (d == null) DEFAULT_INPUT else if (usable) InputDevices.label(d) else DeviceCheck.typeName(d.type)) +
                                 (if (picked) "  ✓ 지금 고름" else ""), BODY_STRONG, if (usable) TEXT else TEXT3))
                    addView(text(if (d == null) "시스템이 고르는 마이크 — 실제로 어느 장치인지는 소리 확인에 나와요"
                                 else "${DeviceCheck.typeName(d.type)} · ${DeviceCheck.rawName(d)} · id ${d.id}" +
                                      (if (usable) "" else " · 녹음 장치 목록에는 안 올려요"), BODY_S, TEXT3).withMargin(top = 4))
                    val m = text(probeResults[key] ?: "", BODY_S_STRONG, TEXT2).withMargin(top = 4)
                    meters[key] = m
                    addView(m)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (usable) addView(button(if (probing == key) "재는 중…" else "소리 확인", size = Size.MEDIUM,
                                           enabled = probing == null && !recording && !counting) { probeDevice(d, key) })
            }
            listOf<android.media.AudioDeviceInfo?>(null).plus(all.sortedBy { if (DeviceCheck.usable(it)) 0 else 1 }).forEach {
                addView(deviceRow(it).withMargin(bottom = 12, fill = true))
            }

            addView(label("음악에서 따기"))
            val sdkOk = android.os.Build.VERSION.SDK_INT >= 29
            val playing = DeviceCheck.playing(audio)
            addView(column().apply {
                background = round(SURFACE, 24)
                setPadding(dp(28), dp(20), dp(28), dp(24))
                addView(text("재생 소리 직접 담기: " + (if (sdkOk) "가능(Android ${android.os.Build.VERSION.RELEASE})" else "불가 — Android 10 이상 필요"), BODY, TEXT))
                addView(text("녹화·전송 동의: " + (if (projection != null) "받아 둠(마지막 녹음 뒤 10분 동안 다시 안 물어요)" else "아직 없음(처음 직접 담을 때 한 번 물어요)"), BODY, TEXT).withMargin(top = 6))
                addView(text("지금 차 안에서 재생 중인 소리: " + (if (playing.isEmpty()) "없음" else playing.joinToString(" · ")), BODY, TEXT).withMargin(top = 6))
                addView(text(when {
                    playing.isEmpty() && DeviceCheck.emulator -> "PC 나 폰 스피커로 트는 음악은 에뮬레이터 안에서 재생되는 게 아니라서 직접 담을 수 없어요. 마이크(지금은 PC 마이크)로 담아요."
                    playing.isEmpty() -> "재생 중인 소리가 없으면 차량 마이크로 실내에 들리는 음악을 담아요."
                    else -> "자동이면 지금 재생 중인 소리를 직접 담아요(음악은 멈추지 않아요)."
                }, BODY_S, TEXT3).withMargin(top = 10))
                addView(text("담는 방법", BODY_S_STRONG, TEXT3).withMargin(top = 20, bottom = 8))
                val modes = listOf("auto", "direct", "mic")
                addView(segmented(listOf("자동", "재생 소리 직접", "마이크로"), modes.indexOf(prefs.getString(MUSIC_SOURCE, "auto")).coerceAtLeast(0)) { i ->
                    prefs.edit().putString(MUSIC_SOURCE, modes[i]).apply(); showDeviceCheck()
                })
                addView(text("지금 누르면: " + (if (musicNeedsCapture()) "재생 소리를 직접 담아요" else "마이크로 담아요"), BODY_S_STRONG, POSITIVE).withMargin(top = 12))
            }.withMargin(fill = true))
        }, ::showDeviceCheck, tabs = true) {
            zones(if (tab == Tab.SETTINGS) button("← 설정") { showSettings() } else button("← 녹음실") { showHome() },
                  listOf(), button("다시 확인") { probeResults.clear(); showDeviceCheck() })
        }
        probeMeters = meters
    }

    private var serverStatus: String? = null

    /**
     * 차작 서버 주소(노래를 만드는 PC). 기본은 빌드할 때 정한 주소(에뮬레이터 = 같은 PC 10.0.2.2).
     * 다른 PC 의 협업자·심사 기기는 서버 PC 가 열어 둔 주소(예: https://….trycloudflare.com)를 넣는다. 글자 입력이라 정차 중에만.
     */
    private fun serverCard() = column().apply {
        background = round(SURFACE, 24)
        setPadding(dp(28), dp(20), dp(28), dp(24))
        addView(text("지금 주소: ${api.base}" + (if (prefs.getString(SERVER_CUSTOM, null) != null) " (직접 정함)" else " (기본)"), BODY, TEXT))
        serverStatus?.let { addView(text(it, BODY_S_STRONG, if (it.startsWith("●")) ACTIVE else RECORDING).withMargin(top = 6)) }
        val input = EditText(this@MainActivity).apply {
            setText(api.base)
            hint = "https://….trycloudflare.com 또는 http://192.168.0.10:8790"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY.size.toFloat())
            typeface = faces.getOrPut(BODY.font) { resources.getFont(BODY.font) }
            setTextColor(TEXT); setHintTextColor(TEXT3)
            maxLines = 1; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = round(SURFACE, 24, TINT_PRESSED)
            setPadding(dp(28), dp(16), dp(28), dp(16))
            isEnabled = !driving
        }
        addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)).apply { topMargin = dp(16) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("연결 확인 후 저장", Btn.SECONDARY, Size.MEDIUM, enabled = !driving) { connectServer(input.text.toString()) }.withMargin(end = 16))
            addView(button("기본 주소로", size = Size.MEDIUM, enabled = !driving) {
                prefs.edit().remove(SERVER_CUSTOM).apply(); connectServer(BuildConfig.SERVER_URL, save = false)
            })
        }.withMargin(top = 16))
        if (driving) addView(text("주소 입력은 정차 중에만 할 수 있어요.", BODY_S, TEXT3).withMargin(top = 10))
    }

    /** /health 로 연결을 확인하고, 되면 그 주소로 바꾼다(재생 서비스도 같은 주소를 쓰게 prefs 의 server 도). */
    private fun connectServer(raw: String, save: Boolean = true) {
        val url = raw.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
        serverStatus = "확인하는 중… $url"
        showSettings()
        io.execute {
            val ok = try {
                val c = java.net.URL("$url/health").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.responseCode == 200 && c.inputStream.bufferedReader().readText().contains("\"ok\"")
            } catch (e: Exception) { Log.w(tag, "서버 확인 실패: $url", e); false }
            ui.post {
                if (ok) {
                    api = ChajakApi(url)
                    // 빌드 기본 주소와 같으면 "직접 정함"으로 남기지 않는다(나중에 다른 기본 주소로 빌드해도 따라가게)
                    prefs.edit().putString("server", url).apply { if (save && url != BuildConfig.SERVER_URL) putString(SERVER_CUSTOM, url) else remove(SERVER_CUSTOM) }.apply()
                    serverStatus = "●  연결됐어요 — 이제 이 서버로 노래를 만들어요"
                    loadLibrary()
                } else serverStatus = "연결하지 못했어요: $url — 주소와 서버 PC 가 켜져 있는지 확인해 주세요"
                if (onSettings) showSettings()
            }
        }
    }

    /** 장치 하나의 소리 크기를 몇 초 잰다(저장 안 함). 화면을 떠나면 멈춘다. */
    private fun probeDevice(d: android.media.AudioDeviceInfo?, key: String) {
        if (probing != null || recording || counting) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        probing = key
        probeResults[key] = "듣는 중… 말하거나 흥얼거려 보세요"
        showDeviceCheck()
        rec.execute {
            val result = runCatching {
                DeviceCheck.probe(d, PROBE_SEC, stop = { !onDeviceCheck || !active }) { db ->
                    val bars = ((db + 60) / 5).toInt().coerceIn(0, 10)
                    ui.post { probeMeters[key]?.text = "지금 ${db.toInt()} dB  " + "▮".repeat(bars) + "▯".repeat(10 - bars) }
                }
            }
            ui.post {
                probing = null
                probeResults[key] = result.fold({ r ->
                    (if (r.maxDb > -50) "✓ 소리가 들어와요(가장 큰 소리 ${r.maxDb.toInt()} dB)" else "✕ 거의 안 들려요(가장 큰 소리 ${r.maxDb.toInt()} dB)") +
                        (r.routed?.let { "\n실제로 녹음된 장치: $it" } ?: "")
                }, { "✕ 열지 못했어요: ${it.message}" })
                Log.i(tag, "소리 확인 $key → ${probeResults[key]}")
                if (onDeviceCheck) showDeviceCheck()
            }
        }
    }

    /** 녹음 중 "녹음 취소": 지금까지 녹음은 버린다(서버로 안 보냄). */
    private fun cancelRecording() {
        if (!recording) return
        cancelled = true
        recorder.stop()
    }

    /** music: 음악 녹음(포커스 없이, 재생 소리 직접 또는 마이크). 끝나면 허밍과 같은 흐름 + 보관함에 원본. */
    private fun record(music: Boolean = false, viaMic: Boolean = false) {
        val capture = if (music && !viaMic && android.os.Build.VERSION.SDK_INT >= 29) projection else null
        musicCapturing = music  // showRecording 의 안내 문구가 음악용인지 본다
        showRecording(music, direct = capture != null)
        // 소리 안내(3·2·1 · 띠링)에 더해 눈으로도 녹음 중임을 바로 알게 — 화면 맨 위에 겹친다(터치는 통과)
        // 테두리 글로우는 쓰지 않는다(소리와 상관없이 늘 보였다) — 가운데 목소리 원이 소리에 반응한다
        recording = true
        cancelled = false
        if (music) Log.i(tag, "음악 녹음 시작: ${if (capture != null) "재생 소리 직접" else "차량 마이크"}, 포커스 요청 없음")
        val file = File(cacheDir, "hum.wav")
        val take = VoiceRange()
        pitchColor = RECORDING
        liveRange?.range = take
        val total = MAX_RECORD_SEC
        val started = System.currentTimeMillis()
        var lastVoice = 0L
        var lastRipple = 0L
        var peak = -120.0  // 녹음 전체에서 가장 큰 소리 — 잡음 바닥(-60~-75dB)조차 없으면 마이크가 없는 것
        rec.execute {
            val sec = try {
                recorder.record(file, total, source = if (android.os.Build.VERSION.SDK_INT >= 29) capture?.let(::captureSource) else null,
                                device = if (capture == null) inputDevice else null) { db, midi ->
                    ui.post {
                        val nowMs = System.currentTimeMillis()
                        val voiced = midi != null && db > VOICE_DB
                        if (db > peak) peak = db
                        // 음역: 허밍만 모은다(음악 녹음은 노래 소리라 내 목소리가 아니다). 링·막대 색도 음높이를 따른다
                        if (voiced && !music) {
                            take.add(midi!!)
                            pitchColor = VoiceRange.color(midi)
                            (halo?.background as? GradientDrawable)?.setColor(Color.argb(0x55, Color.red(pitchColor), Color.green(pitchColor), Color.blue(pitchColor)))
                        }
                        liveRange?.let { it.current = if (voiced) midi else null; it.invalidate() }
                        if (voiced) {
                            lastVoice = nowMs
                            if (nowMs - lastRipple > RIPPLE_EVERY_MS) { lastRipple = nowMs; ripple() }
                        }
                        setHearing(when {
                            peak < SILENT_DB && nowMs - started > SILENT_AFTER_MS -> Hearing.NO_SIGNAL
                            nowMs - lastVoice < VOICE_HOLD_MS -> Hearing.VOICE
                            lastVoice == 0L -> Hearing.WAITING
                            nowMs - lastVoice > QUIET_MS -> Hearing.QUIET
                            else -> hearingState
                        }, nowMs - started)
                        // -60dB(조용) ~ -10dB(큼) 을 0~100% 로. 커질 땐 바로, 작아질 땐 천천히.
                        recOrb?.level(db)
                        halo?.let {
                            val target = HALO_MIN + (1 - HALO_MIN) * ((db + 60) / 50).coerceIn(0.0, 1.0).toFloat()
                            val k = it.scaleX + (target - it.scaleX) * (if (target > it.scaleX) 0.7f else 0.25f)
                            it.scaleX = k; it.scaleY = k
                            it.alpha = if (hearingState == Hearing.VOICE) 1f else 0.4f
                        }
                        val left = total - (nowMs - started) / 1000
                        val el = (nowMs - started) / 1000
                        remain.text = if (left <= 10) "${left}초 뒤 멈춰요" else "%02d:%02d".format(el / 60, el % 60)
                        // 음높이가 잡힌 순간만 색으로(허밍은 음 높이 색, 음악은 원과 같은 파랑 — 빨강은 녹음 버튼 색과 헷갈렸다)
                        wave?.push(db, if (voiced) (if (music) POSITIVE else pitchColor) else TEXT3)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "녹음 실패", e)
                -1.0
            }
            ui.post {
                recording = false
                musicCapturing = false
                // 녹음이 끝난 순간 글로우를 걷는다 — 빛이 남아 있으면 아직 녹음 중인 줄 안다
                glow?.let { root.removeView(it) }
                glow = null
                releaseFocus()
                if (music) holdCapture()
                when {
                    // 취소: 녹음은 버리고 보내지 않는다. 곡 작업 중이었으면 그 곡으로 돌아간다
                    interrupted -> { interrupted = false; cancelled = false; if (active) showError(FOCUS_LOST) }
                    cancelled -> if (active) {
                        cancelled = false
                        notice = CANCELLED
                        if (song != null) { stackMode = null; showSong(false) } else showHome()
                    }
                    sec < 0 -> showError(if (music) "음악을 녹음하지 못했어요." else "마이크를 열지 못했어요.")
                    // 완전한 무음은 서버로 보내지 않는다 — 보내 봐야 "목소리가 들리지 않았어요(더 크게)"라는 엉뚱한 안내만 돌아온다
                    sec >= 1 && peak < SILENT_DB -> {
                        Log.w(tag, "완전한 무음: 가장 큰 소리 ${"%.1f".format(peak)}dB, 장치 ${inputDevice?.productName ?: "기본"}")
                        if (capture != null) showError(NO_SIGNAL_PLAYBACK) else showError(noSignalMic(), deviceHelp = true)
                    }
                    sec < 3 -> showError("너무 짧아요(${"%.1f".format(sec)}초). 5초 이상 ${if (music) "담아" else "흥얼거려"} 주세요.")
                    else -> {
                        if (music) keepMusicRecording(file) else rememberRange(take)
                        upload(file, music)
                    }
                }
            }
        }
    }

    /** 마지막으로 올린 것이 음악 녹음인지(오류 안내·다시 녹음 버튼을 음악용으로). */
    private var lastUploadMusic = false

    private fun upload(file: File, music: Boolean = false) {
        lastUploadMusic = music
        val s = song
        val mode = stackMode
        val inst = instrumentNames[instrument]
        val name = INSTRUMENTS[instrument].first
        val n = (s?.getJSONArray("tracks")?.length() ?: 0) + 1
        startPending(Pending(
            "$n  $name · " + when (mode) { null -> if (music) "음악에서 뽑은 멜로디" else "내 허밍"; "overlay" -> "겹쳐 부르기"; else -> "이어 부르기" },
            listOfNotNull(if (music) "노래 속 멜로디 찾는 중…" else "허밍 정리하는 중…", "음표 뽑는 중…", if (s != null) "곡 박자·조성에 맞추는 중…" else null,
                          "${name}${ro(name)} 연주하는 중…", if (s == null && accompaniment) "반주 붙이는 중…" else null),
        ))
        call {
            val source = if (music) "music" else "hum"
            if (s == null || mode == null) api.createSong(file, inst, melody, accompaniment, source)
            else api.addTrack(s.getString("id"), file, inst, melody, mode, source)
        }
    }

    private fun addVariation(v: Variation) {
        val s = song ?: return
        stopPlayback()
        val tracks = s.getJSONArray("tracks")
        startPending(Pending("${tracks.length() + 1}  ${instrumentLabel(tracks.getJSONObject(0).getString("instrument"))} · 변주 · ${v.name}",
                             if (v.id == "rewrite") listOf("허밍 동기 읽는 중…", "AI가 8마디 멜로디 쓰는 중…", "박자·음계 검사하는 중…", "연주하는 중…")
                             else listOf("음표 다듬는 중…", "연주하는 중…")))
        call { api.addVariation(s.getString("id"), v.id) }
    }

    private fun deleteLast() {
        val s = song ?: return
        stopPlayback()
        val n = s.getJSONArray("tracks").length()
        call(autoplay = false) { api.deleteTrack(s.getString("id"), n) }
    }

    private fun endPending() {
        pending = null
        pendingStep = null
        pendingToken++
    }

    /** 지우기 확인 대기(두 번 눌러 지운다): "song:<id>" · "final:<id>". 다른 화면으로 가면 풀린다. */
    private var deleteArmed: String? = null

    private fun deleteSong(id: String) {
        stopPlayback()
        io.execute {
            try {
                api.deleteSong(id)
                ui.post {
                    if (song?.optString("id") == id) song = null
                    library = library.filter { it.optString("id") != id }
                    notice = "멜로디와 그 노래를 지웠어요."
                    loadLibrary(); showMelodyList()
                }
            } catch (e: ApiException) {
                ui.post { showError(e.message ?: "지우지 못했어요.") }
            } catch (e: Exception) {
                Log.e(tag, "지우기 실패", e)
                ui.post { showError("서버와 연결이 끊겨 지우지 못했어요(${api.base}). 잠시 후 다시 시도해 주세요.", settingsHelp = true) }
            }
        }
    }

    private fun deleteFinal(songId: String, finalId: String) {
        stopPlayback()
        io.execute {
            try {
                val next = api.deleteFinal(songId, finalId)
                ui.post {
                    song = next; playerVersion = null; lyricsDraft = null; loadLibrary()
                    if (next.optJSONObject("final") != null) showPlayer(false) else { notice = "노래를 지웠어요. 멜로디는 남아 있어요."; showSong(false) }
                }
            } catch (e: ApiException) {
                ui.post { showError(e.message ?: "지우지 못했어요.") }
            } catch (e: Exception) {
                Log.e(tag, "지우기 실패", e)
                ui.post { showError("서버와 연결이 끊겨 지우지 못했어요(${api.base}). 잠시 후 다시 시도해 주세요.", settingsHelp = true) }
            }
        }
    }

    /** 서버 호출 → 곡 갱신 → 곡 화면(새 결과는 바로 1회 재생). */
    private fun call(autoplay: Boolean = true, request: () -> JSONObject) {
        io.execute {
            try {
                val next = request()
                ui.post { endPending(); song = next; stackMode = null; showSong(autoplay); loadLibrary() }
            } catch (e: ApiException) {
                ui.post { endPending(); showError(e.message ?: "오류가 났어요.") }
            } catch (e: Exception) {
                Log.e(tag, "요청 실패", e)
                ui.post { endPending(); showError("서버와 연결이 끊겼어요(${api.base}). 잠시 후 다시 시도하거나, 설정 탭의 '서버 연결'에서 주소를 확인해 주세요.", settingsHelp = true) }
            }
        }
    }

    // ------------------------------------------------------------------ 재생
    //
    // 완성된 노래의 재생 주인은 ChajakMediaService(Media3 MediaLibraryService)다. 화면은 MediaController 로
    // 명령만 보낸다. 스티어링휠 키·차량 미디어 센터·음성 검색이 같은 세션을 써야 하므로(Pleos Music/Audio 가이드).
    // 짧은 소리(녹음·트랙·전체 듣기)는 세션 밖 미리 듣기 플레이어로 튼다 — 끝나면 듣던 차 음악이 이어지게(TransientFocus).

    /** 미디어 세션에 붙는다. 이게 안 붙으면 스티어링휠 키가 차의 다른 앱으로 간다. */
    private fun bindMediaSession() {
        val token = SessionToken(this, ComponentName(this, ChajakMediaService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            val c = try { f.get() } catch (e: Exception) { Log.w(tag, "미디어 세션 연결 실패: ${e.message}"); null }
            ui.post {
                if (c != null) {
                    controller = c
                    c.addListener(playerListener { controller })
                }
            }
        }, Executor { it.run() })
    }

    /** 세션·미리 듣기에 하나씩 붙인다. 지금 소리 내는 쪽(activePlayer)의 상태만 화면에 반영한다. */
    private fun playerListener(source: () -> Player?) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (source() !== activePlayer) return
            prepared = state == Player.STATE_READY
            // 큐 끝까지 들으면 "■ 멈춤" 이 남지 않게 정리한다
            if (state == Player.STATE_ENDED) playing = null
            ui.post { if (active) refresh() }
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            if (source() !== activePlayer) return
            // 스티어링휠 "다음" 으로 트랙·버전이 바뀌면 화면도 따라간다
            item?.mediaId?.let { raw ->
                val id = screenKey(raw)
                if (id.startsWith("final:")) playerVersion = id.removePrefix("final:")
                playing = id
            }
            ui.post { if (active) refresh() }
        }
        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            // 통화·안내가 끼어들면 오디오 포커스를 잃는다(가이드: 세션이 active 로 남으면 안 됨)
            if (reason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                ui.post {
                    android.widget.Toast.makeText(this@MainActivity,
                        "지금은 재생할 수 없어요. 통화나 안내가 끝난 뒤 다시 눌러 주세요.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 세션 밖에서 잠깐 포커스로 트는 짧은 소리인가(완성된 노래만 세션). */
    private fun isPreview(key: String) = key.startsWith("recording:") || key.startsWith("track") || key.startsWith("hum") || key == "mix"

    private fun previewPlayer(): ExoPlayer = preview ?: ExoPlayer.Builder(this)
        .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ false)
        .build().also { p ->
            val f = TransientFocus(audio, p)
            p.addListener(f)
            p.addListener(playerListener { preview })
            preview = p
            previewFocus = f
        }

    /** 미디어 센터·음성 검색으로 튼 항목(song:<곡>:track:<n> / song:<곡>:final:<id>)을 화면 key(track<n> / final:<id>)로. */
    private fun screenKey(mediaId: String): String {
        val p = mediaId.split(":")
        if (p.size != 4 || p[0] != "song" || p[1] != song?.optString("id")) return mediaId
        return when (p[2]) { "track" -> "track${p[3]}"; "final" -> "final:${p[3]}"; else -> mediaId }
    }

    private fun toggle(key: String, path: String) {
        val usePreview = isPreview(key) || playOnce
        playOnce = false
        val c: Player? = if (usePreview) previewPlayer() else controller
        if (c == null) {
            android.widget.Toast.makeText(this, "미디어 세션에 아직 연결되지 않았어요. 잠시 뒤 다시 눌러 주세요.",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (playing == key) {
            stopPlayback()
            return refresh()
        }
        stopPlayback()
        playing = key
        previewActive = usePreview
        val items = buildQueue(key, path)
        val index = items.indexOfFirst { it.mediaId == key }.let { if (it < 0) 0 else it }
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()
        refresh()
    }

    /**
     * 스티어링휠의 다음·이전이 자연스럽게 이어지도록 같은 화면의 트랙(또는 버전)을 한 줄로 잇는다.
     * mediaId 는 지금 고른 항목의 key 와 같다(track3, final:xxx, mix).
     */
    private fun buildQueue(key: String, path: String): List<MediaItem> {
        val s = song
        val tracks = s?.optJSONArray("tracks")?.length() ?: 0
        val out = mutableListOf<MediaItem>()
        fun add(id: String, title: String, url: String, cover: String) {
            val meta = MediaMetadata.Builder()
                .setTitle(title).setArtist("차작")
                .setAlbumTitle(s?.let { songName(it) } ?: "멜로디")
                .setIsPlayable(true)
            if (cover.isNotEmpty()) meta.setArtworkUri(Uri.parse(playUrl(cover, tracks)))
            val b = MediaItem.Builder().setMediaId(id).setMediaMetadata(meta.build())
            if (url.isNotEmpty()) b.setUri(playUri(url, tracks))
            out += b.build()
        }
        when {
            key.startsWith("final:") -> {
                val finals = s?.optJSONArray("finals")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                    ?: listOfNotNull(s?.optJSONObject("final"))
                finals.forEach { f ->
                    add("final:" + f.optString("id"), f.optString("title").ifEmpty { "완성된 노래" },
                        f.optString("audio_url"), f.optString("cover_url"))
                }
            }
            key.startsWith("track") -> {
                val arr = s?.optJSONArray("tracks")
                if (arr != null) for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    add("track${i + 1}", t.optString("title").ifEmpty { "트랙 ${i + 1}" }, t.optString("audio_url"), "")
                }
            }
            else -> add(key, when {
                key == "mix" -> "내 곡 · 전체 듣기"
                key.startsWith("recording:") -> "음악 녹음 · ${key.removePrefix("recording:")}"
                key.startsWith("hum") -> "허밍 원본 · 트랙 ${key.removePrefix("hum")}"
                else -> "내 멜로디"
            }, path, "")
        }
        return out
    }

    /** 녹음 파일(local) 또는 서버 주소. 트랙이 쌓이면 같은 주소가 캐시에 남으므로 버전을 붙인다. */
    private fun playUri(path: String, tracks: Int): Uri =
        if (path.startsWith(filesDir.absolutePath)) Uri.fromFile(File(path)) else Uri.parse(playUrl(path, tracks))

    private fun playUrl(path: String, tracks: Int): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return ChajakApi.withToken(path)
        return api.url(if ("?" in path) path else "$path?v=$tracks")
    }

    private fun stopPreview() {
        preview?.stop()
        preview?.clearMediaItems()
        if (previewActive) { playing = null; previewActive = false; prepared = false }
    }

    private fun stopPlayback() {
        controller?.stop()
        controller?.clearMediaItems()
        preview?.stop()
        preview?.clearMediaItems()
        playing = null
        previewActive = false
        prepared = false
    }

    // ------------------------------------------------------------------ 보기 도우미

    // 수치는 Pleos Connect Design System(design assets 215) Light 모드 · connect-s 값.
    // 에뮬레이터가 160dpi 라 Figma px = dp = sp.

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * 슬라이더 손잡이를 작은 원(지름 28, 흰 테두리)으로. 기본 테마 손잡이는 지름이 70px 넘게 커서 막대를 가렸다(2026-11-09 사용자).
     * 누를 때 퍼지는 큰 물결도 없앤다. 막대가 손잡이 양옆에서 끊기지 않게 splitTrack 끔.
     */
    private fun SeekBar.smallThumb(color: Int) {
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(4), Color.WHITE)
            setSize(dp(THUMB), dp(THUMB))
        }
        thumbTintList = null
        splitTrack = false
        background = null
        thumbOffset = dp(THUMB) / 2
    }

    private fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun pressable(normal: GradientDrawable, pressed: GradientDrawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

    private val faces = mutableMapOf<Int, Typeface>()

    private fun text(s: String, type: Typo, color: Int) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, type.size.toFloat())
        lineHeight = dp(type.line)
        includeFontPadding = false
        setTextColor(color)
        typeface = faces.getOrPut(type.font) { resources.getFont(type.font) }
    }

    /** 문장 맨 앞 ● 점만 색을 입힌다(색만으로 뜻을 전하지 않고, 글자는 대비 높은 색으로 둔다). */
    private fun dot(s: String, color: Int): CharSequence = SpannableString(s).apply {
        if (s.startsWith("●")) setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun headline(s: String) = text(s, HEADLINE, TEXT).withMargin(bottom = 8)
    private fun title(s: String) = text(s, TITLE, TEXT).withMargin(bottom = 8)
    private fun body(s: String) = text(s, BODY, TEXT2).withMargin(bottom = 4)
    private fun caption(s: String, color: Int) = text(s, BODY_S, color).withMargin(top = 8)
    private fun label(s: String) = text(s, BODY_S_STRONG, TEXT3).withMargin(top = 24, bottom = 12)
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /** Pleos Basic button. PRIMARY = 옅은 회색 채움 + 검은 글자, SECONDARY = 진회색 채움 + 흰 글자(화면의 주 동작에 쓴다). */
    private fun button(s: String, kind: Btn = Btn.PRIMARY, size: Size = Size.LARGE, enabled: Boolean = true, onClick: () -> Unit) =
        text(s, size.label, if (!enabled) TEXT_DISABLED else if (kind == Btn.SECONDARY) Color.WHITE else TEXT).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            height = dp(size.height)
            minWidth = dp(size.minWidth)
            setPadding(dp(size.padH), dp(size.padV), dp(size.padH), dp(size.padV))
            background = when {
                !enabled && kind == Btn.SECONDARY -> round(TINT, size.radius, TINT_PRESSED)
                !enabled -> round(WHITE_20, size.radius, TINT_PRESSED)
                kind == Btn.SECONDARY -> pressable(round(GRAY_600, size.radius), round(GRAY_700, size.radius))
                else -> pressable(round(TINT, size.radius), round(TINT_PRESSED, size.radius))
            }
            isEnabled = enabled
            setOnClickListener { onClick() }
        }

    /** Pleos Icon button(Basic · Medium 80×80). 아이콘 자산 대신 글리프를 쓴다. desc = 화면 낭독 이름(글리프는 읽어 줄 말이 없다). */
    private fun iconButton(glyph: String, desc: String, onClick: () -> Unit) = text(glyph, TITLE, TEXT).apply {
        gravity = Gravity.CENTER
        contentDescription = desc
        width = dp(80)
        height = dp(80)
        background = pressable(round(TINT, 20), round(TINT_PRESSED, 20))
        setOnClickListener { onClick() }
    }

    /** Pleos Segmented control(solid · Large): 회색 틀 안에서 고른 칸만 흰색. 2~3칸 880, 4~6칸 1176 폭(아래 줄처럼 좁은 곳은 width). */
    private fun segmented(labels: List<String>, selected: Int, width: Int = if (labels.size <= 3) 880 else 1176, onPick: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = round(TINT, 32)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(width), dp(100))
        labels.forEachIndexed { i, s ->
            val on = i == selected
            addView(text(s, Size.LARGE.label, if (on) TEXT else TEXT2).apply {
                gravity = Gravity.CENTER
                background = if (on) round(SURFACE, 24) else pressable(round(Color.TRANSPARENT, 24), round(TINT_PRESSED, 24))
                setOnClickListener { onPick(i) }
            }, LinearLayout.LayoutParams(0, dp(84), 1f))
        }
    }

    private fun oval(color: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (stroke != null) setStroke(dp(2), stroke)
    }

    /** 녹음 레벨에 따라 커지는 원(녹음 중 버튼 뒤). 화면이 바뀌면 null. */
    private var halo: View? = null
    /** 녹음 중 화면 가장자리 글로우. 화면이 바뀌면(root 를 비우면) null. */
    private var glow: EdgeGlow? = null

    /**
     * 녹음 전용 버튼(프로젝트 컴포넌트): 지름 200 원형, 가운데 아이콘이 상태를 보여 준다.
     * 준비 = 빨간 원 + 마이크 · 카운트다운 = 진회색 원 + 숫자 · 녹음 중 = 빨간 원 + 멈춤, 뒤에서 소리 크기만큼 번지는 원
     * · 변환 중 = 회색 원 + 진행 표시 · 주행 중 = 비활성 원 + 사선 마이크.
     */
    /** 기존 빨간 버튼 오른쪽의 작은 음악 녹음 버튼. 터치 영역은 80dp. */
    /** 녹음실 왼쪽: 대화 녹음(30분까지) → 알림 화면. 처음 알림을 읽어야 해서 정차 중에만. */
    private fun talkRecordButton(): View = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        val enabled = !driving && !TalkRecordService.recording
        val core = FrameLayout(this@MainActivity).apply {
            background = if (enabled) pressable(oval(GRAY_700), oval(TEXT)) else oval(TINT)
            contentDescription = if (enabled) "대화 녹음" else "대화 녹음 사용 불가"
            isEnabled = enabled
            if (enabled) setOnClickListener { talkPickFor = null; showTalkIntro() }
            addView(text("🗣", TITLE, if (enabled) Color.WHITE else TEXT_DISABLED),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        addView(core, LinearLayout.LayoutParams(dp(80), dp(80)))
        addView(text(if (driving) "대화는 정차 후에" else "대화 녹음", BODY_S, if (enabled) TEXT else TEXT3).withMargin(top = 8))
    }

    private fun musicRecordButton(on: Boolean, stack: String? = stackMode): View = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        // 처음(동의 전)에는 설명 화면과 시스템 확인 창이 떠서 주행 중에는 막는다. 동의가 살아 있으면(10분) 주행 중에도 한 번에
        val firstTime = driving && projection == null && android.os.Build.VERSION.SDK_INT >= 29 && musicNeedsCapture()
        val enabled = on && !firstTime
        val core = FrameLayout(this@MainActivity).apply {
            background = if (enabled) pressable(oval(POSITIVE), oval(GRAY_700)) else oval(TINT)
            contentDescription = if (enabled) "음악을 멈추지 않고 녹음해 멜로디로" else "음악 녹음 사용 불가"
            isEnabled = enabled
            if (enabled) setOnClickListener { startMusicRecording(stack) }
            addView(text("♪ ●", BODY_STRONG, if (enabled) Color.WHITE else TEXT_DISABLED),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        addView(core, LinearLayout.LayoutParams(dp(80), dp(80)))
        addView(text(if (firstTime) "처음은 정차 후에" else "음악 녹음", BODY_S, if (enabled) TEXT else TEXT3).withMargin(top = 8))
    }

    private fun recordButton(state: Rec, caption: String, count: Int = 0, onClick: (() -> Unit)?): View {
        val frame = FrameLayout(this)
        val ring = View(this).apply {
            background = oval(RECORDING_HALO)
            visibility = if (state == Rec.RECORDING) View.VISIBLE else View.INVISIBLE
            scaleX = HALO_MIN
            scaleY = HALO_MIN
        }
        frame.addView(ring, FrameLayout.LayoutParams(dp(RING), dp(RING), Gravity.CENTER))
        if (state == Rec.RECORDING) halo = ring
        val core = FrameLayout(this).apply {
            background = when (state) {
                Rec.READY, Rec.RECORDING -> pressable(oval(RECORDING), oval(RECORDING_PRESSED))
                Rec.COUNTDOWN -> pressable(oval(GRAY_600), oval(GRAY_700))
                Rec.BUSY -> oval(TINT)
                Rec.DISABLED -> oval(WHITE_20, TINT_PRESSED)
            }
            contentDescription = caption
            if (onClick != null) setOnClickListener { onClick() } else isClickable = false
        }
        val (icon, size) = when (state) {
            Rec.COUNTDOWN -> text(if (count == 0) "♪" else "$count", COUNT, Color.WHITE) to ViewGroup.LayoutParams.WRAP_CONTENT
            Rec.BUSY -> ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(TEXT) } to dp(RECORD * 2 / 5)
            else -> ImageView(this).apply {
                setImageResource(when (state) { Rec.RECORDING -> R.drawable.ic_stop; Rec.DISABLED -> R.drawable.ic_mic_off; else -> R.drawable.ic_mic })
                imageTintList = ColorStateList.valueOf(if (state == Rec.DISABLED) TEXT_DISABLED else Color.WHITE)
            } to dp(RECORD * 13 / 25)
        }
        core.addView(icon, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        frame.addView(core, FrameLayout.LayoutParams(dp(RECORD), dp(RECORD), Gravity.CENTER))
        return column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(frame, LinearLayout.LayoutParams(dp(RING), dp(RING)))
            addView(text(caption, BODY_STRONG, if (state == Rec.DISABLED) TEXT3 else TEXT).withMargin(top = 4))
        }
    }

    /** 아래 고정 줄: 왼쪽 · 가운데(녹음 버튼) · 오른쪽. 가운데는 화면 폭의 정확히 가운데. */
    /** 아래 줄 위에 녹음 관련 문구를 한 줄 얹는다(녹음 버튼 바로 위, 가운데). message 가 없으면 아래 줄만. */
    private fun LinearLayout.aboveButton(message: CharSequence?, color: Int = TEXT3, row: LinearLayout.() -> Unit) {
        if (message.isNullOrEmpty()) return row()
        orientation = LinearLayout.VERTICAL
        addView(text(message.toString(), BODY, color).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
        addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; row() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun LinearLayout.zones(left: View?, center: List<View>, right: View? = null, gap: Int = 32) {
        fun side(v: View?, g: Int) = FrameLayout(this@MainActivity).apply {
            if (v != null) addView(v, FrameLayout.LayoutParams(v.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                                                               v.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT, g or Gravity.CENTER_VERTICAL))
        }
        addView(side(left, Gravity.START), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        center.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(v.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(gap) })
        }
        addView(side(right, Gravity.END), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** 녹음실 양옆 버튼은 같은 폭 칸에(가운데 허밍 버튼이 한가운데에 오게). */
    private fun View.fixedWidth(): View = FrameLayout(this@MainActivity).also { f ->
        f.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(dp(SIDE_REC_W), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun row(vararg views: View?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views.filterNotNull()) addView(v.withMargin(end = 16))
    }

    /** 버튼을 cols 칸씩 줄 맞춰 놓는다(칸 폭은 같게). */
    private fun grid(views: List<View>, cols: Int) = column().apply {
        views.chunked(cols).forEachIndexed { r, line ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                for (c in 0 until cols) {
                    val v = line.getOrNull(c) ?: View(this@MainActivity)
                    addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (c < cols - 1) marginEnd = dp(16) })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (r > 0) topMargin = dp(16) })
        }
    }

    private fun <T : View> T.withMargin(top: Int = 0, bottom: Int = 0, end: Int = 0, fill: Boolean = false): T {
        val w = if (fill) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom); marginEnd = dp(end)
        }
        return this
    }

    private fun instrumentLabel(name: String) =
        INSTRUMENTS.firstOrNull { name.startsWith("GM ${it.second} ") }?.first ?: name.substringAfter(' ').substringAfter(' ')

    /** 조사 '로/으로': 받침이 있으면(ㄹ 받침 빼고) '으로'. 바이올린으로 · 첼로로. */
    private fun ro(word: String): String {
        val f = (word.last() - '가') % 28
        return if (word.last() in '가'..'힣' && f != 0 && f != 8) "으로" else "로"
    }

    private fun keyName(k: String) = if (k.endsWith("m")) "${k.dropLast(1)}단조" else "${k}장조"

    /** 곡·목록 항목의 조 이름. 성격 분석이 조표 안에서 장조/나란한조 단조를 골랐으면 그것(성격의 밝음·어두움과 맞게). */
    private fun keyOf(item: JSONObject): String = item.optJSONObject("mood")?.let { m ->
        m.optJSONObject("features")?.optString("key")?.takeIf { it.isNotEmpty() } ?: m.optString("key").takeIf { it.isNotEmpty() }
    } ?: keyName(item.optString("key"))

    data class Variation(val id: String, val name: String, val description: String)

    /** 만들고 있는 트랙: 카드 제목과 차례로 보여 줄 단계 문구. */
    data class Pending(val title: String, val steps: List<String>)

    /** Pleos 텍스트 스타일: 크기 · 줄 높이(sp/px) · Asta Sans 굵기. */
    data class Typo(val size: Int, val line: Int, val font: Int)

    enum class Btn { PRIMARY, SECONDARY }

    enum class Rec { READY, COUNTDOWN, RECORDING, BUSY, DISABLED }

    /** 녹음 중 소리 상태: 아직 안 들림 · 흥얼거림이 들림 · 들리다가 조용해짐. */
    enum class Hearing(val message: String, val music: String) {
        WAITING("흥얼거려 보세요. 잘 들리면 버튼 둘레가 반응해요.", "음악을 틀어 주세요. 소리가 들어오면 버튼 둘레가 반응해요."),
        /** 잡음조차 없는 완전한 무음 — 흥얼거림이 작은 게 아니라 마이크가 없거나 다른 장치가 골라졌다(2026-11-09: PC 에 마이크가 없을 때 "더 크게"라고 안내했다). */
        NO_SIGNAL("마이크에서 소리가 전혀 들어오지 않아요. 녹음 장치 연결을 확인해 주세요.", "소리가 전혀 들어오지 않아요. 음악이 재생 중인지 확인해 주세요."),
        VOICE("●  잘 들려요", "●  음악이 들어오고 있어요"),
        QUIET("조용해졌어요. 다 불렀으면 가운데 버튼을 누르세요.", "음악이 멈췄어요. 원하는 부분을 다 담았으면 가운데 버튼을 누르세요."),
    }

    /** Pleos Basic button 크기(height · min_width · padding · radius · 글자). */
    enum class Size(val height: Int, val minWidth: Int, val padH: Int, val padV: Int, val radius: Int, val label: Typo) {
        LARGE(100, 140, 40, 16, 32, Typo(32, 40, R.font.asta_sans_bold)),
        MEDIUM(80, 120, 32, 16, 24, Typo(30, 38, R.font.asta_sans_bold)),
    }

    companion object {
        // Pleos Color · Light
        val BG = Color.parseColor("#F7F8FA")             // basic/50
        val SURFACE = Color.WHITE                         // basic/00
        val TEXT = Color.parseColor("#131417")            // basic/900
        val TEXT2 = Color.parseColor("#D6131417")         // 84%
        val TEXT3 = Color.parseColor("#A3131417")         // 64% (Icon/gray-tertiary)
        val TEXT_DISABLED = Color.parseColor("#52131417") // 32% (Icon/gray-quaternary)
        val TINT = Color.parseColor("#0D131417")          // alpha/dark/50 — 버튼·세그먼트 바탕
        val TINT_PRESSED = Color.parseColor("#1A131417")  // alpha/dark/100 — 눌림, 비활성 테두리
        val WHITE_20 = Color.parseColor("#33FFFFFF")      // alpha/light/200 — 비활성 바탕
        val GRAY_600 = Color.parseColor("#44464E")        // basic/600 — Secondary 버튼
        val GRAY_700 = Color.parseColor("#313236")        // basic/700 — Secondary 눌림
        val ACTIVE = Color.parseColor("#02C265")          // informative/active
        val POSITIVE = Color.parseColor("#0064FF")        // informative/positive
        val RECORDING = Color.parseColor("#F62E24")       // regulation/red
        val RECORDING_PRESSED = Color.parseColor("#DF2B23") // regulation/red 위에 alpha/dark/100 을 겹친 값
        val RECORDING_HALO = Color.parseColor("#33F62E24")  // regulation/red 20%

        /** 녹음 버튼 지름과 소리 크기 원의 최대 지름(Pleos Large 버튼 높이 100의 1.6배 — 프로젝트 선택.
         *  처음 200 은 아래 줄에서 너무 크다는 사용자 의견으로 2026-11-05 줄임). */
        const val RECORD = 160
        const val RING = 200
        const val HALO_MIN = RECORD.toFloat() / RING

        /** 녹음 중 "들린다" 판단: 음높이가 잡히고 -50dB 보다 크면 흥얼거림. 0.4초 끊겨도 들리는 걸로, 1.5초 조용하면 안내. */
        const val VOICE_DB = -50.0
        const val VOICE_HOLD_MS = 400L
        const val QUIET_MS = 1500L
        /** 앱에서 직접 정한 서버 주소(prefs). 없으면 빌드 기본값. */
        const val SERVER_CUSTOM = "server_custom"
        /** 완전한 무음 판단: 녹음 내내 이보다 작으면(실제 마이크는 조용한 방에서도 -60~-75dB 잡음이 있다) · 시작 후 이만큼 지나서 안내. */
        const val SILENT_DB = -80.0
        const val SILENT_AFTER_MS = 2500L
        const val NO_SIGNAL_PLAYBACK = "재생 소리가 전혀 들어오지 않았어요. 음악이 재생 중일 때 다시 담아 주세요."
        /** 흥얼거림이 들릴 때 녹음 버튼 둘레로 퍼지는 고리: 퍼지는 시간 · 새 고리 간격. */
        const val RIPPLE_MS = 700L
        const val RIPPLE_EVERY_MS = 350L
        /** 음표 보기 막대 그림 높이(프로젝트 선택). */
        const val ROLL = 560
        /** 녹음 길이 상한. 서버 업로드 상한(20MB)·변환 시간을 넘지 않게. */
        const val MAX_RECORD_SEC = 60
        /** 곡 화면 아래 줄 겹쳐/이어 토글 폭(아래 줄 오른쪽 칸에 들어가게 줄임 — 프로젝트 선택). */
        const val STACK_TOGGLE = 440
        /** 만드는 중 단계 문구를 넘기는 간격. 서버 처리 2~4초에 단계 3~5개가 대략 맞게. */
        const val PENDING_STEP_MS = 900L
        const val FINISH = "finish"
        /** "내 멜로디 다듬기"로 따로 보여 줄 변주(나머지는 변주 칸). */
        val POLISH = setOf(FINISH, "rewrite")
        /** 녹음 중 포커스 용도. 차량 포커스 표에서 음성 명령(ASSISTANT)은 음악과 함께 울리지 않아 음악이 잠깐 멈춘다. */
        const val FOCUS_USAGE = AudioAttributes.USAGE_ASSISTANT
        val PLAY_ATTRS: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        const val TUTORIAL_SEEN = "tutorial_seen"
        /** 첫 녹음 전 '녹음한 소리는 노래를 만드는 데만 써요' 안내를 봤는지. */
        const val VOICE_NOTICE_SEEN = "voice_notice_seen"
        /** 잘 들릴 때의 문구 — 흥얼거린 시간 단계(encourageStage)별. 앞의 ● 만 초록. */
        val ENCOURAGE = listOf(
            "●  잘 들려요 — 계속 흥얼거려 주세요",
            "●  잘 들려요 — 거의 됐어요, 조금만 더",
            "●  좋아요 — 한 소절 더 부르면 노래가 풍성해져요",
            "●  아주 좋아요 — 다 불렀으면 가운데 버튼을 누르세요",
            "●  곧 녹음이 끝나요 — 마무리해 주세요",
        )
        /** 5초도 안 돼 조용해졌을 때 — 3초 미만은 노래로 만들 수 없다. */
        const val QUIET_TOO_SHORT = "조금 더 흥얼거려 주세요. 5초 넘게 불러야 노래로 만들 수 있어요."
        const val REQ_EXPORT_RECORDING = 5
        /** 녹음 장치를 따로 고르지 않음 → 안드로이드 기본 마이크(AudioSource.MIC). 어느 마이크로 들어올지는 차량 오디오 설정이 정한다. */
        const val DEFAULT_INPUT = "자동(차량 기본 마이크)"
        val STEPS = listOf("흥얼거리기", "내 멜로디", "노래 만들기", "완성")
        const val INPUT_MENU_W = 560
        const val FOCUS_DENIED = "지금은 녹음할 수 없어요. 통화나 안내가 끝난 뒤 다시 흥얼거려 주세요."
        /** 녹음을 끝내는 스티어링휠·미디어 키: 재생/일시정지 · 일시정지 · 정지 · 이어폰 버튼. */
        val FINISH_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
                                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_HEADSETHOOK)
        /** Gleo 가 말하기를 끝내고 오디오 포커스를 놓을 틈. */
        const val GLEO_GAP_MS = 700L
        /** 음성 인식 표시: 가운데 원 크기 · 판 폭. */
        const val VOICE_ORB = 320
        const val VOICE_PANEL_W = 880
        const val MUSIC_SOURCE = "music_source"
        const val DEFAULT_KEY = "default"
        const val PROBE_SEC = 4
        const val CANCELLED = "녹음을 취소했어요. 방금 녹음은 저장하지 않았어요."
        const val FOCUS_LOST = "통화나 안내가 끼어들어 녹음을 멈췄어요. 끝난 뒤 다시 흥얼거려 주세요."
        const val REQ_CAPTURE = 6
        const val CAPTURE_IDLE_MS = 10 * 60 * 1000L
        const val REQ_MUSIC_RECORD = 4
        const val REQ_DRILL = 2
        const val REQ_VOICE = 3
        const val REQ_TALK = 7
        const val TALK_MIN_SEC = 5
        /** 대화 녹음 화면 가운데 목소리 원 높이. */
        const val TALK_ORB_H = 420
        /** 대화로 만들 노래의 방향(서버 talk.FEELINGS 와 같은 이름). */
        val TALK_DIRECTIONS = linkedMapOf(
            "그대로" to "대화에서 드러난 감정을 그대로 살려요. 위로나 교훈으로 바꾸지 않아요.",
            "통쾌하게" to "억울하고 답답했던 마음을 시원하게 받아치는 노래로.",
            "담담하게" to "과장하지 않고 이야기하듯 담담하게.",
            "유쾌하게" to "웃음이 나는 가볍고 신나는 노래로.",
        )
        /** 노래 만들기 단계(서버 job stage) → 화면 문구. */
        val FINAL_STAGES = listOf(
            "melody" to "멜로디를 2분 곡으로 늘리는 중 (전주·절·후렴·브리지 + 화음)",
            "lyrics" to "주제로 가사 쓰는 중 (줄마다 음 개수에 맞춰)",
            "cover" to "앨범 표지 그리는 중",
            "music" to "노래 부르는 중 (가장 오래 걸려요)",
        )
        val SECTION_NAMES = mapOf("verse" to "절", "chorus" to "후렴", "bridge" to "브리지")
        /** 가사 편집 칸 머리말(서버 가사 묶음 이름 ↔ 화면). */
        val LYRIC_KEYS = linkedMapOf("verse" to "절 1", "chorus" to "후렴", "verse2" to "절 2", "bridge" to "브리지")
        val VOICE_NAMES = linkedMapOf("auto" to "자동", "female" to "여성", "male" to "남성", "rap" to "랩", "none" to "연주곡")
        /** 주사위 주제(서버에 못 닿을 때). 서버 lyrics.THEMES 와 같은 목록 일부. */
        val THEMES = listOf(
            "비 오는 밤 퇴근길, 와이퍼 소리와 잔잔한 시티팝",
            "주말 아침 바닷가로 가는 드라이브, 상쾌한 어쿠스틱 팝",
            "새벽 고속도로, 졸음을 깨우는 신나는 신스팝",
            "노을 지는 해안도로, 몽글몽글한 로파이",
        )
        /** 노래 듣기 화면 표지 크기 · 세부 설정 칸 폭(오른쪽 칸에 들어가게 — 프로젝트 선택). */
        const val PLAYER_COVER = 520
        /** 홈 "내 노래": 받아 올 곡 수 · 보여 줄 카드 수 · 표지 크기. */
        const val LIBRARY_SIZE = 50
        /** 왼쪽 탭 폭·한 칸 높이, 음악 탭 표지 높이(dp). */
        /** 왼쪽 탭: 칸 200×168, 칸 사이 32(2026-11-08 사용자 요청 "버튼 영역 키우고 간격 띄우기" — 처음 168×120·간격 12). */
        const val TAB_W = 232
        const val TAB_H = 168
        const val TAB_GAP = 32
        /** 미니 플레이어 폭. */
        const val MINI_W = 560
        /** 허밍·음악 녹음 화면 가운데 목소리 원 높이. */
        const val REC_ORB_H = 300
        /** 대기열에 넣어 둔 노래 작업(prefs). */
        const val QUEUED_JOBS = "queued_final_jobs"
        /** 녹음실 양옆(대화·음악) 버튼 칸 폭. */
        const val SIDE_REC_W = 180
        /** 내 노래 보기 방식(tile·list). */
        const val MUSIC_VIEW = "music_view"
        /** 음악 녹음 이름(prefs 키 앞부분 + 파일 이름). */
        const val REC_NAME = "rec_name:"
        /** 슬라이더 손잡이 지름. */
        const val THUMB = 28
        /** 표지 받는 크기(px): 목록 칸 · 만드는 중 작은 칸 · 노래 듣기 큰 표지. */
        const val COVER_THUMB = 384
        const val COVER_SMALL = 256
        const val COVER_LARGE = 768
        /** 맨 아래 설정 탭 높이(주 탭보다 낮게 — 자주 누르지 않는다). */
        const val SETTINGS_TAB_H = 128
        const val MUSIC_COVER = 240
        const val OPTION_W = 640
        /** 목소리 고르기(자동·여성·남성·랩·연주곡) 폭. */
        const val VOICE_W = 820
        /** 시작음 '띠링'(0.3초)이 끝나자마자 녹음을 여는 간격 — 효과음은 섞이지 않게, 첫 음은 놓치지 않게. */
        const val BEEP_GAP_MS = 350L
        /** 따라 부르기 연습 막대 그림 높이. */
        const val DRILL_ROLL = 420
        /** 따라 부르기 연습 멜로디(직접 지은 8음, 템포 96 · 도~솔 음역). 마지막 음은 두 박. */
        val PRACTICE = listOf(60, 64, 67, 64, 62, 65, 64, 60).mapIndexed { i, m ->
            NoteRoll.Note(i * 0.625f, if (i == 7) 1.25f else 0.625f, m)
        }
        /** 흥얼거리기 요령(제목, 설명). 21번 시험 허밍 음역·SheetSage 전사 조건(5초 이상, 또렷한 음)에 맞춘 안내. */
        val TUTORIAL = listOf(
            "입을 다물고 '음~'" to "가사 없이 '음~' 하고 흥얼거리면 음이 제일 잘 잡혀요.",
            "가까이, 조금 크게" to "말할 때보다 조금 크게. 버튼 둘레가 퍼지면 잘 들리는 거예요.",
            "한 음씩 또렷하게" to "너무 빠르지 않게, 음이 바뀔 때 분명하게 바꿔 주세요.",
            "다 부르면 가운데 버튼" to "길이는 자유예요. 5초 넘게, 최대 60초까지.",
        )

        val HEADLINE = Typo(48, 64, R.font.asta_sans_bold)      // Headline/Small/Strong
        val TITLE = Typo(36, 44, R.font.asta_sans_bold)         // Title/Medium/Strong
        val BODY = Typo(30, 38, R.font.asta_sans_regular)       // Body/Large/Normal
        val BODY_STRONG = Typo(30, 38, R.font.asta_sans_bold)   // Body/Large/Strong
        val BODY_S = Typo(26, 34, R.font.asta_sans_regular)     // Body/Small/Normal
        val BODY_S_STRONG = Typo(26, 34, R.font.asta_sans_bold) // Body/Small/Strong
        val COUNT = Typo(112, 134, R.font.asta_sans_extrabold)  // Driving/Speed 크기

        /** 화면 여백(page_navigation/padding_extralarge 40을 가로 화면에 맞춰 넓힘 — 프로젝트 선택). */
        const val PAGE_H = 64
        const val PAGE_V = 32

        /** (화면 이름, GM 번호, 사운드폰트 이름) — UI 시안의 고정 6개. */
        val INSTRUMENTS = listOf(
            Triple("피아노", 0, "Grand Piano"), Triple("첼로", 42, "Cello"), Triple("바이올린", 40, "Violin"),
            Triple("플루트", 73, "Flute"), Triple("리코더", 74, "Recorder"), Triple("마림바", 12, "Marimba"),
        )

        /** 서버 목록을 못 받았을 때 쓰는 기본값(서버 comfy.VARIATIONS 와 같은 순서). */
        val DEFAULT_VARIATIONS = listOf(
            Variation("harmony", "화음 멜로디", "3도 위 둘째 목소리"), Variation("mood", "분위기 바꾸기", "장조↔단조"),
            Variation("ornament", "꾸밈 넣기", "꾸밈음 추가"), Variation("bounce", "통통 튀게", "셔플 리듬"),
            Variation("inversion", "위아래 뒤집기", "음 방향 반대로"), Variation("retrograde", "거꾸로", "끝에서부터"),
            Variation("step_up", "한 단계 위로", "음계 한 칸 위에서 다시"),
            Variation(FINISH, "멜로디 완성하기", "음계·박자를 맞추고 프레이즈 끝을 닫아 한 곡처럼"),
            Variation("rewrite", "AI로 멜로디 살리기", "허밍은 참고만 하고 AI가 더 노래다운 8마디로 새로 써요"),
        )
    }
}
