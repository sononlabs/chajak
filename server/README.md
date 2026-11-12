# 차작 서버 (chajak-server)

차량 앱이 허밍 녹음을 올리면 음표로 바꿔 연주하고(내 멜로디), 그 한 소절을 2분짜리 노래(가사·앨범 표지·노래)로 만들어 돌려주는 HTTP API입니다. 생성 엔진은 같은 PC에서 도는 ComfyUI(`pleos_melody_instrument` 노드 + SheetSage2 + YuE2 + Z-Image Turbo 표지. `CHAJAK_COVER_MODEL=qwen2512` 로 Qwen-Image-2512 를 쓸 수 있음)와 Ollama 로컬 LLM(`gemma3:12b`, 가사·말로 바꾸기. `CHAJAK_LYRICS_MODEL` 로 바꿀 수 있음)이고, 이 서버는 그 앞에서 요청·저장·믹스·작업 관리를 맡습니다.

> YuE2 는 비상업(CC BY-NC) 라이선스라 공모전 시연용입니다. 상용은 다른 엔진으로 바꿉니다.

## 실행

```powershell
# 1) ComfyUI (생성 엔진) — custom_nodes 에 comfyui-node/pleos_melody_instrument 를 넣는다
cd <ComfyUI 폴더>
venv\Scripts\python.exe main.py --listen 127.0.0.1 --port 8188 --disable-all-custom-nodes --whitelist-custom-nodes pleos_melody_instrument

# 2) 차작 서버 (ComfyUI venv 그대로 사용: aiohttp·numpy·soundfile)
cd server
<ComfyUI 폴더>\venv\Scripts\python.exe server.py --port 8790

# 3) 시험 (실제 허밍으로 곡 만들기·오류·변주 4종, 약 20초. 시험 곡은 끝에 지움)
<ComfyUI 폴더>\venv\Scripts\python.exe test_api.py
```

- 서버는 `127.0.0.1`에만 열립니다. Android 에뮬레이터는 `http://10.0.2.2:8790`으로 접속합니다.
- 곡은 `storage/<곡 id>/`에 저장되어 서버를 다시 켜도 남습니다(`song.json`, 원본 허밍, 트랙·믹스 음원). 작업 상태(`/v1/jobs`)는 `storage/jobs.json` 에 3초마다 저장되고, 다시 켜면 끝나지 않은 작업은 "서버가 다시 켜지면서 멈췄어요" 실패로 남습니다(앱이 이유를 보여 준다).

## 운영 규칙 (2026-11-10 안정성 시험)

- ComfyUI 는 반드시 `--disable-all-custom-nodes --whitelist-custom-nodes pleos_melody_instrument` 로 켠다. `--whitelist-custom-nodes` 만 주면 다른 커스텀 노드가 모두 올라오고, 모델을 VRAM 에 붙잡는 노드(예: HeartMuLa)가 있으면 노래 한 곡이 1시간 넘게 걸린다. 서버는 시작할 때 이를 경고하고 `GET /health` 의 `extra_custom_nodes` 로 보여 준다.
- 이 ComfyUI 에 ComfyUI-Manager 로 노드를 설치하지 않는다(설치 뒤 옵션 없이 다시 켜진다).
- 실측(RTX 5070 Ti 16GB, 에뮬레이터 두 대 켠 채): 노래 약 165초, 랩 약 270초. 다른 GPU 작업(VRAM 4GB+계산)을 함께 하면 250~390초로 느려지지만 끝난다.
- 노래 도중 ComfyUI 가 다시 켜지면 서버가 최대 300초 기다렸다가 작업을 한 번 다시 넣는다.

## 엔진 바꿔 끼우기 · 비교 시험 (2026-11-13)

단계마다 쓰는 모델은 `engines/` 가 고른다. 설정은 `~/.chajak/engines.json`(저장소 밖 — API 키가 들어갈 수 있다)이고, 요청마다 다시 읽어 서버를 다시 켜지 않아도 바뀐다. 파일이 없으면 지금 구성 그대로다.

```json
{"song": "yue2", "cover": "zimage",
 "llm": {"lyrics": "ollama/gemma3:12b", "talk": "ollama/gemma3:12b", "melody": "ollama/qwen3:14b"},
 "keys": {}}
```

