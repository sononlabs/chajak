package ai.sonon.chajak

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 차량 마이크로 허밍을 녹음해 WAV(48kHz 모노 16bit)로 쓴다.
 * 21번 시험에서 MIC 소스 · 48kHz 가 에뮬레이터 차량 마이크로 정상 녹음됐다.
 */
class HumRecorder(private val rate: Int = 48000) {

    @Volatile private var running = false

    /** 녹음 중 화면을 떠나거나 주행 규제가 켜지면 즉시 멈춘다(21번 시험 결론 2). */
    fun stop() {
        running = false
    }

    /**
     * 블로킹. onFrame(dBFS, 음높이 MIDI 또는 null) 은 약 20번/초. 반환: 실제 녹음 초.
     * source 를 주면 마이크 대신 그 AudioRecord 로 녹음한다(재생 중인 음악 직접 녹음 — 같은 48kHz 모노 16bit).
     */
    @SuppressLint("MissingPermission")
    fun record(out: File, maxSeconds: Int, shouldContinue: () -> Boolean = { true },
               source: ((format: AudioFormat, bufferBytes: Int) -> AudioRecord)? = null,
               device: android.media.AudioDeviceInfo? = null,
               onFrame: (Double, Float?) -> Unit): Double {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuf > 0) { "Unsupported microphone format: $minBuf" }
        val rec = source?.invoke(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                     .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(), minBuf * 4)
            ?: AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("Microphone initialization failed")
        }
        // 고른 녹음 장치(차량 마이크·뒷좌석·블루투스·USB). 시스템이 못 따르면 기본 마이크로 녹음된다
        if (source == null && device != null && !rec.setPreferredDevice(device)) android.util.Log.w("HumRecorder", "녹음 장치 지정 실패: ${device.productName}")
        val pcm = ShortArray(rate * maxSeconds)
        val chunk = rate / 20
        var read = 0
        running = true
        try {
            if (!shouldContinue()) return 0.0
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            val deadline = android.os.SystemClock.elapsedRealtime() + maxSeconds * 1000L
            while (running && shouldContinue() && read < pcm.size && android.os.SystemClock.elapsedRealtime() < deadline) {
                val n = rec.read(pcm, read, minOf(chunk, pcm.size - read))
                check(n >= 0) { "Microphone read failed: $n" }
                if (n == 0) continue
                var sum = 0.0
                for (i in read until read + n) sum += pcm[i].toDouble() * pcm[i]
                val rms = sqrt(sum / n)
                onFrame(if (rms > 0) 20 * log10(rms / 32768.0) else -90.0, pitch(pcm, read, n))
                read += n
            }
        } finally {
            running = false
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            } finally {
                rec.release()
            }
        }
        writeWav(out, pcm, read)
        return read.toDouble() / rate
    }

    /**
     * 녹음 화면에 그릴 대략의 음높이(MIDI). 4배 줄여(12kHz) YIN 방식으로 70~1000Hz 에서 찾는다.
     * 음높이가 뚜렷하지 않으면(말소리·잡음·무음) null. 서버 악보 추출과는 별개로 화면 반응에만 쓴다.
     */
    private fun pitch(pcm: ShortArray, off: Int, n: Int): Float? {
        val sr = rate / 4f
        val m = n / 4
        val minLag = (sr / 1000).toInt()
        val maxLag = (sr / 70).toInt()
        if (m < maxLag * 2) return null
        val x = FloatArray(m) { i -> (pcm[off + 4 * i] + pcm[off + 4 * i + 1] + pcm[off + 4 * i + 2] + pcm[off + 4 * i + 3]) / 4f }
        val w = m - maxLag
        var running = 0f
        var best = -1
        for (tau in 1..maxLag) {
            var d = 0f
            for (j in 0 until w) { val e = x[j] - x[j + tau]; d += e * e }
            running += d
            val norm = if (running > 0) d * tau / running else 1f
            if (tau >= minLag && norm < 0.15f) { best = tau; break }
        }
        if (best < 0) return null
        return (69 + 12 * ln(sr / best / 440f) / ln(2f))
    }

    private fun writeWav(file: File, pcm: ShortArray, count: Int) {
        val dataBytes = count * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
            for (i in 0 until count) putShort(pcm[i])
        }
        RandomAccessFile(file, "rw").use { it.setLength(0); it.write(buf.array()) }
    }
}
