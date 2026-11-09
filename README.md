# 차구석 작곡가 (차작)

운전하며 흥얼거린 한 소절을, 오늘 드라이브를 주제로 한 2분짜리 내 노래(가사·앨범 표지 포함)로 만들어 주는 Pleos Connect 차량용 작곡 앱.
2026 현대자동차·기아 Pleos Playground 차량용 앱 개발 공모전 출품작.

## 흐름 — 4단계

**흥얼거리기 → 내 멜로디 → 노래 만들기 → 완성**

1. **흥얼거리기** — 큰 버튼 하나(녹음 시작 → 3·2·1 → 녹음 완료/취소, 스티어링휠 재생·일시정지 키로도 완료). 듣던 음악(폰 블루투스 포함)은 녹음할 때만 멈췄다 이어진다. 지금 내는 음이 음역 막대·파형에 보이고 내 음역대가 쌓인다.
2. **내 멜로디** — 허밍을 음표로 바꿔 2~4초 만에 연주. 원본 ↔ 변환 비교, AI 제안, 다듬기, 더 만들기(악기·겹쳐 부르기·변주).
3. **노래 만들기** — 주제는 오늘 드라이브로 미리 채워진다(🎲·🎤로 바꾸기). "노래 만들기" 한 번으로 2분 곡 구성·가사·표지·노래(약 3분).
4. **완성** — 표지·가사(지금 줄 강조)·버전, AI 제안, 말로 바꾸기("더 신나게, 멜로디는 살려줘").

## 폴더

| 폴더 | 내용 |
|---|---|
| `app/` | Android(Kotlin) 차량 앱 — 설명은 [app/README.md](app/README.md) |
| `app/apk/` | 빌드해 둔 APK. 기본 서버 = 같은 PC `10.0.2.2:8790`, 앱 안에서 바꿀 수 있음 |
| `server/` | 차작 서버(aiohttp) — API 는 [server/README.md](server/README.md) |
| `comfyui-node/pleos_melody_instrument/` | ComfyUI 노드: 허밍 정리·악보·사운드폰트 연주·변주·2분 곡 구성 |

## 실행 순서

1. ComfyUI 에 `comfyui-node/pleos_melody_instrument` 를 `custom_nodes` 로 넣고 켠다(SheetSage2·YuE2·Qwen-Image-2512(+Lightning 4단계 LoRA) 모델 필요. Z-Image Turbo 로 바꾸려면 `CHAJAK_COVER_MODEL=zimage`).
2. Ollama 에 `gemma3:12b` 를 받아 둔다(가사·말로 바꾸기).
3. `server/` 에서 `python server.py --port 8790`.
4. Pleos Connect 에뮬레이터에 `app/apk/*.apk` 설치(`adb install -r --user 10 …`).

## 협업자 시험 — 앱만(GPU 없이)

노래를 만드는 서버(ComfyUI·GPU)는 서버 PC 한 대에서만 돌린다. 협업자는 앱만 설치해 그 서버에 붙는다.

1. Android Studio 로 **Pleos Connect 에뮬레이터**를 만든다(Pleos Playground 안내대로).
2. `app/apk/chajak-0.4.8-drive.apk` 설치:
   ```bash
   adb install -r --user 10 app/apk/chajak-0.4.8-drive.apk
   ```
3. 앱 왼쪽 아래 **⚙ 설정** 탭 → **서버 연결**에 서버 PC 가 알려 준 주소(예: `https://….trycloudflare.com`)를 넣고 **연결 확인 후 저장**. "● 연결됐어요"가 뜨면 된다.
4. 녹음하려면 PC 에 마이크가 있어야 하고, 에뮬레이터 설정 → Microphone 의 **"Virtual microphone uses host audio input"** 을 켠다. 소리가 전혀 안 들어오면 앱이 "마이크에서 소리가 전혀 들어오지 않아요"라고 알려 준다.

## 라이선스·권리

- 노래 엔진 **YuE2 는 비상업(CC BY-NC)** — 공모전 시연용. 상용은 다른 엔진으로 바꾼다.
- 시연·시험 음악은 모두 이 앱으로 만든 것. 사용자 녹음·곡(`server/storage`)은 저장소에 넣지 않는다.
- 이 저장소는 작업 폴더에서 `sync.sh` 로 가져온 사본이다.
