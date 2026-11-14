# CHAJAK | 차작 (차구석 작곡가)

![banner](./docs/banner.jpeg)

**운전 중 흥얼거린 멜로디를 가사와 앨범 표지가 있는 나만의 노래로 만드는 Pleos Connect 차량용 앱.**

2026 [현대자동차그룹 차량용 앱 개발 공모전](https://appdevchallenge.auto-hmg.io/guide/overview) 출품작

![Android](https://img.shields.io/badge/Android-API%2028%2B-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/Kotlin-JDK%2017-7F52FF?logo=kotlin&logoColor=white) ![Pleos Connect](https://img.shields.io/badge/Pleos%20Connect-Music%20%26%20Audio-333333)

## 심사용 실행 정보

| 항목 | 내용 |
| --- | --- |
| 앱 이름 | **차작 (차구석 작곡가)** |
| 설치 APK | [`app/apk/chajak-0.4.11-drive.apk`](app/apk/chajak-0.4.11-drive.apk) |
| Pleos Connect 에뮬레이터 | **v2.1.0**, Android API 34 시스템 이미지 |
| 화면 규격 | **2560 × 1440 px**, **160 dpi** (`adb shell wm size` / `adb shell wm density`) |
| 기본 서버 | `http://10.0.2.2:8790` (에뮬레이터에서 호스트 PC 접속) |

> APK는 저장소에 포함되어 있습니다. 멜로디·노래 생성에는 별도 서버 실행이 필요합니다.

## 실행 방법

1. [Pleos Connect Emulator 설치 및 실행 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)에 따라 Android Studio에서 에뮬레이터를 구성하고 실행합니다. 호스트 PC에 마이크를 연결한 뒤 에뮬레이터의 **Virtual microphone uses host audio input**을 켭니다.
2. ComfyUI `custom_nodes`에 [`comfyui-node/pleos_melody_instrument`](comfyui-node/pleos_melody_instrument)를 설치하고 SheetSage2·YuE2·표지 생성 모델을 준비합니다. Ollama에는 `gemma3:12b`를 준비합니다.
3. 별도 터미널에서 ComfyUI와 차작 서버를 차례로 실행합니다. `<ComfyUI 경로>`와 Python 실행 파일은 로컬 설치에 맞게 바꿉니다.

   ```bash
   cd '<ComfyUI 경로>'
   python main.py --listen 127.0.0.1 --port 8188 --disable-all-custom-nodes --whitelist-custom-nodes pleos_melody_instrument
   ```

   ```bash
   cd server
   python server.py --port 8790
   ```

4. 저장소 루트에서 APK를 설치하고 실행합니다.

   ```bash
   adb install -r --user 10 app/apk/chajak-0.4.11-drive.apk
   adb shell am start --user 10 -n ai.sonon.chajak/.MainActivity
   ```

5. 앱의 **⚙ 설정 → 서버 연결**에서 주소를 확인하고 **연결 확인 후 저장**합니다. 같은 PC의 서버는 기본 주소를 사용하고, 다른 PC의 서버는 접근 가능한 주소로 변경합니다.

앱을 직접 빌드할 때는 JDK 17과 Android SDK Platform 36을 준비합니다. 프로젝트는 Gradle Wrapper 9.1.0과 Android Gradle Plugin 9.0.1을 사용합니다.

```bash
cd app
./gradlew assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk
```

## 테스트 환경 및 필수 설정

| 항목 | 요구 사항 및 확인 상태 |
| --- | --- |
| Android | 최소 API 28, 빌드 SDK 36. 테스트 에뮬레이터: **Pleos Connect v2.1.0**, Android API 34 시스템 이미지 |
| JDK·빌드 | JDK 17, Gradle 9.1.0, Android Gradle Plugin 9.0.1 |
| 권한 | 녹음 시 `RECORD_AUDIO` 허용. 재생 음원 캡처는 별도 사용자 동의가 필요합니다. 미디어 재생·마이크 서비스와 알림 권한은 매니페스트에 선언되어 있습니다. |
| 서버 의존성 | Python 서버의 `aiohttp`, `numpy`, `soundfile` 등과 ComfyUI 생성 노드·모델, Ollama `gemma3:12b`가 필요합니다. **실제 설치 버전과 GPU 구성은 확인 필요** |
| API 키·토큰 | 로컬 기본 실행에 외부 API 키는 필요하지 않습니다. 공유 서버 인증 시 서버의 `CHAJAK_TOKEN` 또는 `~/.chajak/token`과 앱 빌드 옵션 `-PchajakToken=...`에 같은 토큰을 지정합니다. |
| 화면 | 에뮬레이터 측정값: **2560 × 1440 px, 160 dpi** (`adb shell wm size`, `adb shell wm density`) |

원격 서버 주소를 APK에 포함하려면 `app/`에서 `./gradlew assembleDebug -PchajakServer=https://example.dev`로 빌드합니다. 앱 설정 화면에서도 서버 주소를 변경할 수 있습니다.

## 사용자 흐름

![차작 사용자 흐름: 흥얼거리기 → 내 멜로디 → 노래 만들기 → 완성](docs/user-flow.svg)

## 시스템 구성

![차작 시스템 구성: Pleos Connect 앱, API 서버, ComfyUI, Ollama, 저장소](docs/architecture.svg)

앱은 `CarUxRestrictions`에 따라 주행 중 녹음과 글자 입력을 제한합니다. Media3 `MediaLibraryService`로 차량 미디어 센터·스티어링휠 제어·검색을 연결하고, Audio Focus로 기존 음악 재생과 앱 소리를 조정합니다. 주요 API는 `GET /health`, `POST /v1/songs`, `POST /v1/songs/{id}/final`, `GET /v1/jobs/{job_id}`입니다. 생성된 곡은 `server/storage/`에 저장됩니다.

| 경로 | 역할 |
| --- | --- |
| [`app/`](app/) | Android 앱 소스, Gradle 설정, 설치 APK |
| [`server/`](server/) | aiohttp API, 생성 작업 관리, 음원 저장 |
| [`comfyui-node/pleos_melody_instrument/`](comfyui-node/pleos_melody_instrument/) | ComfyUI 생성 노드 |

## 라이선스 및 데이터

YuE2는 비상업용 **CC BY-NC** 라이선스의 시연용 생성 엔진입니다. 사용자 녹음과 생성 곡은 `server/storage/`에 저장되며 제출 저장소에 포함하지 않습니다.
