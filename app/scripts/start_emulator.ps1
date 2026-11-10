# 차작 시험용 Pleos 에뮬레이터 켜기 — PC 마이크 연결까지 한 번에.
#
# 마이크가 무음으로 들어오던 원인(2026-11-11):
#  1) -allow-host-audio 없이 켜면 PC 마이크가 연결되지 않는다(부팅 뒤 'adb emu avd hostmicon' 도 필요).
#  2) Claude 데스크톱 앱(MSIX) 안에서 켠 에뮬레이터는 그 앱의 권한을 물려받는데, 그 앱에는 마이크 권한이 없다
#     → Windows 가 마이크를 열어 주지 않아 늘 무음. 그래서 start_emulator.bat 을 탐색기(explorer)에서 실행한다.
#  3) Pleos 시스템 이미지는 Claude 앱의 가상화 폴더(...\Packages\Claude_*\LocalCache\Local\Android\Sdk)에 깔려 있어
#     탐색기 쪽에서는 보통 SDK 경로에 없다 → 이미지가 있는 SDK 를 찾아 쓴다.
# 쓰는 법: scripts\start_emulator.bat 더블클릭(앱까지 띄움). Claude 에서는 explorer.exe 로 그 .bat 을 연다.
param([switch]$App)

$env:ANDROID_ADB_SERVER_PORT = "5038"
$image = "system-images\android-34\pleos_car_emulator\x86_64"
$cands = @("$env:LOCALAPPDATA\Android\Sdk") + @(Get-ChildItem "$env:LOCALAPPDATA\Packages" -Directory -Filter "Claude_*" -ErrorAction SilentlyContinue |
           ForEach-Object { Join-Path $_.FullName "LocalCache\Local\Android\Sdk" })
$sdk = $cands | Where-Object { Test-Path (Join-Path $_ $image) } | Select-Object -First 1
if (-not $sdk) { "Pleos 시스템 이미지를 찾지 못했어요: $($cands -join ', ')"; Read-Host "엔터를 누르면 닫혀요"; exit 1 }
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME = $sdk
$adb = "$sdk\platform-tools\adb.exe"
$dev = "emulator-5554"
"SDK: $sdk"

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