| 단계 | 파일 | 지금 있는 엔진 | 새 엔진 더하기 |
|---|---|---|---|
| 노래 | `engines/song.py` | `yue2` | `SongEngine` 을 이어받아 `generate` 하나 + `caps`(멜로디를 받는지 등) |
| 표지 | `engines/cover.py` | `zimage`, `qwen2512` | `CoverEngine.draw` |
| 가사·대화 요약·멜로디 다시 쓰기 | `engines/llm.py` | `ollama/<모델>` | `_BACKENDS` 에 함수 하나 |

- 노래 만들기 요청에 `"engine": "<이름>"` 을 주면 그 엔진으로 만든다(없는 이름은 400). 완성곡 기록 `engines` 에 노래·표지·가사 엔진과 멜로디를 넘겼는지가 남는다. `GET /health` 의 `engines` 로 지금 설정을 본다.
- 멜로디를 받지 않는 엔진(`caps.melody = None`)에는 곡 악보를 넘기지 않는다.
- 비교 시험: `python bench_engines.py --engines yue2,<새 엔진> [--hum a.wav b.wav] [--seconds 60]` — 같은 허밍·같은 가사·같은 표지로 엔진만 바꿔 만들고, 걸린 시간·길이·**멜로디 F1**(결과를 SheetSage2 로 다시 받아 적어 넘긴 악보와 비교)·**가사 CER**(whisper 로 받아 적어 부른 순서의 가사와 비교)을 `결과.md`·`결과.json` 에, 엔진 이름을 가린 듣기용 파일과 `답지.json` 을 함께 남긴다. 시험 곡은 끝나면 지운다(`--keep` 이면 남김).
- 2026-11-13 첫 실행(YuE2, 허밍 1개, 60초 곡): 만드는 데 156초 · 노래 70.8초 · 멜로디 F1 0.819(전주 7.3초 뒤, 한 옥타브 아래) · 가사 CER 0.067.

## API

모든 오류는 `{"error": {"code": "...", "message": "..."}}` 형식입니다. `message`는 앱 화면에 그대로 띄울 수 있는 문장입니다.

| 메서드 · 경로 | 하는 일 | 걸리는 시간(실측) |
|---|---|---|
| `GET /health` | 서버·생성 엔진 상태. 엔진이 꺼져 있으면 503 | 즉시 |
| `GET /v1/instruments` | 고를 수 있는 악기 128종(`GM 0 Grand Piano` …) | 즉시 |
| `POST /v1/songs` | **첫 허밍으로 곡 만들기** | 약 2초 |
| `GET /v1/songs/{id}` | 곡 정보 | 즉시 |
| `GET /v1/songs/{id}/audio` | 모든 트랙을 합친 음원(FLAC 44.1kHz 스테레오) | 즉시 |
| `POST /v1/talk` | **대화로 가사 만들기**: multipart `audio`(차 안 대화, 10분까지) + `direction`(그대로·통쾌하게·담담하게·유쾌하게) → 받아쓰기(faster-whisper large-v3-turbo) + 노래 이야기 정리(주제·요약·감정·살릴 말, 이름은 가림). 녹음은 저장하지 않음. 정리 결과를 `POST /v1/songs/{id}/final` 의 `talk` 로 넘기면 가사에 들어간다 | 30초~2분 |
| `GET /v1/songs/{id}/final/cover?size=384` | 표지. `size`(64~1024)를 주면 줄인 JPEG | 즉시 |
| `GET /v1/songs/{id}/tracks/{n}/audio` | 트랙 하나의 음원 | 즉시 |
| `GET /v1/variations` | 멜로디 변주 4종(화음 위·아래, 멜로디 완성하기, AI로 멜로디 살리기) | 즉시 |
| `POST /v1/songs/{id}/variations` | **변주 트랙 쌓기**(녹음 없이) | 0.3초~10초 |
| `GET /v1/jobs/{job_id}` | 노래 만들기 진행 상태 | 즉시 |
| `GET /v1/songs` | 내 노래 목록(최근 순, `?limit=` 1~100) | 즉시 |
| `GET /v1/songs/{id}/tracks/{n}/hum` | 트랙의 허밍 원본(전/후 들어보기). 변주 트랙은 404 | 즉시 |
| `GET /v1/themes` | 주사위 주제 목록 | 즉시 |
| `POST /v1/songs/{id}/final` | **노래 만들기**(비동기): 2분 곡 구성 → 가사 → 표지 → 노래. 다시 만들기·말로 바꾸기 | 약 3.5분 |
| `GET /v1/jobs/{job_id}/cover` | 만드는 중인 노래의 표지(나오는 대로) | 즉시 |
| `GET /v1/songs/{id}/final/audio`·`/cover` | 완성곡 음원·표지(`?v=버전 id`) | 즉시 |

