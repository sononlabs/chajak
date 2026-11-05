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
        api = ChajakApi(intent.getStringExtra("server") ?: BuildConfig.SERVER_URL)
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
            prefs.getBoolean(TUTORIAL_SEEN, false) -> showHome()
            else -> showTutorial()
        }
    }

    private val prefs by lazy { getSharedPreferences("chajak", MODE_PRIVATE) }

    /** 앱이 떠 있을 때 am start --es song <id> 로 곡 열기(시험·시연용). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("song")?.let { openSong(it) }
    }

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
        car = Car.createCar(this)
        val m = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
        driving = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
        m.registerListener { r ->
            driving = r.isRequiresDistractionOptimization
            Log.i(tag, "주행 규제 ${if (driving) "켜짐" else "꺼짐"}")
            ui.post {
                if (driving && recording) {
                    recorder.stop()
                    showError("주행 중에는 녹음할 수 없어요. 정차한 뒤 다시 흥얼거려 주세요.")
                } else if (!recording) refresh()
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

    private fun refresh(): Unit = current()

    /**
     * 화면 = [왼쪽 탭] + 위 내용(스크롤) + 아래 고정 동작 줄. 녹음 버튼은 어느 화면에서나 아래 가운데 같은 자리에 있다.
     * tabs: 큰 화면(녹음실·곡·목록·노래 듣기)에만 왼쪽 탭을 둔다. 카운트다운·녹음 중·오류 화면에는 없다(녹음 중 딴 데로 새지 않게).
     */
    private fun show(build: LinearLayout.() -> Unit, again: () -> Unit, tabs: Boolean = false, bar: (LinearLayout.() -> Unit)? = null) {
        current = again
        halo = null
        scoreToken++
        onHome = false
        onList = false
        root.removeAllViews()
        val page = column()
        val col = column().apply {
            setPadding(dp(PAGE_H), dp(PAGE_V), dp(PAGE_H), dp(PAGE_V))
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
        page.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (bar != null) {
            page.addView(View(this).apply { setBackgroundColor(TINT_PRESSED) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            page.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(PAGE_H), dp(20), dp(PAGE_H), dp(20))
                try { bar() } catch (e: Exception) { Log.e(this@MainActivity.tag, "아래 줄을 그리지 못함", e) }
            })
        }
        if (!tabs) return root.addView(page)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tabRail(), LinearLayout.LayoutParams(dp(TAB_W), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(this@MainActivity).apply { setBackgroundColor(TINT_PRESSED) }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(page, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        })
    }

    // ------------------------------------------------------------------ 왼쪽 탭

    enum class Tab(val label: String, val glyph: String) { STUDIO("녹음실", "●"), MUSIC("내 노래", "♫"), MELODIES("내 멜로디", "♪") }

    private var tab = Tab.STUDIO
    /** 목록 탭이 떠 있으면 목록을 새로 받았을 때 다시 그린다. */
    private var onList = false

    private fun tabRail() = column().apply {
        setBackgroundColor(SURFACE)
        setPadding(dp(16), dp(PAGE_V), dp(16), dp(PAGE_V))
        Tab.values().forEach { t ->
            val on = t == tab
            addView(column().apply {
                gravity = Gravity.CENTER
                background = if (on) round(TINT_PRESSED, 20) else pressable(round(SURFACE, 20), round(TINT, 20))
                contentDescription = t.label
                setOnClickListener { openTab(t) }
                addView(text(t.glyph, TITLE, if (on) RECORDING.takeIf { t == Tab.STUDIO } ?: TEXT else TEXT3).apply { gravity = Gravity.CENTER })
                addView(text(t.label, if (on) BODY_S_STRONG else BODY_S, if (on) TEXT else TEXT2).apply { gravity = Gravity.CENTER }.withMargin(top = 4))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TAB_H)).apply { bottomMargin = dp(12) })
        }
    }

    private fun openTab(t: Tab) {
        tab = t
        when (t) {
            Tab.STUDIO -> { stackMode = null; showHome() }
            Tab.MUSIC -> { loadLibrary(); showMusicList() }
            Tab.MELODIES -> { loadLibrary(); showMelodyList() }
        }
    }

    /** 탭 2 · 내 노래: 완성된 노래를 드라이브 앨범(만든 때 — 출근길·퇴근길·밤·주말…)으로 묶어 보여 준다. 누르면 노래 듣기. */
    private fun showMusicList() {
        val songs = library.filter { !it.isNull("title") }
        show({
            onList = true
            addView(headline("내 노래"))
            addView(body(if (songs.isEmpty()) "아직 완성된 노래가 없어요. 녹음실에서 흥얼거리고 노래 만들기를 눌러 보세요."
                         else "완성된 노래 ${songs.size}곡 · 만든 드라이브별 앨범"))
            songs.groupBy { DriveTheme.momentOf(it.optString("created")) }.forEach { (moment, list) ->
            addView(label("$moment 앨범 · ${list.size}곡"))
            addView(grid(list.map { item ->
                val id = item.getString("id")
                column().apply {
                    background = pressable(round(SURFACE, 24), round(TINT, 24))
                    setPadding(dp(16), dp(16), dp(16), dp(20))
                    setOnClickListener { openSong(id, player = true) }
                    addView(thumb(item), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MUSIC_COVER)))
                    addView(text(item.getString("title"), BODY_STRONG, TEXT).apply { maxLines = 1 }.withMargin(top = 12))
                    addView(text("${clock(item.optDouble("final_sec", 0.0).toFloat())} · ${keyName(item.optString("key"))} · ${dateOf(item)}", BODY_S, TEXT3))
                }
            }, 4).withMargin(fill = true))
            }
        }, ::showMusicList, tabs = true)
    }

    /** 탭 3 · 허밍 리스트: 음표로 바꾼 내 멜로디(곡)들. 누르면 곡 화면(트랙·음표 보기·변주). */
    private fun showMelodyList() {
        show({
            onList = true
            addView(headline("내 멜로디"))
            addView(body(if (library.isEmpty()) "아직 만든 멜로디가 없어요. 녹음실에서 흥얼거려 보세요."
                         else "음표로 바꾼 내 멜로디 ${if (library.size >= LIBRARY_SIZE) "최근 " else ""}${library.size}개 · 눌러서 트랙·음표 보기"))
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
                        addView(text(listOfNotNull(
                            "트랙 ${item.optInt("tracks")}개", clock(item.optDouble("duration_sec", 0.0).toFloat()),
                            "템포 ${item.optInt("tempo")}", keyName(item.optString("key")), dateOf(item),
                            if (item.isNull("title")) null else "노래 있음",
                        ).joinToString(" · "), BODY_S, TEXT3).withMargin(top = 4))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text("›", TITLE, TEXT3))
                }.withMargin(top = 16, fill = true))
            }
        }, ::showMelodyList, tabs = true)
    }

    private fun dateOf(item: JSONObject) = item.optString("created").let { c ->
        if (c.length >= 16) "${c.substring(5, 7)}.${c.substring(8, 10)} ${c.substring(11, 16)}" else c
    }

    /** 곡 표지(없으면 ♪). 받아 둔 표지는 다시 받지 않는다. */
    private fun thumb(item: JSONObject): View = FrameLayout(this).apply {
        val id = item.getString("id")
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
        thumbs[id]?.let { img.setImageBitmap(it); return@apply }
        val url = item.getString("cover_url")
        images.execute {
            try {
                val png = api.bytes(url)
                // 깨진·빈 그림이면 decode 가 null — 그대로 두면 Kotlin null 검사에서 앱이 죽는다
                val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@execute
                ui.post { thumbs[id] = bmp; img.setImageBitmap(bmp) }
            } catch (e: Exception) {
                Log.w(this@MainActivity.tag, "표지 못 받음: ${e.message}")
            }
        }
    }

    private var onHome = false

    /**
     * 1단계 녹음실: 녹음 전에는 아무것도 고르지 않는다(악기·반주는 기본값, 다듬기는 2단계에서). 큰 빨간 버튼 하나.
     * 오른쪽 위 작게 녹음 장치, 오른쪽 아래 작게 "음악에서 따기"(듣던 노래에서 멜로디 따기 — 공유 주의).
     * 정차 중 최근 멜로디가 노래 없이 남아 있으면 "아까 멜로디로 노래 만들까요?" 카드(정차하면 이어서).
     */
    private fun showHome() {
        val stacking = stackMode != null && song != null
        show({
            onHome = true
            addView(steps(0))
            addView(titleRow(headline(if (stacking) (if (stackMode == "overlay") "겹쳐 부르기" else "이어 부르기") else "녹음실"), inputPill()))
            addView(body(if (stacking) "지금 멜로디 위에 얹을 멜로디를 흥얼거려 주세요. 박자와 조성은 곡에 맞춰 드려요."
                         else "떠오른 한 소절을 흥얼거려 보세요. 듣던 음악은 녹음하는 동안만 멈춰요."))
            addView(caption("5~30초 한 소절이면 가장 잘 돼요(최대 ${MAX_RECORD_SEC}초). 다 부르면 가운데 버튼을 누르세요.", TEXT3))
            if (!stacking) {
                resumeCard()?.let { addView(it) }
                finalJob?.let { job ->
                    addView(button("만드는 노래 · 상태 보기", size = Size.MEDIUM) {
                        resumeFinalJob()
                        job.optString("song_id").takeIf { it.isNotEmpty() }?.let { openSong(it, create = true) }
                    }.withMargin(top = 16))
                }
                addView(rangeCard())
                addView(row(
                    text("음악에서 따기: 듣던 노래를 그대로 녹음해 멜로디를 따요. 남의 노래 멜로디는 내 노래로 공유하지 마세요.", BODY_S, TEXT3),
                ).withMargin(top = 20))
                addView(button("음악 녹음 보관함", size = Size.MEDIUM) { showMusicLibrary() }.withMargin(top = 12))
            }
        }, ::showHome, tabs = true) {
            zones(
                if (stacking) button("내 멜로디로") { stackMode = null; showSong(false) } else button("흥얼거리기 요령") { showTutorial() },
                listOf(if (!canRecord) recordButton(Rec.DISABLED, "녹음은 정차 후에", onClick = null)
                       else recordButton(Rec.READY, "흥얼거리기 시작") { startRecording() }),
                if (testInputs.isNotEmpty()) text("시험 모드 · 마이크 대신\n${testInputs[testIndex % testInputs.size]}", BODY_S, POSITIVE)
                else musicRecordButton(canRecord),
            )
        }
    }

    /** 정차하면 이어서: 하루 안에 만든 멜로디가 노래 없이 남아 있으면 바로 노래 만들기로. 주행 중에는 안 띄운다(소리·움직임 없음). */
    private fun resumeCard(): View? {
        if (driving) return null
        val item = library.filter { it.isNull("title") && DriveTheme.withinHours(it.optString("created"), 24) }
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
        addView(headline("곧 녹음을 시작해요"))
        addView(body("마이크 가까이에서 편하게 흥얼거려 주세요. 가운데 버튼을 누르면 취소돼요."))
    }, {}) {
        zones(null, listOf(recordButton(Rec.COUNTDOWN, "누르면 취소", count = n) { cancelCountdown() }))
    }

    private lateinit var remain: TextView
    private lateinit var hearing: TextView

    /** 녹음 중: 소리가 들리는지(hearing 문구)와, 흥얼거림이 들릴 때 녹음 버튼 둘레에 번지는 원으로 반응한다. */
    private fun showRecording(music: Boolean = false, direct: Boolean = false): Unit = show({
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(text(if (music) "●  음악 녹음 중" else "●  녹음 중", HEADLINE, RECORDING), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            remain = text("", TITLE, TEXT)
            addView(remain)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        hearing = text("", TITLE, TEXT3).withMargin(top = 24)
        addView(hearing)
        // 파형(가운데 점선 → 소리 나면 막대)
        wave = LevelStrip(this@MainActivity).also {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        liveRange = if (music) null else RangeBar(this@MainActivity, faces.getOrPut(BODY_S_STRONG.font) { resources.getFont(BODY_S_STRONG.font) }).also {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        }
        addView(body(when {
            !music -> "다 부르면 가운데 버튼을 누르세요. 녹음 장치: ${inputDevice?.let(InputDevices::label) ?: DEFAULT_INPUT}"
            direct -> "재생 중인 음악을 그대로 담고 있어요(음악은 멈추지 않아요). 원하는 부분이 끝나면 가운데 버튼을 누르세요."
            else -> "차량 마이크로 음악을 듣고 있어요. 원하는 부분이 끝나면 가운데 버튼을 누르세요."
        }).withMargin(top = 12))
        setHearing(Hearing.WAITING)
    }, {}) {
        zones(button("녹음 취소") { cancelRecording() }, listOf(recordButton(Rec.RECORDING, "그만 · 바로 변환") { recorder.stop() }))
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

    private fun setHearing(state: Hearing) {
        if (state == hearingState && hearing.text.isNotEmpty()) return
        hearingState = state
        hearing.text = state.message
        hearing.setTextColor(if (state == Hearing.VOICE) ACTIVE else TEXT3)
    }

    /** 녹음·변환에서 난 오류면 가운데 녹음 버튼으로 같은 설정 그대로 다시 부른다. 변주·트랙 빼기 오류면 곡으로 돌아간다. */
    private fun showError(message: String) {
        val retry = song == null || stackMode != null
        show({
            addView(headline("다시 한 번 해 볼까요?"))
            addView(text(message, BODY, TEXT))
        }, { showError(message) }) {
            val back = if (song != null) button("곡으로 돌아가기") { stackMode = null; showSong(false) } else null
            when {
                !retry -> zones(null, listOfNotNull(back?.withMargin()))
                driving -> zones(back, listOf(recordButton(Rec.DISABLED, "녹음은 정차 후에", onClick = null)))
                else -> zones(back, listOf(recordButton(Rec.READY, "다시 흥얼거리기") { startRecording() }))
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
            drillStatus = text(drillText(), if (drill == Drill.RESULT) TITLE else BODY_STRONG,
                               if (drill == Drill.RESULT) (if (drillGood) ACTIVE else RECORDING) else TEXT2).withMargin(top = 16)
            addView(drillStatus)
        }, ::showTutorial) {
            zones(button(if (prefs.getBoolean(TUTORIAL_SEEN, false)) "돌아가기" else "건너뛰기") { done() },
                listOf(when {
                    driving -> recordButton(Rec.DISABLED, "연습은 정차 후에", onClick = null)
                    drill == Drill.GUIDE -> recordButton(Rec.BUSY, "먼저 들어요", onClick = null)
                    drill == Drill.COUNT -> recordButton(Rec.COUNTDOWN, "따라 부를 준비", count = drillCount) { cancelDrill(); showTutorial() }
                    drill == Drill.SING -> recordButton(Rec.RECORDING, "그만") { recorder.stop() }
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
            addView(when {
                s == null || p != null -> steps(1, busy = true)
                s.optJSONObject("final") != null -> steps(3, done = true)
                else -> steps(1)
            })
            val left = column()
            left.addView(headline(s?.let { songName(it, "내 멜로디") } ?: "새 멜로디"))
            left.addView(body(if (s == null) "흥얼거림을 음표로 바꾸고 있어요"
                              else "템포 ${s.optInt("tempo")} · ${keyName(s.optString("key"))} · 트랙 ${count}개"))
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
            left.addView(label("트랙 · 음표로 바꾼 멜로디"))
            for (i in 0 until count) left.addView(trackRow(tracks!!.getJSONObject(i)))
            if (p != null) left.addView(pendingRow(p))

            val right = column()
            if (s != null) right.addView(suggestCard(Suggest.melody(s), idle))
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

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { marginEnd = dp(64) })
                addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }, { showSong(false) }, tabs = true) {
            val play = s?.let { button(if (playing == "mix") "■  멈춤" else "▶  전체 듣기") { toggle("mix", it.optString("audio_url")) } }
            zones(play,
                listOf(if (s != null && idle) button("노래로 만들기 →", Btn.SECONDARY) { showCreate() }
                       else recordButton(Rec.BUSY, if (p != null) "음표로 바꾸는 중" else "노래 만드는 중", onClick = null)),
                button("새로 흥얼거리기", enabled = idle) { stopPlayback(); song = null; stackMode = null; showHome() })
        }
        if (autoplay && s != null) toggle("mix", s.optString("audio_url"))
        trackProgress(s?.optDouble("duration_sec", 0.0)?.toFloat() ?: 0f)
    }

    private var showMore = false

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
        if (!making && promptFor != s.optString("id")) { prompt = DriveTheme.now(); themeFromDrive = true; promptFor = s.optString("id") }
        show({
            addView(steps(2, busy = making))
            addView(headline("노래 만들기"))
            addView(body("${songName(s, "내 멜로디")} · 템포 ${s.optInt("tempo")} · ${keyName(s.optString("key"))} → 2분 노래(가사·앨범 표지)"))
            if (job != null) {
                addView(label("만드는 중"))
                addView(finalPendingRow(job))
            }
            addView(label("주제"))
            addView(promptRow(enabled = !making))
            addView(caption(if (themeFromDrive) "오늘 드라이브로 채웠어요. 🎲 로 바꾸거나 🎤 로 말해도 돼요." else "비워 두면 🎲 처럼 아무 주제나 골라요.", TEXT3))
            val type = voiceRange.type()
            addView(label("목소리" + (type?.let { " · 자동이면 내 음역(${it.name} 쪽)에 맞춰 ${if (it.male) "남성" else "여성"}" } ?: "")))
            val voices = VOICE_NAMES.keys.toList()
            addView(segmented(VOICE_NAMES.values.toList(), voices.indexOf(voice), width = OPTION_W) { voice = voices[it]; saveDraft(); refresh() })
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
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(caption("한 소절을 전주·절·후렴·브리지로 늘리고, 주제로 가사·제목을 쓰고, 앨범 표지를 그려 노래로 만들어요. 약 3분 — 주행을 시작해도 계속 만들어요.", TEXT3).withMargin(top = 16))
        }, ::showCreate, tabs = true) {
            zones(button("← 내 멜로디") { showSong(false) },
                listOf(if (making) recordButton(Rec.BUSY, "만드는 중", onClick = null)
                       else button("Create music", Btn.SECONDARY) { createFinal() }),
                s.optJSONObject("final")?.let { button("완성된 노래 →") { showPlayer(false) } })
        }
    }

    /** 주제가 오늘 드라이브에서 자동으로 왔는지(안내 문구용). 사용자가 고치거나 🎲 를 누르면 false. */
    private var themeFromDrive = false
    /** 지금 주제가 어느 곡의 것인지. */
    private var promptFor: String? = null

    // ------------------------------------------------------------------ 내 노래(목록)

    private var library = listOf<JSONObject>()
    private val thumbs = HashMap<String, Bitmap>()

    private fun loadLibrary() {
        io.execute {
            try {
                val a = api.songs(LIBRARY_SIZE).getJSONArray("songs")
                val list = (0 until a.length()).map { a.getJSONObject(it) }
                ui.post { library = list; if (onList || (onHome && !recording && !counting)) refresh() }
            } catch (e: Exception) {
                Log.w(tag, "내 노래 목록을 못 받음: ${e.message}")
            }
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
        addView(iconButton(if (listening) "■" else "🎤") { if (enabled) voicePrompt(input) }.apply {
            contentDescription = "말로 주제 넣기"
            if (listening) { background = round(RECORDING, 20); setTextColor(Color.WHITE) }
        }, LinearLayout.LayoutParams(dp(100), dp(100)).apply { marginEnd = dp(16) })
        addView(iconButton("🎲") { if (enabled) rollTheme(input) }.apply { contentDescription = "주사위 — 아무 주제나" }, LinearLayout.LayoutParams(dp(100), dp(100)))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private var listening = false
    private var recognizer: SpeechRecognizer? = null

    /**
     * 말로 주제 넣기: Pleos 기기 음성 인식(RecognitionService, 에뮬레이터는 ai.umos.ambientai)으로 받아 적는다.
     * 듣는 동안 차 음악은 오디오 포커스로 잠깐 멈추고, 한 번 더 누르면 그만. 말한 내용은 기기 인식 서비스로만 간다.
     */
    private fun voicePrompt(input: EditText, onText: (String) -> Unit = { prompt = it }) {
        if (listening) { recognizer?.stopListening(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VOICE)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { input.hint = "이 차에서는 음성 인식을 쓸 수 없어요"; return }
        stopPlayback()
        if (!holdFocus()) { input.hint = "지금은 말로 넣을 수 없어요(통화·안내 중)"; return }
        listening = true
        refresh()
        fun done(text: String?) {
            listening = false
            releaseFocus()
            recognizer?.destroy(); recognizer = null
            if (!text.isNullOrBlank()) onText(text)
            refresh()
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { Log.i(tag, "음성 인식 준비") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { Log.w(tag, "음성 인식 오류 $error"); done(null) }
                override fun onResults(results: Bundle?) = done(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                override fun onPartialResults(partial: Bundle?) {
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onText(it); input.setText(it) }
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

    private fun optionSummary() =
        "${finalSeconds / 60}분 · ${VOICE_NAMES.getValue(voice)} · 멜로디 $melodyWeight · 스타일 $styleWeight"

    private fun optionsPanel() = column().apply {
        background = round(SURFACE, 24)
        setPadding(dp(28), dp(8), dp(28), dp(24))
        addView(label("곡 길이"))
        addView(segmented(listOf("1분", "2분", "3분"), finalSeconds / 60 - 1, width = OPTION_W) { finalSeconds = (it + 1) * 60; saveDraft(); refresh() })
        addView(label("목소리"))
        val voices = VOICE_NAMES.keys.toList()
        addView(segmented(VOICE_NAMES.values.toList(), voices.indexOf(voice), width = OPTION_W) { voice = voices[it]; saveDraft(); refresh() })
        addView(weightSlider("내 멜로디 반영", "70 이상: 내 허밍 그대로 · 33~69: 허밍은 참고만, AI가 더 좋은 멜로디로 · 33 미만: AI가 자유롭게", melodyWeight) { melodyWeight = it; saveDraft() })
        addView(weightSlider("스타일 강도", "높을수록 주제의 장르·분위기를 진하게", styleWeight) { styleWeight = it; saveDraft() })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    /** 가중치 막대(0~100). 손을 떼면 값을 저장한다(다시 그리지 않아 끌기가 끊기지 않음). */
    private fun weightSlider(name: String, help: String, value: Int, onSet: (Int) -> Unit) = column().apply {
        val title = text("$name  $value", BODY_S_STRONG, TEXT3).withMargin(top = 24, bottom = 4)
        addView(title)
        addView(SeekBar(this@MainActivity).apply {
            max = 100
            progress = value
            progressTintList = ColorStateList.valueOf(GRAY_600)
            thumbTintList = ColorStateList.valueOf(GRAY_600)
            progressBackgroundTintList = ColorStateList.valueOf(TINT_PRESSED)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) { title.text = "$name  $p" }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) { onSet(progress) }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        addView(text(help, BODY_S, TEXT3))
    }

    private fun createFinal(from: String? = null, keepLyrics: Boolean = false, lyrics: JSONObject? = null, keepCover: Boolean = false, edit: String? = null) {
        val s = song ?: return
        if (finalSubmitting || finalJob != null || recording || counting) return
        finalSubmitting = true
        saveDraft()
        stopPlayback()
        val promptSnapshot = prompt
        // 목소리 '자동'이면 내 음역대 쪽(남성/여성)으로 — 따라 부르기 편한 노래가 되게
        val sung = if (voice == "auto") voiceRange.type()?.let { if (it.male) "male" else "female" } ?: "auto" else voice
        val options = JSONObject().put("seconds", finalSeconds).put("voice", sung)
            .put("melody_weight", melodyWeight).put("style_weight", styleWeight)
        if (from != null) options.put("from", from).put("keep_lyrics", keepLyrics).put("keep_cover", keepCover)
        if (lyrics != null) options.put("lyrics", lyrics)
        if (!edit.isNullOrBlank()) options.put("edit", edit)
        playerVersion = null
        showCreate()
        io.execute {
            try {
                val j = api.startFinal(s.getString("id"), promptSnapshot, options)
                ui.post {
                    if (!isDestroyed) {
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
                            val url = final?.optString("cover_url")?.takeIf { it.isNotEmpty() }
                            // 표지 다운로드가 실패해도 완성된 노래는 열 수 있게 한다.
                            val bitmap = url?.let { runCatching { api.bytes(it) }.getOrNull() }
                                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            ui.post {
                                if (token == finalPollToken && !isDestroyed) {
                                    rememberFinalJob(null)
                                    if (url != null && bitmap != null) { covers[url] = bitmap; thumbs[songId] = bitmap }
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
                                        if (changed) refresh() else finalElapsed?.let { updateFinalPending(j) }
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
                    covers[url]?.let { setImageBitmap(it) } ?: images.execute {
                        runCatching { api.bytes(url) }.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            ?.let { b -> ui.post { covers[url] = b; setImageBitmap(b) } }
                    }
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
        val elapsed = (System.currentTimeMillis() / 1000.0 - j.getDouble("created")).toFloat()
        val eta = j.optDouble("eta_sec", 180.0).toFloat()
        finalPendingBar?.progress = (elapsed / eta * 1000).toInt().coerceIn(0, 980)
        finalElapsed?.text = "${clock(elapsed)} 지남 · 약 ${clock(maxOf(0f, eta - elapsed))} 남음 · 다른 화면을 봐도 계속 만들어요"
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
        box.addView(iconButton("▶") { showPlayer(autoplay = true) })
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
        covers[url]?.let { v.setImageBitmap(it); return v }
        images.execute {
            try {
                val png = api.bytes(url)
                val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@execute
                ui.post { covers[url] = bmp; v.setImageBitmap(bmp) }
            } catch (e: Exception) {
                Log.w(tag, "표지를 받지 못함: ${e.message}")
            }
        }
        return v
    }

    private val covers = HashMap<String, Bitmap>()
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
        addView(text("말로 바꾸기 · 예: 더 신나게, 멜로디는 살려줘", BODY_S_STRONG, TEXT3))
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
            addView(iconButton(if (listening) "■" else "🎤") { voicePrompt(input) { editText = it } }.apply { contentDescription = "말로 바꾸기 — 말하기" },
                LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(12) })
            addView(button("새 버전", Btn.SECONDARY, Size.MEDIUM, enabled = !finalSubmitting) {
                val req = editText.trim()
                if (req.isEmpty()) return@button
                editText = ""
                prompt = f.optString("prompt", prompt)
                createFinal(from = f.optString("id").ifEmpty { null }, keepLyrics = true, keepCover = true, edit = req)
            })
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
        lateinit var seek: SeekBar
        lateinit var time: TextView
        show({
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
                addView(left, LinearLayout.LayoutParams(dp(PLAYER_COVER), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(56) })
                val col = column()
                col.addView(headline(f.getString("title")))
                col.addView(text(f.getString("prompt"), BODY, TEXT2))
                f.optString("edit_summary").takeIf { it.isNotEmpty() && it != "null" }?.let { col.addView(text("✦ 이번 버전: $it", BODY_S, POSITIVE).withMargin(top = 4)) }
                val o = f.optJSONObject("options")
                col.addView(text(listOfNotNull(f.getString("style"),
                    o?.let { "멜로디 ${it.optInt("melody_weight")} · 스타일 ${it.optInt("style_weight")} · ${VOICE_NAMES[it.optString("voice")] ?: "자동"}" }).joinToString("\n"), BODY_S, TEXT3).withMargin(top = 4))
                seek = SeekBar(this@MainActivity).apply {
                    max = 1000
                    progressTintList = ColorStateList.valueOf(POSITIVE)
                    thumbTintList = ColorStateList.valueOf(POSITIVE)
                    progressBackgroundTintList = ColorStateList.valueOf(TINT_PRESSED)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {}
                        override fun onStartTrackingTouch(b: SeekBar?) {}
                        override fun onStopTrackingTouch(b: SeekBar?) {
                            if (playing == key && prepared) activePlayer?.seekTo((progress / 1000f * total * 1000).toLong())
                        }
                    })
                }
                col.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(24) })
                time = text("0:00 / ${clock(total)}", BODY_S_STRONG, TEXT3)
                col.addView(time)
                if (finalJob == null) col.addView(suggestCard(Suggest.song(f, s, voiceRange), !finalSubmitting) { remix -> remixWith(f, remix) }.withMargin(top = 24))
                if (finalJob == null) col.addView(editRow(f).withMargin(top = 16))
                // 다시 만들기(설정 다시 쓰기 · 다른 편곡 · 가사 고치기): 이 버전을 바탕으로 가중치·주제·가사를 바꿔 새 버전
                col.addView(button(if (showRemix) "다시 만들기 접기 ▴" else "이 노래로 다시 만들기 · 가중치·가사 바꿔서 ▾", size = Size.MEDIUM, enabled = finalJob == null) {
                    showRemix = !showRemix
                    if (showRemix) { prompt = f.getString("prompt"); f.optJSONObject("options")?.let { o ->
                        finalSeconds = o.optInt("seconds", 120); voice = o.optString("voice", "auto")
                        melodyWeight = o.optInt("melody_weight", 80); styleWeight = o.optInt("style_weight", 50) } }
                    showPlayer(false)
                }.withMargin(top = 24))
                if (showRemix && finalJob == null) {
                    val panel = column().apply { background = round(SURFACE, 24); setPadding(dp(28), dp(8), dp(28), dp(28)) }
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
                col.addView(label("가사"))
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
                    col.addView(text(SECTION_NAMES[name.trimEnd('2')] ?: name, BODY_S_STRONG, TEXT3).withMargin(top = 12, bottom = 4))
                    val start = sec.getDouble("start_sec").toFloat()
                    val lineSec = sec.optInt("bars", 2 * lines.length()) * barSec / lines.length()
                    for (j in 0 until lines.length()) {
                        val tv = text(lines.getString(j), BODY, TEXT3).withMargin(bottom = 4)
                        views += LyricLine(start + j * lineSec, start + (j + 1) * lineSec, tv)
                        col.addView(tv)
                    }
                }
                if (views.isEmpty()) col.addView(text("연주곡이에요(가사 없음).", BODY, TEXT3))
                lyricViews = views
                addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }, { showPlayer(false) }, tabs = true) {
            zones(button("← 내 멜로디") { showSong(false) },
                listOf(button(if (playing == key) "■  멈춤" else "▶  노래 듣기", Btn.SECONDARY) { toggle(key, f.getString("audio_url")) }),
                button("처음부터") { stopPlayback(); toggle(key, f.getString("audio_url")) })
        }
        if (autoplay) toggle(key, f.getString("audio_url"))
        val token = scoreToken
        fun tick() {
            if (token != scoreToken) return
            val pos = if (playing == key && prepared) (activePlayer?.currentPosition ?: 0) / 1000f else 0f
            if (!seek.isPressed) seek.progress = (pos / total * 1000).toInt()
            time.text = "${clock(pos)} / ${clock(total)}"
            // 지금 부르는 줄만 빨갛게, 지난 줄은 진하게, 남은 줄은 옅게(구간 사이 간주에는 빨간 줄 없음)
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
        info.addView(text(listOfNotNull(
            clock(dur),
            mode,
            "음 ${t.optInt("notes")}개 · 템포 ${t.getInt("tempo")}$fit",
            if (chords.isNotEmpty()) "반주 화음 $chords" else null,
        ).joinToString(" · "), BODY_S, TEXT3).withMargin(top = 4))
        val bar = progressBar()
        if (dur > 0) trackBars += TrackBar(n, t.optDouble("start_sec", 0.0).toFloat(), dur, bar)
        info.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(12) })
        box.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(24) })
        if ((t.optJSONArray("score")?.length() ?: 0) > 0)
            box.addView(iconButton("♪") { stopPlayback(); showScore(t) }, LinearLayout.LayoutParams(dp(80), dp(80)).apply { marginEnd = dp(16) })
        t.optString("hum_url").takeIf { it.isNotEmpty() && it != "null" }?.let { url ->
            // 전/후 들어보기: 내가 흥얼거린 원본 ↔ 음표로 바꾼 연주
            box.addView(button(if (playing == "hum$n") "■ 원본" else "원본", size = Size.MEDIUM) { toggle("hum$n", url) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) })
        }
        box.addView(iconButton(if (playing == "track$n") "■" else "▶") { toggle("track$n", t.getString("audio_url")) })
        return box.withMargin(bottom = 16, fill = true)
    }

    // ------------------------------------------------------------------ 동작

    /**
     * 음악 녹음: 듣던 음악을 멈추지 않고, 재생 중인 소리를 그대로 담는다(AudioPlaybackCapture — 녹음마다 동의 창 한 번).
     * 동의를 못 받거나 차가 지원하지 않으면 차량 마이크로 듣는다. 다 담으면 허밍과 똑같이 음표로 바꿔 트랙·곡이 된다
     * (서버 SheetSage 가 노래 속 멜로디를 뽑는다). 원본은 음악 녹음 보관함에도 남는다.
     * stack: 곡 화면에서 누르면 그 곡에 겹쳐/이어 쌓고, 그 밖에서는 지금 쌓기 방식(stackMode) 그대로.
     */
    private fun startMusicRecording(stack: String? = stackMode) {
        if (driving || recording || counting || pending != null || !active) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            musicPermissionPending = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MUSIC_RECORD)
            return
        }
        stopPlayback()
        stackMode = stack
        if (android.os.Build.VERSION.SDK_INT < 29) return record(music = true)  // 재생 소리 녹음은 Android 10 부터
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

    private fun stopCapture() {
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
        show({
            addView(headline("음악 녹음 보관함"))
            addView(body("음악 녹음 버튼으로 담아 둔 소리예요. ${files.size}개 보관 중 · 멜로디로 만들면 허밍처럼 트랙이 돼요."))
            if (files.isEmpty()) addView(body("빨간 녹음 버튼 오른쪽의 작은 ‘음악 녹음’ 버튼으로 첫 소리를 담아 보세요.").withMargin(top = 24))
            else if (!driving) addView(caption("지울 녹음은 왼쪽으로 밀어 삭제를 누르세요.", TEXT3))
            files.forEach { file ->
                val key = "recording:${file.name}"
                val duration = (file.length() - 44) / 96000f
                val date = java.text.SimpleDateFormat("MM.dd HH:mm", java.util.Locale.KOREA).format(java.util.Date(file.lastModified()))
                val box = column().apply {
                    background = round(SURFACE, 24)
                    setPadding(dp(24), dp(16), dp(24), dp(16))
                    addView(text("$date · ${clock(duration)}", BODY_STRONG, TEXT))
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
        }, ::showMusicLibrary, tabs = true) {
            zones(button("홈으로") { showHome() }, listOf(musicRecordButton(!driving)))
        }
    }

    private fun deleteRecording(file: File, key: String) {
        if (driving) return
        if (playing == key) stopPlayback()
        if (!file.delete()) return showError("녹음을 지우지 못했어요.")
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
            .putString("draft_melody_mode", melody).putBoolean("draft_accompaniment", accompaniment).apply()
    }

    private fun restoreDraft() {
        prompt = prefs.getString("draft_prompt", "") ?: ""
        finalSeconds = prefs.getInt("draft_seconds", 120).takeIf { it in listOf(60, 120, 180) } ?: 120
        voice = (prefs.getString("draft_voice", "auto") ?: "auto").takeIf { it in VOICE_NAMES } ?: "auto"
        melodyWeight = prefs.getInt("draft_melody", 80).coerceIn(0, 100)
        styleWeight = prefs.getInt("draft_style", 50).coerceIn(0, 100)
        instrument = prefs.getInt("draft_instrument", 0).coerceIn(0, INSTRUMENTS.lastIndex)
        melody = prefs.getString("draft_melody_mode", "그대로")?.takeIf { it in listOf("그대로", "다듬기") } ?: "그대로"
        accompaniment = prefs.getBoolean("draft_accompaniment", true)
    }

    private fun startRecording() {
        if (driving) return showError("주행 중에는 녹음할 수 없어요. 정차한 뒤 다시 흥얼거려 주세요.")
        stopPlayback()
        cancelled = false
        if (testInputs.isNotEmpty()) {
            val f = File(filesDir, testInputs[testIndex++ % testInputs.size])
            return upload(f)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!holdFocus()) return showError(FOCUS_DENIED)
        countdown(3)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when {
            requestCode == REQ_DRILL -> if (granted) startDrill() else showTutorial()
            requestCode == REQ_VOICE -> if (granted) promptInput?.let { voicePrompt(it) }
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

    // 주행 규칙(15번 v4 4절): 녹음·더 만들기·글자 입력은 정차에서, 듣기·다듬기·🎲·🎤·Create music 은 주행 중에도
    private val canRecord get() = !driving

    private fun releaseFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it); Log.i(tag, "오디오 포커스 반납") }
        focus = null
    }

    private fun countdown(n: Int, token: Int = ++countdownToken) {
        if (token != countdownToken) return
        if (driving || !active) {
            cancelCountdown()
            if (driving) showError("주행 중에는 녹음할 수 없어요. 정차 후 다시 시작해 주세요.")
            return
        }
        if (n == 0) {
            // 시작음이 끝난 뒤 녹음을 연다 — 효과음이 녹음에 섞이지 않게
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
            addView(text("흥얼거리면 내 음역대가 여기에 쌓여요. 녹음하는 동안 지금 내는 음이 막대 위에 점으로 보여요.", BODY_S, TEXT2).withMargin(top = 8))
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
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { if (onHome && !recording) refresh() }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { if (onHome && !recording) refresh() }
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
                    override fun isEnabled(i: Int) = i < names.size
                    override fun getView(i: Int, convert: View?, parent: ViewGroup?): View =
                        if (i == names.size) text("폰 마이크는 차가 인식할 때만 보여요.", BODY_S, TEXT3).apply { setPadding(dp(24), dp(12), dp(24), dp(16)) }
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
                    if (i >= devices.size) return@setOnItemClickListener
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

    /** 녹음 중 "녹음 취소": 지금까지 녹음은 버린다(서버로 안 보냄). */
    private fun cancelRecording() {
        if (!recording) return
        cancelled = true
        recorder.stop()
    }

    /** music: 음악 녹음(포커스 없이, 재생 소리 직접 또는 마이크). 끝나면 허밍과 같은 흐름 + 보관함에 원본. */
    private fun record(music: Boolean = false) {
        val capture = if (music && android.os.Build.VERSION.SDK_INT >= 29) projection else null
        showRecording(music, direct = capture != null)
        recording = true
        cancelled = false
        musicCapturing = music
        if (music) Log.i(tag, "음악 녹음 시작: ${if (capture != null) "재생 소리 직접" else "차량 마이크"}, 포커스 요청 없음")
        val file = File(cacheDir, "hum.wav")
        val take = VoiceRange()
        pitchColor = RECORDING
        liveRange?.range = take
        val total = MAX_RECORD_SEC
        val started = System.currentTimeMillis()
        var lastVoice = 0L
        var lastRipple = 0L
        rec.execute {
            val sec = try {
                recorder.record(file, total, source = if (android.os.Build.VERSION.SDK_INT >= 29) capture?.let(::captureSource) else null,
                                device = if (capture == null) inputDevice else null) { db, midi ->
                    ui.post {
                        val nowMs = System.currentTimeMillis()
                        val voiced = midi != null && db > VOICE_DB
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
                            nowMs - lastVoice < VOICE_HOLD_MS -> Hearing.VOICE
                            lastVoice == 0L -> Hearing.WAITING
                            nowMs - lastVoice > QUIET_MS -> Hearing.QUIET
                            else -> hearingState
                        })
                        // -60dB(조용) ~ -10dB(큼) 을 0~100% 로. 커질 땐 바로, 작아질 땐 천천히.
                        halo?.let {
                            val target = HALO_MIN + (1 - HALO_MIN) * ((db + 60) / 50).coerceIn(0.0, 1.0).toFloat()
                            val k = it.scaleX + (target - it.scaleX) * (if (target > it.scaleX) 0.7f else 0.25f)
                            it.scaleX = k; it.scaleY = k
                            it.alpha = if (hearingState == Hearing.VOICE) 1f else 0.4f
                        }
                        val left = total - (nowMs - started) / 1000
                        val el = (nowMs - started) / 1000
                        remain.text = if (left <= 10) "${left}초 뒤 멈춰요" else "%02d:%02d".format(el / 60, el % 60)
                        wave?.push(db, if (voiced) pitchColor else TEXT3)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "녹음 실패", e)
                -1.0
            }
            ui.post {
                val stoppedByDriving = driving
                recording = false
                musicCapturing = false
                releaseFocus()
                if (music) stopCapture()
                when {
                    stoppedByDriving -> Unit  // 리스너가 이미 안내 화면을 띄움
                    // 취소: 녹음은 버리고 보내지 않는다. 곡 작업 중이었으면 그 곡으로 돌아간다
                    interrupted -> { interrupted = false; cancelled = false; if (active) showError(FOCUS_LOST) }
                    cancelled -> if (active) {
                        cancelled = false
                        if (song != null) { stackMode = null; showSong(false) } else showHome()
                    }
                    sec < 0 -> showError(if (music) "음악을 녹음하지 못했어요." else "마이크를 열지 못했어요.")
                    sec < 3 -> showError("너무 짧아요(${"%.1f".format(sec)}초). 5초 이상 ${if (music) "담아" else "흥얼거려"} 주세요.")
                    else -> {
                        if (music) keepMusicRecording(file) else rememberRange(take)
                        upload(file, music)
                    }
                }
            }
        }
    }

    private fun upload(file: File, music: Boolean = false) {
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
                ui.post { endPending(); showError("서버와 연결이 끊겼어요. 잠시 후 다시 시도해 주세요.") }
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
        if (path.startsWith("http://") || path.startsWith("https://")) return path
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

    /** Pleos Icon button(Basic · Medium 80×80). 아이콘 자산 대신 글리프를 쓴다. */
    private fun iconButton(glyph: String, onClick: () -> Unit) = text(glyph, TITLE, TEXT).apply {
        gravity = Gravity.CENTER
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

    /**
     * 녹음 전용 버튼(프로젝트 컴포넌트): 지름 200 원형, 가운데 아이콘이 상태를 보여 준다.
     * 준비 = 빨간 원 + 마이크 · 카운트다운 = 진회색 원 + 숫자 · 녹음 중 = 빨간 원 + 멈춤, 뒤에서 소리 크기만큼 번지는 원
     * · 변환 중 = 회색 원 + 진행 표시 · 주행 중 = 비활성 원 + 사선 마이크.
     */
    /** 기존 빨간 버튼 오른쪽의 작은 음악 녹음 버튼. 터치 영역은 80dp. */
    private fun musicRecordButton(enabled: Boolean, stack: String? = stackMode): View = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        val core = FrameLayout(this@MainActivity).apply {
            background = if (enabled) pressable(oval(POSITIVE), oval(GRAY_700)) else oval(TINT)
            contentDescription = if (enabled) "음악을 멈추지 않고 녹음해 멜로디로" else "음악 녹음 사용 불가"
            isEnabled = enabled
            if (enabled) setOnClickListener { startMusicRecording(stack) }
            addView(text("♪ ●", BODY_STRONG, if (enabled) Color.WHITE else TEXT_DISABLED),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        addView(core, LinearLayout.LayoutParams(dp(80), dp(80)))
        addView(text("음악에서 따기", BODY_S, if (enabled) TEXT else TEXT3).withMargin(top = 8))
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
            Rec.COUNTDOWN -> text("$count", COUNT, Color.WHITE) to ViewGroup.LayoutParams.WRAP_CONTENT
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
    private fun LinearLayout.zones(left: View?, center: List<View>, right: View? = null) {
        fun side(v: View?, g: Int) = FrameLayout(this@MainActivity).apply {
            if (v != null) addView(v, FrameLayout.LayoutParams(v.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                                                               v.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT, g or Gravity.CENTER_VERTICAL))
        }
        addView(side(left, Gravity.START), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        center.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(32) })
        }
        addView(side(right, Gravity.END), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    data class Variation(val id: String, val name: String, val description: String)

    /** 만들고 있는 트랙: 카드 제목과 차례로 보여 줄 단계 문구. */
    data class Pending(val title: String, val steps: List<String>)

    /** Pleos 텍스트 스타일: 크기 · 줄 높이(sp/px) · Asta Sans 굵기. */
    data class Typo(val size: Int, val line: Int, val font: Int)

    enum class Btn { PRIMARY, SECONDARY }

    enum class Rec { READY, COUNTDOWN, RECORDING, BUSY, DISABLED }

    /** 녹음 중 소리 상태: 아직 안 들림 · 흥얼거림이 들림 · 들리다가 조용해짐. */
    enum class Hearing(val message: String) {
        WAITING("흥얼거려 보세요. 잘 들리면 버튼 둘레가 반응해요."),
        VOICE("●  잘 들려요"),
        QUIET("조용해졌어요. 다 불렀으면 가운데 버튼을 누르세요."),
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
        const val REQ_EXPORT_RECORDING = 5
        const val DEFAULT_INPUT = "기본 마이크(차가 고름)"
        val STEPS = listOf("흥얼거리기", "내 멜로디", "노래 만들기", "완성")
        const val INPUT_MENU_W = 560
        const val FOCUS_DENIED = "지금은 녹음할 수 없어요. 통화나 안내가 끝난 뒤 다시 흥얼거려 주세요."
        const val FOCUS_LOST = "통화나 안내가 끼어들어 녹음을 멈췄어요. 끝난 뒤 다시 흥얼거려 주세요."
        const val REQ_CAPTURE = 6
        const val REQ_MUSIC_RECORD = 4
        const val REQ_DRILL = 2
        const val REQ_VOICE = 3
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
        val VOICE_NAMES = linkedMapOf("auto" to "자동", "female" to "여성", "male" to "남성", "none" to "연주곡")
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
        const val TAB_W = 200
        const val TAB_H = 120
        const val MUSIC_COVER = 240
        const val OPTION_W = 640
        /** 시작음(0.25초 + 꼬리)이 다 끝난 뒤 녹음을 여는 간격. */
        const val BEEP_GAP_MS = 450L
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
