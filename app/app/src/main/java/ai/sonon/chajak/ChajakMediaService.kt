package ai.sonon.chajak

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 차작의 미디어 세션(Pleos Connect Music/Audio 카테고리).
 *
 * 가이드( https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide ) 요구와 대응:
 *  - MBS(Media Browser Service) 구현 — Media3 MediaLibraryService + 매니페스트의 android.media.browse.MediaBrowserService 선언.
 *    "MBS를 구현하지 않은 오디오 앱은 차량 탑재 및 배포 대상에서 제외될 수 있다."
 *  - 스티어링휠 키 기본 제어 — MediaSession 기본 제공(재생/일시정지/정지).
 *  - 미디어 ID 기반 재생 — onGetItem + 미디어 브라우저의 play-by-media-id.
 *  - 검색 기반 재생 — onSearch + onGetSearchResult(음성 "내 멜로디 2번째 재생").
 *  - 이전·다음 트랙 — 한 곡의 트랙(내 멜로디)이나 버전(완성된 노래)을 한 줄(큐)로 이어둔다.
 *  - 자동 재생 금지 — 사용자가 누르거나 시스템 재개 요청에만 재생한다.
 *  - 포커스 획득·상실 대응 — ExoPlayer 가 오디오 포커스(GAIN)를 관리하고, 잃으면 재생을 멈춘다.
 *    이 세션은 완성된 노래용(차의 주 음악이 된다). 짧은 소리(녹음·트랙·전체 듣기)는 Activity 의
 *    미리 듣기 플레이어가 세션 밖에서 잠깐 포커스로 튼다 — TransientFocus 참고.
 *  - Started 상태 + onTaskRemoved — 화면을 닫아도 세션이 남고, 앱이 밀려나면 정리한다.
 *
 * Activity(화면)는 MediaController 로 이 세션에 명령만 보낸다. 재생 상태의 주인이 하나여야
 * 스티어링휠·미디어 센터·음성 검색이 같은 곡을 다루기 때문이다.
 */
