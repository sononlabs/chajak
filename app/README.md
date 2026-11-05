# 차구석 작곡가 (차작) — Pleos Connect 앱

운전하며 흥얼거린 한 소절을, 오늘 드라이브를 주제로 한 2분짜리 내 노래(가사·앨범 표지 포함)로 만들어 주는 차량용 작곡 앱.
2026 현대자동차·기아 Pleos Playground 차량용 앱 개발 공모전 출품작.

## 흐름 — 4단계

| 단계 | 화면 | 하는 일 |
|---|---|---|
| 1 흥얼거리기 | 녹음실 | 큰 버튼 → 3·2·1 → 한 소절(5~30초 권장). 듣던 음악은 녹음할 때만 멈췄다가 이어진다. 지금 내는 음이 음역 막대·파형에 보이고 내 음역대가 쌓인다 |
| 2 내 멜로디 | 곡 화면 | 허밍 → 악보 → 피아노 연주(2~4초). 원본 ↔ 변환 비교, AI 제안, 다듬기, 더 만들기(악기·겹쳐 부르기·변주) |
| 3 노래 만들기 | 노래 만들기 | 주제(오늘 드라이브로 미리 채움 · 🎲 · 🎤) → Create music. 2분 곡 구성·가사·표지·노래 약 3분 |
| 4 완성 | 노래 듣기 | 표지·가사(지금 줄 강조)·버전, AI 제안, 말로 바꾸기("더 신나게, 멜로디는 살려줘") |

왼쪽 탭: 녹음실 · 내 노래(드라이브 앨범) · 내 멜로디.

## 차량 기능

- `CarUxRestrictions` — 주행 중 녹음·글자 입력 잠금, 듣기·버튼 한 번 동작은 허용
- Audio Focus — 녹음·짧은 미리 듣기는 `GAIN_TRANSIENT`(듣던 차·폰 블루투스 음악이 끝나면 이어짐), 완성곡은 Media3 세션(`GAIN`, 스티어링휠·미디어 센터)
- Media3 `MediaLibraryService` — 드라이브 앨범·내 멜로디 라이브러리, 음성 검색
- `AudioManager` 입력 장치 선택(차량·뒷좌석·블루투스·USB 마이크), `AudioPlaybackCapture`(듣던 음악에서 따기, 동의 창)

## 빌드·실행

```
./gradlew assembleDebug                                   # 에뮬레이터용(서버 = PC, http://10.0.2.2:8790)
./gradlew assembleDebug -PchajakServer=https://example.dev  # 다른 서버 주소로
adb install -r --user 10 app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user 10 -n ai.sonon.chajak/.MainActivity [--es server http://…]
```

- `apk/` — 빌드해 둔 APK(에뮬레이터용, 서버 = PC)
- Android API 28 이상(Pleos Connect 에뮬레이터 API 34 에서 확인), Kotlin, Gradle 9
- 서버: 별도 저장소 `chajak-server`(aiohttp + ComfyUI + 로컬 LLM). 노래 엔진 YuE2 는 비상업 라이선스라 공모전 시연용

## 코드

| 파일 | 내용 |
|---|---|
| `MainActivity.kt` | 화면 4단계·탭·녹음·재생·주행 규칙 |
| `ChajakMediaService.kt` | Media3 라이브러리(드라이브 앨범)·세션 |
| `TransientFocus.kt` | 짧은 소리 미리 듣기의 잠깐 포커스 |
| `HumRecorder.kt` | 녹음(48kHz 모노)·음높이(YIN) |
| `VoiceRange.kt` · `RangeBar.kt` · `LevelStrip.kt` | 음역대·음역 막대·파형 |
| `Suggest.kt` | AI 제안 규칙 |
| `DriveTheme.kt` | 오늘 드라이브 → 주제·앨범 |
| `InputDevices.kt` · `CaptureService.kt` | 녹음 장치 선택 · 재생 소리 녹음 |
| `ChajakApi.kt` | 서버 API |
