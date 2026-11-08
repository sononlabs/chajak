package ai.sonon.chajak

import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 녹음 장치 고르기: 차가 알려 주는 입력 장치(차량 마이크·뒷좌석 마이크·블루투스·USB·유선) 중 하나.
 * 고른 장치는 type/주소/이름으로 기억한다(다시 연결하면 id 가 바뀌므로). 없으면 기본(시스템이 고르는 마이크).
 * 폰 마이크: 차가 블루투스 핸즈프리(HFP HF) 쪽이라 보통은 입력 장치로 안 보인다 — 폰이 USB 오디오나 SCO 로 보이면 목록에 뜬다.
 */
object InputDevices {

    /** 녹음에 쓸 만한 입력(에코 기준·원격 믹스·라디오·내부 버스는 뺀다). */
    val USABLE = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_TELEPHONY,
        26 /* TYPE_BLE_HEADSET (API 31) */,
    )

    fun list(audio: AudioManager): List<AudioDeviceInfo> =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in USABLE }
            .distinctBy { key(it) }

    fun key(d: AudioDeviceInfo) = "${d.type}|${d.address}|${d.productName}"

    fun label(d: AudioDeviceInfo): String {
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() && !it.contains("emulator", true) && it != "Built-In Mic" }
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> if (d.address.contains("back", true)) "뒷좌석 마이크" else "차량 마이크"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "블루투스 마이크"
            26 -> "블루투스(LE) 마이크"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "유선 헤드셋 마이크"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 마이크"
            AudioDeviceInfo.TYPE_TELEPHONY -> "통화 입력"
            else -> "외부 입력"
        }
        return if (name != null) "$kind ($name)" else kind + d.address.takeIf { it.isNotBlank() && d.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { " ($it)" }.orEmpty()
    }

    /** 고른 장치(지금 연결돼 있으면). 없거나 빠졌으면 null = 기본 마이크. */
    fun chosen(audio: AudioManager, prefs: SharedPreferences): AudioDeviceInfo? {
        val k = prefs.getString(PREF, null) ?: return null
        return list(audio).firstOrNull { key(it) == k }
    }

    fun choose(prefs: SharedPreferences, d: AudioDeviceInfo?) {
        prefs.edit().apply { if (d == null) remove(PREF) else putString(PREF, key(d)) }.apply()
    }

    private const val PREF = "input_device"
}
