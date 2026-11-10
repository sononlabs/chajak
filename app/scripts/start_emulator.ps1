# 차작 시험용 Pleos 에뮬레이터 켜기 — PC 마이크 연결까지 한 번에.
# 2026-11-11: -allow-host-audio 없이 켜서 허밍이 계속 무음으로 녹음됐다(11-02 시험에서 이미 찾은 원인).
# PC 마이크가 들어오려면 ① -allow-host-audio 로 켜고 ② 부팅 뒤 'adb emu avd hostmicon' 을 해야 한다(둘 다 필요).
# 쓰는 법: powershell -ExecutionPolicy Bypass -File scripts\start_emulator.ps1  [-App]  (-App: 차작 앱도 띄움)
param([switch]$App)

$env:ANDROID_ADB_SERVER_PORT = "5038"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$dev = "emulator-5554"

if (-not (& $adb devices | Select-String $dev)) {
    Start-Process -FilePath "$sdk\emulator\emulator.exe" -ArgumentList "-avd", "Pleos_Connect", "-no-snapshot-save", "-no-boot-anim", "-allow-host-audio" -WindowStyle Normal
    "에뮬레이터를 켜는 중(PC 마이크 허용)…"
}
& $adb -s $dev wait-for-device
for ($i = 0; $i -lt 100; $i++) {
    if ((& $adb -s $dev shell getprop sys.boot_completed 2>$null) -match "1") { break }
    Start-Sleep 3
}
& $adb -s $dev emu avd hostmicon | Out-Null
"부팅 완료 · PC 마이크 연결 켬(hostmicon)"
"Windows 기본 녹음 장치로 들어온 소리가 에뮬레이터 마이크가 됩니다. 소리가 안 들어오면 Windows 설정 > 소리 > 입력에서 기본 장치를 확인하세요."
if ($App) {
    Start-Sleep 15  # 런처(차량 정보 확인 중)가 끝나기를 기다린다
    & $adb -s $dev shell am start --user 10 -n ai.sonon.chajak/.MainActivity | Out-Null
    "차작 앱을 띄웠어요."
}
