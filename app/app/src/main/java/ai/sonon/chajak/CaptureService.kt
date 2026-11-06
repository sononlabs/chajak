package ai.sonon.chajak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 재생 중인 음악을 직접 녹음하는 동안(AudioPlaybackCapture) 떠 있는 포그라운드 서비스.
 * Android 14 는 MediaProjection 을 받기 전에 mediaProjection 종류의 포그라운드 서비스가 돌고 있어야 한다.
 * 동의 한 번을 마지막 녹음 뒤 10분 동안 다시 쓴다(그동안 떠 있음). Activity 가 멈춘다.
 */
class CaptureService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "음악 녹음", NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("차작 음악 녹음 준비됨")
            .setContentText("재생 중인 소리만 담아요(화면은 녹화하지 않아요). 10분 동안 안 쓰면 꺼져요.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, note)
        started = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        started = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "capture"
        /** startForeground 가 끝났는지(그 뒤에야 MediaProjection 을 받을 수 있다). */
        @Volatile var started = false
    }
}
