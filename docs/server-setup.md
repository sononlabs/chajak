# 차작 호스트 서버 운영 가이드

이 문서는 **서버 운영자용**입니다. 심사위원은 저장소의 APK를 설치해 앱을 확인합니다. API 서버, ComfyUI, Ollama와 모델은 호스트 서버에서만 실행합니다.

## 1. 호스트 준비

- [ComfyUI](https://github.com/Comfy-Org/ComfyUI)와 호환되는 Python 가상환경을 준비합니다. 이 저장소는 Python 3.13에서 API 실행을 확인했습니다. 노래 생성은 GPU와 충분한 메모리가 필요합니다.
- ComfyUI `custom_nodes`에 [`pleos_melody_instrument`](../comfyui-node/pleos_melody_instrument/)를 복사하거나 링크합니다.
- ComfyUI의 Python 환경에 공식 `requirements.txt`, `tinysoundfont`, 이 저장소의 [`requirements-server.txt`](../requirements-server.txt)를 설치합니다. 다운로드 캐시를 남기지 않으려면 `pip install --no-cache-dir`를 사용합니다. 별도 Python 환경을 쓰면 해당 환경에도 서버 의존성을 설치합니다.

   ```bash
   cd '<ComfyUI 경로>'
   python -m venv .venv
   .venv/bin/python -m pip install --no-cache-dir -r requirements.txt
   .venv/bin/python -m pip install --no-cache-dir --no-deps tinysoundfont
   .venv/bin/python -m pip install --no-cache-dir -r '<차작 저장소 경로>/requirements-server.txt'
   ```

   Windows에서는 `.venv/bin/python` 대신 `.venv\\Scripts\\python.exe`를 사용합니다. 이미 설치된 ComfyUI 환경이 있으면 새 가상환경을 만들 필요가 없습니다.
- 다음 파일을 ComfyUI의 `models/` 아래에 배치합니다. 모델은 **사용할 경로에 필요한 것만** 받습니다.

| 기능 | ComfyUI 경로 | 출처 |
| --- | --- | --- |
| 허밍 전사 | `audio_encoders/sheetsage2_bf16.safetensors` | [Comfy-Org/YuE2](https://huggingface.co/Comfy-Org/YuE2/tree/main/audio_encoders) |
| 악기 연주 | `soundfonts/GeneralUser-GS.sf2` | [GeneralUser-GS](https://github.com/mrbumpy409/GeneralUser-GS) |
| 최종 노래 | `checkpoints/yue2_3b_int8_convrot.safetensors` | [Comfy-Org/YuE2](https://huggingface.co/Comfy-Org/YuE2/tree/main/checkpoints) |
| 기본 표지 | `diffusion_models/Zimage/z_image_turbo_nvfp4.safetensors`, `text_encoders/qwen_3_4b_fp8_mixed.safetensors`, `vae/z_image_ae.safetensors` | [Comfy-Org/Z-Image-Turbo](https://huggingface.co/Comfy-Org/Z-Image-Turbo/tree/main/split_files) |

표지 VAE 원본 파일명은 `ae.safetensors`이므로 위 경로에는 `z_image_ae.safetensors`로 저장합니다. `CHAJAK_COVER_MODEL=qwen2512`를 설정한 경우에만 [서버 코드](../server/comfy.py)의 Qwen-Image 파일명이 추가로 필요합니다.

Ollama는 기본 설정에서 **`gemma3:12b` 하나**를 가사와 AI 멜로디에 함께 사용합니다.

```bash
ollama pull gemma3:12b
ollama list
```

디스크가 작은 호스트에서는 `CHAJAK_LYRICS_MODEL`로 더 작은 모델을 지정할 수 있습니다. 1B 모델은 시험에서 가사 품질이 낮고 AI 멜로디가 규칙 기반 결과로 돌아가므로 심사용으로 권장하지 않습니다. `CHAJAK_MELODY_MODEL`을 따로 지정할 때만 두 번째 Ollama 모델을 받습니다. 대화 받아쓰기 기능은 별도 `faster-whisper` 패키지와 `CHAJAK_STT_MODEL`의 로컬 모델 파일이 필요합니다.

## 2. 실행

터미널을 나눠 Ollama, ComfyUI, 차작 API 순서로 시작합니다. 아래 Python은 ComfyUI 의존성이 설치된 환경의 실행 파일을 뜻합니다.

```bash
ollama serve
```

```bash
cd '<ComfyUI 경로>'
.venv/bin/python main.py --listen 127.0.0.1 --port 8188 --disable-all-custom-nodes --whitelist-custom-nodes pleos_melody_instrument
```

```bash
cd '<차작 저장소 경로>'
'<ComfyUI 경로>/.venv/bin/python' server/server.py --port 8790
```

Windows에서는 두 실행 명령의 `.venv/bin/python`을 `.venv\\Scripts\\python.exe`로 바꿉니다. ComfyUI와 Ollama가 다른 주소에 있으면 서버의 `--comfy http://HOST:8188`와 `CHAJAK_OLLAMA=http://HOST:11434`를 설정합니다. 노드 경로를 자동으로 찾지 못하면 `CHAJAK_NODES`에 `pleos_melody_instrument` 폴더를 지정합니다. 생성 곡과 녹음은 `server/storage/`에 저장됩니다.

## 3. 확인과 심사용 연결

```bash
curl http://127.0.0.1:8790/health
'<ComfyUI 경로>/.venv/bin/python' tests/test_api.py --hum /path/to/real-hum.wav
```

`/health`의 `ok: true`는 API와 차작 ComfyUI 노드 연결을 뜻합니다. 실제 생성 확인에는 **5초 이상의 허밍 녹음**으로 `tests/test_api.py`를 실행합니다. 서버 없이 API·저장 경로만 점검하려면 같은 Python으로 `tests/test_e2e_mock.py`를 실행합니다.

제출 APK의 기본 주소 `http://10.0.2.2:8790`은 **Pleos Connect 에뮬레이터를 실행하는 호스트 PC의 서버**를 가리킵니다. 위 명령으로 그 PC의 `127.0.0.1:8790`에서 API를 실행하면 APK 주소를 바꾸거나 다시 빌드할 필요가 없습니다. 앱 **설정 → 서버 연결**에서 연결 상태를 확인할 수 있습니다.