### 멜로디 성격(`mood`)

곡 응답의 `tracks[n].mood` 와 `mood`(1번 트랙 = 내 허밍)는 음표에서 잰 멜로디 성격이다(`melody_mood.py`). 템포·촘촘함·도약·음역·리듬 변화로 **에너지 0~100**, 장조·단조 틀로 **밝기**를 재서 6칸(포근한·쓸쓸한·산뜻한·아련한·신나는·강렬한, 밝기가 애매하면 잔잔한·부드러운·힘찬)으로 부르고 이유 2~3개(`why`)를 붙인다. 음이 6개 미만이거나 4초 미만이면 `null`. 시험: `python test_mood.py`.

### POST /v1/songs/{id}/final — 노래 만들기

본문 JSON. `prompt`(주제, 비우면 주사위) · `seconds`(60~180, 기본 120) · `voice`(`auto`·`female`·`male`·`none`) · `melody_weight`(0~100, 내 멜로디 반영 — 70 이상 그대로 · 33~69 AI가 다시 씀 · 33 미만 악보 없이. 앱은 그대로 80 · AI가 다듬기 50 · AI가 새로 20 세 버튼만 쓴다) · `style_weight`(0~100, 스타일 강도, 기본 50 — 앱에서는 정하지 않는다). 다시 만들기: `from`(바탕 버전 id) · `keep_lyrics` · `keep_cover` · `lyrics`(직접 고친 가사, 머리말은 절·후렴·브리지 등으로 정리) · **`edit`(말로 바꾸기 한 문장 — 로컬 LLM 이 설정 변경으로 해석, `edit_summary` 로 한 줄 안내)**. `follow_mood`(기본 true): 실제로 쓰는 멜로디의 성격으로 스타일 태그 앞쪽(장르·악기·템포·창법)과 가사 말투·표지 빛을 맞춘다. 주제에 장르를 직접 적으면 장르는 사용자 말을 따른다. 결과는 `final.mood` 에 남는다.

진행은 `GET /v1/jobs/{id}` 의 `stage`(`edit` → `melody` → `lyrics` → `cover` → `music` → `done`). 가사·제목·스타일·표지(`cover_url`)는 나오는 대로 job 에 먼저 실린다. 가사는 줄마다 음 개수에 맞춰 쓰고, 어긋난 줄만 골라 최대 3번 고쳐 쓴다(`syllable_miss`).

### POST /v1/songs — 첫 트랙

`multipart/form-data`

| 필드 | 값 | 기본 |
|---|---|---|
| `audio` | 녹음 파일(WAV 권장, 20MB 이하) | 필수 |
| `instrument` | `/v1/instruments`의 값 | `GM 0 Grand Piano` |
| `melody` | `그대로` · `다듬기` | `그대로` |
| `accompaniment` | `true`(멜로디+화음+베이스) · `false`(악기 하나) | `true` |

응답 201 (요약):

```json
{
  "id": "3f2a9c1b7e44", "tempo": 74, "key": "A", "duration_sec": 13.6,
  "audio_url": "/v1/songs/3f2a9c1b7e44/audio",
  "tracks": [{
    "n": 1, "mode": "overlay", "instrument": "GM 0 Grand Piano", "melody": "그대로", "accompaniment": true,
    "notes": 24, "tempo": 74, "hummed_tempo": 74, "key": "A", "transpose": 0,
    "chords": ["A", "E", "A", "F#m", "E", "A", "E", "A"],
    "hum_info": "입력 5.0s → 출력 12.4s · 음성구간 1개 · 반복 3회 · …",
    "seconds": 2.2, "start_sec": 0.0, "audio_url": "/v1/songs/3f2a9c1b7e44/tracks/1/audio",
    "score": [{"start": 0.0, "dur": 0.405, "midi": 57}, "…"]
  }],
  "ai": null
}
```

