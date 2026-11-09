package ai.sonon.chajak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.math.log10

/**
 * 대화 녹음(저니맵 02) — 포그라운드 서비스(마이크)로 최대 30분.
 * 화면을 떠나도(내비게이션 등) 계속 녹음하고, 알림에 "녹음 중"이 뜬다. AAC 16kHz 모노 32kbps — 30분이 약 7MB 라
 * 서버 업로드 한도(20MB) 안에 든다(전에는 wav 16kHz 로 10분 19MB).
 */
class TalkRecordService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishRecording()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "대화 녹음", NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("차작 대화 녹음 중")
            .setContentText("최대 30분 · 다 이야기했으면 차작에서 녹음 완료를 누르세요")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 30) startForeground(2, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(2, note)
        val out = File(intent?.getStringExtra(EXTRA_FILE) ?: return START_NOT_STICKY)
        val deviceId = intent.getIntExtra(EXTRA_DEVICE, -1)
        try {
            val r = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(32000)
                setMaxDuration(MAX_MS)
                setOutputFile(out.absolutePath)
                setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishRecording() }
                if (deviceId >= 0) getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.id == deviceId }?.let { setPreferredDevice(it) }
                prepare()
                start()
            }
            recorder = r
            file = out
            startedAt = SystemClock.elapsedRealtime()
            result = null
        } catch (e: Exception) {
            Log.e("TalkRecord", "대화 녹음 시작 실패", e)
            result = Result(null, -1.0)
            done()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun finishRecording() {
        val r = recorder
        if (r == null) { stopSelf(); return }
        val sec = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val ok = try { r.stop(); true } catch (e: Exception) { Log.w("TalkRecord", "녹음 멈추기 실패(너무 짧음?)", e); false }
        r.release()
        recorder = null
        result = Result(if (ok) file else null, if (ok) sec else -1.0)
        done()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    data class Result(val file: File?, val seconds: Double)

    companion object {
        private const val CHANNEL = "talk"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_DEVICE = "device"
        const val MAX_MS = 30 * 60 * 1000

        @Volatile private var recorder: MediaRecorder? = null
        @Volatile private var file: File? = null
        @Volatile private var startedAt = 0L
        /** 끝난 녹음(앱이 가져가면 null 로). */
        @Volatile var result: Result? = null
        /** 녹음이 끝나면 부른다(ui 스레드). */
        var onDone: (() -> Unit)? = null

        val recording: Boolean get() = recorder != null
        fun elapsedMs(): Long = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0L

        /** 지난번 이후 가장 큰 소리(dBFS). */
        fun levelDb(): Double {
            val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            return if (amp <= 0) -90.0 else 20 * log10(amp / 32767.0)
        }

        fun start(ctx: Context, out: File, deviceId: Int?) {
            ctx.startForegroundService(Intent(ctx, TalkRecordService::class.java)
                .putExtra(EXTRA_FILE, out.absolutePath).putExtra(EXTRA_DEVICE, deviceId ?: -1))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TalkRecordService::class.java).setAction(ACTION_STOP))
        }

        private fun done() = Handler(Looper.getMainLooper()).post { onDone?.invoke() }
    }
}
