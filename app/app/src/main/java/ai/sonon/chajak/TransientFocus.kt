package ai.sonon.chajak

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player

/**
 * 짧게 들어 보는 소리(녹음·내 멜로디 트랙·전체 듣기)용 오디오 포커스.
 *
 * 듣던 차 음악(폰 블루투스·멜론 등)을 끝내 버리지 않도록 영구(GAIN)가 아니라 잠깐(GAIN_TRANSIENT)
 * 가져가고, 다 듣거나 멈추면 돌려줘서 그 음악이 이어지게 한다(2026-11-05 사용자 결정 "짧은 소리만 이어지게").
 * 이 소리는 미디어 세션 밖에서 튼다 — 세션이 재생 중이 되면 차(CarMediaService)가 미디어 소스를 바꾸며
 * 이전 소스(블루투스)에 정지를 보내 버려서, 포커스를 돌려줘도 폰 음악이 다시 안 나왔다.
 *
 * 플레이어의 재생 의사(playWhenReady + 준비/버퍼링)를 따라 포커스를 쥐고 놓는다.
 * 잠깐 잃으면(안내·통화) 멈췄다 이어 틀고, 줄이라면 20% 볼륨, 아주 잃으면 멈춘다.
 */
class TransientFocus(private val audio: AudioManager, private val player: Player) : Player.Listener {

    private var held = false
    /** 잠깐 잃어서 멈춘 경우. 돌려받으면 이어 튼다. 그동안은 포커스를 쥔 채로 둔다. */
    private var resumeOnGain = false
    private val main = Handler(Looper.getMainLooper())
    /** 트랙에서 트랙으로 바로 넘어갈 때 포커스를 놓았다 잡으면 폰 음악이 잠깐 튄다 — 조금 기다렸다 놓는다. */
    private val releaseLater = Runnable { release() }

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change ->
            Log.i(TAG, "포커스 변화 $change")
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    player.volume = 1f
                    if (resumeOnGain) { resumeOnGain = false; player.play() }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.volume = 0.2f
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnGain = player.playWhenReady
                    player.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnGain = false
                    player.pause()
                    release()
                }
            }
        }
        .build()

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = update()
    override fun onPlaybackStateChanged(state: Int) = update()

    private fun update() {
        val wants = player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (wants) main.removeCallbacks(releaseLater)
        when {
            wants && !held -> {
                if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    held = true
                    Log.i(TAG, "포커스 잠깐 가져옴")
                } else {
                    // 통화 중 등 — 강제로 틀지 않는다
                    Log.i(TAG, "포커스 거절 — 재생 안 함")
                    player.pause()
                }
            }
            !wants && held && !resumeOnGain -> { main.removeCallbacks(releaseLater); main.postDelayed(releaseLater, RELEASE_DELAY_MS) }
        }
    }

    fun release() {
        main.removeCallbacks(releaseLater)
        resumeOnGain = false
        if (!held) return
        audio.abandonAudioFocusRequest(request)
        held = false
        player.volume = 1f
        Log.i(TAG, "포커스 돌려줌")
    }

    companion object {
        private const val TAG = "ChajakPreview"
        private const val RELEASE_DELAY_MS = 400L
    }
}