`duration_sec`: 트랙 음원 길이(초). `score`: 앱 음표 보기용 트랙 음표. `start`·`dur`는 트랙 음원 기준 초(실제 연주 템포 반영), `midi`는 조성 이동(`transpose`)까지 반영한 음높이. 곡 믹스에서의 위치는 `start_sec`을 더한다.

### POST /v1/songs/{id}/variations — 멜로디 변주

본문 JSON `{"variation": "harmony"}`. 이미 있는 트랙의 악보를 음계 규칙으로 바꿔(ComfyUI `PleosMelodyVariation`) 새 트랙으로 쌓습니다. 허밍 전사가 없어 빠릅니다.

| 필드 | 값 | 기본 |
|---|---|---|
| `variation` | `harmony` 화음 멜로디(마디 화음에 맞춘 위 둘째 목소리) · `harmony_low` 아래 화음 멜로디(아래에서 받침) · `finish` 멜로디 완성하기(음계·박자 맞춤 + 4마디 프레이즈 마침, 4마디 이하면 두 번 불러 8마디) | 필수 |
| `source` | 변주할 트랙 번호 | `1` |
| `instrument` | `/v1/instruments`의 값 | 원본 트랙 악기. 화음 줄(`harmony`·`harmony_low`)은 다른 소리로 — 위 플루트·아래 첼로(원본이 그 악기면 바이올린·클라리넷), 멜로디 트랙보다 약 5dB 작게 |
| `mode` | `overlay` · `append` | `harmony`·`harmony_low`는 겹치기, 나머지는 이어 붙이기 |
| `accompaniment` | 화음+베이스 붙이기 | 이어 붙이기일 때만 `true`. 원본 트랙이 조성 이동됐으면 항상 반주 없음 |

응답 201은 곡 전체(`POST /v1/songs`와 같은 형식). 새 트랙에 `variation`, `source`, `variation_info`(바뀐 음 수·조성)가 붙습니다. 변주 트랙은 2단계 듣기용이고, 노래 만들기(`/final`)는 첫 트랙 악보를 씁니다.

### 오류 코드

| HTTP | code | 언제 | 앱 동작 |
|---|---|---|---|
| 400 | `BAD_REQUEST` · `NO_AUDIO` · `BAD_OPTION` · `BAD_JSON` · `NO_LYRICS` | 요청 형식 오류 | 개발 오류(사용자에게 노출 안 함) |
| 404 | `NOT_FOUND` | 없는 곡·트랙·작업 | 목록 새로고침 |
| 413 | `TOO_LARGE` | 20MB 초과 | 녹음 길이 제한(최대 30초)으로 예방 |
| 422 | `NO_VOICE` | 허밍이 안 들림(무음·잡음뿐) | 메시지 + 다시 녹음 (06번 문서 무음 예외) |
| 422 | `NO_MELODY` | 소리는 있지만 멜로디를 못 뽑음 | 메시지 + 다시 녹음 (06번 문서 E16) |
| 502 | `ENGINE_ERROR` | 생성 엔진 내부 오류 | "잠시 후 다시" + 원본 녹음은 보관 |
| 500 | `SERVER_ERROR` | 그 밖 | 같음 |

## 알려진 한계

- **잔향:** 트랙마다 리버브를 따로 넣고 합칩니다. 기획한 "공유 리버브 1회"는 아직 아닙니다.
- **겹치기 길이:** 짧은 트랙을 반복해 긴 트랙 길이에 맞추지 않습니다(같은 시작점에서 한 번만 재생).
- **대기열:** ComfyUI가 한 번에 하나씩 처리합니다. 허밍 요청은 대기열 맨 앞에 넣지만, 이미 돌고 있는 노래 만들기 단계가 끝날 때까지 기다립니다.
- **보안:** 인증 없음. PC 로컬·에뮬레이터 시험용입니다. 외부 공개 전에 인증·HTTPS가 필요합니다.
