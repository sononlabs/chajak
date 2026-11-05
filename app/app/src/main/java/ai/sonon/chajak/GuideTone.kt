package ai.sonon.chajak

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** 따라 부르기 연습용 안내 멜로디: 음표를 부드러운 오르간 비슷한 소리로 만들어 바로 튼다(서버 없이). */
object GuideTone {
    /** 차량 출력 버스(bus0_media_out)와 같은 48kHz — 다시 샘플링하지 않게. */
    private const val SR = 48000

    /** 카운트다운 효과음: 3·2·1 은 짧은 '삑'(A5), 시작은 한 옥타브 위로 조금 길게. */
    fun beep(start: Boolean): AudioTrack = play(listOf(NoteRoll.Note(0f, if (start) 0.25f else 0.12f, if (start) 93 else 81)))

    fun play(notes: List<NoteRoll.Note>): AudioTrack {
        val end = notes.maxOf { it.start + it.dur }
        val pcm = ShortArray(((end + 0.3f) * SR).toInt())
        for (n in notes) {
            val f = 440.0 * 2.0.pow((n.midi - 69) / 12.0)
            val s0 = (n.start * SR).toInt()
            val len = (n.dur * SR).toInt()
            for (i in 0 until len) {
                val t = i.toDouble() / SR
                // 앞 15ms 올리고 뒤 80ms 내린다 — 음과 음 사이가 또렷하게 들리도록
                val env = min(1.0, t / 0.015) * min(1.0, (len - i).toDouble() / (0.08 * SR)) * exp(-t * 0.4)
                val v = sin(2 * PI * f * t) + 0.35 * sin(4 * PI * f * t) + 0.12 * sin(6 * PI * f * t)
                pcm[s0 + i] = (v * env * 0.6 * Short.MAX_VALUE / 1.47).toInt().toShort()
            }
        }
        return AudioTrack.Builder()
            // 음성 명령(ASSISTANT) 용도일 때 에뮬레이터에서 안 들렸다(2026-11-05 사용자). 곡 듣기와 같은 미디어 용도로 튼다
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .apply { write(pcm, 0, pcm.size); play() }
    }
}
