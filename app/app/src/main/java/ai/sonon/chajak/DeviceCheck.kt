package ai.sonon.chajak

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 녹음 장치 확인(2026-11-08 사용자: "지금 뭐가 인식되는지 모르겠다").
 * - 차가 알려 주는 입력 장치 전부(녹음에 안 쓰는 라디오·내부 버스까지)와 원래 이름·종류·주소.
 * - 장치마다 몇 초 소리 크기를 재서 실제로 소리가 들어오는지, 시스템이 정말 그 장치로 녹음했는지(routedDevice) 보여 준다.
 *   재기만 하고 저장·전송하지 않는다. 사용자가 누를 때만, 화면에 재는 중임을 보이며.
 * - 지금 재생 중인 소리(음악에서 따기의 "재생 소리 직접" 대상이 있는지).
 */
object DeviceCheck {

    /** 에뮬레이터면 차량 마이크·뒷좌석 마이크 모두 PC 마이크로 들어온다. */
    val emulator: Boolean = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
        Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk") || Build.MODEL.contains("Emulator", true)

    fun inputs(audio: AudioManager): List<AudioDeviceInfo> = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    fun typeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "내장 마이크"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "블루투스 통화(SCO)"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선 헤드셋"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 장치"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 헤드셋"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 액세서리"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "아날로그 라인 입력"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "디지털 라인 입력"
        AudioDeviceInfo.TYPE_TELEPHONY -> "통화 회선"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM 라디오 튜너"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV 튜너"
        AudioDeviceInfo.TYPE_BUS -> "차량 오디오 버스"
        25 -> "내부 믹스(원격 서브믹스)"
        26 -> "블루투스 LE 헤드셋"
        28 -> "에코 기준 신호"
        else -> "종류 $t"
    }

    /** 사용자에게 보일 이름 + 원래 이름(productName/address). */
    fun rawName(d: AudioDeviceInfo): String =
        listOfNotNull(d.productName?.toString()?.takeIf { it.isNotBlank() }, d.address.takeIf { it.isNotBlank() && it != d.productName?.toString() })
            .joinToString(" · ").ifEmpty { "이름 없음" }

    /** 차작이 녹음 장치 목록에 올리는 종류인지(라디오·내부 버스·에코 기준은 아님). */
    fun usable(d: AudioDeviceInfo) = d.type in InputDevices.USABLE

    /** 지금 재생 중인 소리(다른 앱 포함, 앱 이름은 시스템이 알려 주지 않는다). */
    fun playing(audio: AudioManager): List<String> = playingRaw(audio).ifEmpty { if (audio.isMusicActive) listOf("음악(블루투스 등)") else emptyList() }

    private fun playingRaw(audio: AudioManager): List<String> = audio.activePlaybackConfigurations.map { c ->
        val a = c.audioAttributes
        when (a.usage) {
            AudioAttributes.USAGE_MEDIA -> "음악·미디어"
            AudioAttributes.USAGE_GAME -> "게임"
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "내비 안내"
            AudioAttributes.USAGE_ASSISTANT -> "음성 비서"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "통화"
            AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "알림·벨소리"
            AudioAttributes.USAGE_UNKNOWN -> "알 수 없음"
            else -> "기타(${a.usage})"
        }
    }

    /** 재생 소리를 직접 담을 대상(음악·게임·알 수 없음)이 지금 있는지. */
    fun capturablePlaying(audio: AudioManager): Boolean = audio.activePlaybackConfigurations.any {
        it.audioAttributes.usage in setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
    }

    /** 소리 재기 결과: 가장 큰 소리(dB), 시스템이 실제로 고른 장치 이름. */
    data class Probe(val maxDb: Double, val routed: String?)

    /**
     * device 로 seconds 동안 소리 크기만 잰다(저장 안 함). null = 기본 마이크.
     * onLevel 은 0.1초마다(작업 스레드에서) 부른다. stop() 이 true 를 돌려주면 멈춘다.
     */
    @SuppressLint("MissingPermission")
    fun probe(device: AudioDeviceInfo?, seconds: Int, stop: () -> Boolean, onLevel: (Double) -> Unit): Probe {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); error("마이크를 열지 못했어요") }
        if (device != null) rec.setPreferredDevice(device)
        val buf = ShortArray(rate / 10)
        var max = -90.0
        try {
            rec.startRecording()
            val end = SystemClock.elapsedRealtime() + seconds * 1000L
            while (!stop() && SystemClock.elapsedRealtime() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val db = 20 * log10(sqrt(sum / n) / Short.MAX_VALUE + 1e-9)
                if (db > max) max = db
                onLevel(db)
            }
            val routed = rec.routedDevice?.let { "${typeName(it.type)} · ${rawName(it)}" }
            return Probe(max, routed)
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }
}
