# 차작 블루투스 상대용 가상 폰(BT_Phone) 켜기.
# 이 폰은 PC 마이크가 필요 없으니 Claude 안에서 켜도 상관없다 — 마이크 문제는 Pleos(start_emulator) 쪽 얘기.
$env:ANDROID_ADB_SERVER_PORT = "5038"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME = $sdk
$adb = "$sdk\platform-tools\adb.exe"

Start-Process -FilePath "$sdk\emulator\emulator.exe" -ArgumentList "-avd", "BT_Phone", "-no-snapshot-save", "-no-boot-anim" -WindowStyle Normal
"가상 폰(BT_Phone)을 켜는 중…"