class ChajakMediaService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private var librarySession: MediaLibraryService.MediaLibrarySession? = null
    private val io = Executors.newSingleThreadExecutor()

    /** 서버 주소. Activity 가 prefs 에 넣어 둔다(앱 화면이 없어도 미디어 센터에서 쓸 수 있게). */
    @Volatile private var base: String = ""

    /** 서버에서 읽어 온 내 노래(트랙·완성곡 포함). 메모리에만 들고 있다. */
    @Volatile private var songs: List<JSONObject> = emptyList()

    /** 라이브러리를 마지막으로 읽은 때(ms). 미디어 센터가 다시 볼 때 오래됐으면 새로 읽는다. */
    @Volatile private var loadedAt = 0L

    /** 마지막으로 재생한 항목. 앱이 다시 열렸을 때 이어 듣기용. */
    @Volatile private var lastItem: MediaItem? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (item != null) lastItem = item
            }
        })
        librarySession = MediaLibraryService.MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
        // Started 상태로 둔다. bind()/unbind() 만 쓰면 onTaskRemoved 를 못 받아 재생이 끊긴다.
        try {
            startService(Intent(this, ChajakMediaService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "서비스를 Started 로 유지하지 못함: ${e.message}")
        }
        io.execute { refreshLibrary() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? = librarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 가이드: 앱을 밀어내면 재생을 정리한다.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        librarySession?.release()
        librarySession = null
        player.release()
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 라이브러리

    /** 서버에서 내 노래를 읽는다. 곡마다 상세를 한 번 더 받아 트랙 목록까지 채운다. */
    private fun refreshLibrary() {
        loadedAt = System.currentTimeMillis()
        try {
            base = getSharedPreferences("chajak", MODE_PRIVATE).getString("server", "") ?: ""
            if (base.isEmpty()) return
            val api = ChajakApi(base)
            val arr = api.songs(LIBRARY_SIZE).getJSONArray("songs")
            val list = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val id = s.optString("id")
                // 목록의 tracks 는 개수(숫자)라 상세(트랙·완성곡)가 없다 — 배열이 아니면 상세를 받는다
                if (id.isEmpty() || s.optJSONArray("tracks") != null) s else try { api.song(id) } catch (e: Exception) { s }
            }
            songs = list
            librarySession?.notifyChildrenChanged("root", list.size, null)
        } catch (e: Exception) {
            Log.w(TAG, "라이브러리를 못 읽음: ${e.message}")
        }
    }

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId("root")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("차작").setArtist("차작").setIsBrowsable(true).setIsPlayable(false).build())
        .build()

    /** 미디어 브라우저가 요구한 항목의 하위(곡 → 트랙·완성된 노래). */
    /**
     * 차 미디어 센터 목록: 맨 위 = 드라이브 앨범(완성곡을 만든 때로 묶음 — 퇴근길 앨범·밤 드라이브 앨범…) + "내 멜로디".
     * 앨범 안 = 완성곡(바로 재생, 스티어링휠 다음/이전으로 넘김), 내 멜로디 안 = 곡 → 트랙·완성곡.
     */
    private fun childrenOf(parentId: String): ImmutableList<MediaItem> {
        if (parentId == "root") {
            return ImmutableList.copyOf(albums().keys.map { folder("album:$it", "$it 앨범") } + folder(MELODIES, "내 멜로디"))
        }
        if (parentId.startsWith("album:")) {
            return ImmutableList.copyOf(albums()[parentId.removePrefix("album:")].orEmpty().mapNotNull(::finalItem))
        }
        if (parentId == MELODIES) {
            return ImmutableList.copyOf(songs.mapIndexed { i, s -> songItem(s, i) })
        }
        if (parentId.startsWith("song:")) {
            val id = parentId.removePrefix("song:")
            val s = songs.firstOrNull { it.optString("id") == id } ?: return ImmutableList.of()
            return ImmutableList.copyOf(childrenOfSong(s))
        }
        return ImmutableList.of()
    }

    /** 완성곡이 있는 곡을 드라이브 때(DriveTheme)로 묶는다. */
    private fun albums(): Map<String, List<JSONObject>> =
        songs.filter { it.optJSONObject("final") != null }.groupBy { DriveTheme.momentOf(it.optString("created")) }

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("차작").setIsBrowsable(true).setIsPlayable(false).build())
        .build()

    /** 앨범 안의 완성곡(가장 최근 버전). mediaId 는 곡 안 항목과 같아 findItem·앱 화면 key 로 그대로 통한다. */
    private fun finalItem(s: JSONObject): MediaItem? {
        val f = s.optJSONObject("final") ?: return null
        return playable("song:${s.optString("id")}:final:${f.optString("id")}", f.optString("title").ifEmpty { "완성된 노래" },
            f.optString("audio_url"), "${DriveTheme.momentOf(s.optString("created"))} 앨범", f.optString("cover_url"))
    }

    private fun songItem(s: JSONObject, index: Int): MediaItem {
        val id = s.optString("id")
        val title = songTitle(s, index)
        val b = MediaItem.Builder().setMediaId("song:$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title).setArtist("차작").setAlbumTitle("차작 멜로디")
                    .setIsBrowsable(true).setIsPlayable(true).build())
        s.optString("audio_url").takeIf { it.isNotEmpty() }?.let { b.setUri(Uri.parse(serverUrl(it, 0))) }
        return b.build()
    }

    /** 한 곡 안의 트랙(내 멜로디)과 완성된 노래를 순서대로 돌려준다. */
    private fun childrenOfSong(s: JSONObject): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val tracks = s.optJSONArray("tracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val t = tracks.getJSONObject(i)
                out += playable("song:${s.optString("id")}:track:${i + 1}", t.optString("title").ifEmpty { "트랙 ${i + 1}" },
                    t.optString("audio_url"), songTitle(s, 0), "")
            }
        }
        finalsOf(s).forEach { f ->
            out += playable("song:${s.optString("id")}:final:${f.optString("id")}", f.optString("title").ifEmpty { "완성된 노래" },
                f.optString("audio_url"), songTitle(s, 0), f.optString("cover_url"))
        }
        return out
    }

    private fun playable(mediaId: String, title: String, path: String, album: String, cover: String): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title).setArtist("차작").setAlbumTitle(album)
            .setIsPlayable(true)
        // 차 화면의 앨범 그림은 작게(서버가 줄인 JPEG) — 원본 PNG 1.4MB 를 받을 필요가 없다
        if (cover.isNotEmpty()) serverUrl(cover, 0).let { meta.setArtworkUri(Uri.parse(it + (if ('?' in it) "&" else "?") + "size=512")) }
        val b = MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(meta.build())
        if (path.isNotEmpty()) b.setUri(Uri.parse(serverUrl(path, 0)))
        return b.build()
    }

    private fun finalsOf(s: JSONObject): List<JSONObject> {
        val arr = s.optJSONArray("finals")
        return if (arr != null) (0 until arr.length()).map { arr.getJSONObject(it) } else listOfNotNull(s.optJSONObject("final"))
    }

    private fun songTitle(s: JSONObject, index: Int): String {
        s.optString("title").takeIf { it.isNotEmpty() }?.let { return it }
        s.optString("prompt").takeIf { it.isNotEmpty() }?.let { return it.take(24) }
        return dateTitle(s.optString("created")) ?: "멜로디 ${index + 1}"
    }

    /** 서버 주소 + 캐시 깨기(트랙이 쌓이면 같은 주소가 캐시에 남는다). */
    private fun serverUrl(path: String, tracks: Int): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return ChajakApi.withToken(path)
        val query = if ("?" in path) path else "$path?v=$tracks"
        return ChajakApi.withToken(base + query)  // 차량 미디어 센터는 헤더를 못 붙여 주소에 토큰을 싣는다
    }

    // ------------------------------------------------------------------ 세션 콜백

    private inner class LibraryCallback : MediaLibraryService.MediaLibrarySession.Callback {

        /**
         * 핸들(스티어링휠) 재생·일시정지 키는 앱 화면이 아니라 이 미디어 세션으로 온다(2026-11-14 에뮬레이터 확인:
         * 'Media button session is ai.sonon.chajak'). 녹음·카운트다운·대화 녹음 알림 화면에서는 화면이 키를 쓰게 넘긴다.
         */
        @OptIn(UnstableApi::class)
        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val ev = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (ev != null && mediaKeyHook?.invoke(ev) == true) return true
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            // 새로 만든 곡·바뀐 서버 주소가 미디어 센터에 보이게: 30초 넘게 지났거나 비어 있으면 다시 읽는다(끝나면 notifyChildrenChanged)
            if (parentId == "root" && (songs.isEmpty() || System.currentTimeMillis() - loadedAt > REFRESH_MS)) io.execute { refreshLibrary() }
            return Futures.immediateFuture(LibraryResult.ofItemList(childrenOf(parentId), params))
        }

        override fun onGetItem(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = findItem(mediaId)
            return if (item == null) Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            else Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        /** 음성 검색("내 멜로디 2번째", "드라이브 노래") — 검색 결과는 onGetSearchResult 로 준다. */
        override fun onSearch(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> =
            Futures.immediateFuture(LibraryResult.ofVoid(params))

        override fun onGetSearchResult(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val q = query.trim().lowercase()
            val out = mutableListOf<MediaItem>()
            if (q.isNotEmpty()) {
                songs.forEach { s ->
                    childrenOfSong(s).forEach { item ->
                        val t = item.mediaMetadata.title?.toString()?.lowercase() ?: ""
                        if (t.contains(q)) out += item
                    }
                }
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(out), params))
        }

        /** 미디어 브라우저에서 항목만 골라 재생할 때 실제 주소를 채워 준다. */
        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val filled = mediaItems.map { item ->
                if (item.localConfiguration != null) item
                else findItem(item.mediaId)?.let { found ->
                    MediaItem.Builder().setMediaId(item.mediaId)
                        .setUri(found.localConfiguration?.uri)
                        .setMediaMetadata(found.mediaMetadata)
                        .build()
                } ?: item
            }
            return Futures.immediateFuture(filled)
        }

        /** 앱을 다시 열었을 때 마지막 곡을 이어 듣는다(시스템 재개 요청). */
        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val item = lastItem
            return if (item == null) Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
            else Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L))
        }
    }

    private fun findItem(mediaId: String): MediaItem? {
        if (mediaId == "root") return rootItem()
        if (mediaId == MELODIES) return folder(MELODIES, "내 멜로디")
        if (mediaId.startsWith("album:")) return folder(mediaId, "${mediaId.removePrefix("album:")} 앨범")
        if (!mediaId.startsWith("song:")) return null
        val rest = mediaId.removePrefix("song:")
        val id = rest.substringBefore(':')
        val s = songs.firstOrNull { it.optString("id") == id } ?: return null
        return childrenOfSong(s).firstOrNull { it.mediaId == mediaId }
    }

    companion object {
        /** 화면(MainActivity)이 녹음 중 핸들 키를 가져가는 고리. true = 화면이 썼다(재생 명령으로 넘기지 않음). */
        @Volatile @JvmStatic var mediaKeyHook: ((android.view.KeyEvent) -> Boolean)? = null
        private const val TAG = "ChajakMedia"
        private const val LIBRARY_SIZE = 20
        private const val REFRESH_MS = 30_000L
        private const val MELODIES = "melodies"
    }
}
