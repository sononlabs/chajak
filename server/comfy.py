"""ComfyUI 를 생성 엔진으로 쓰는 클라이언트와 차작 그래프(API 형식).

ComfyUI 실행 조건: --disable-all-custom-nodes --whitelist-custom-nodes pleos_melody_instrument (Pleos* 노드만;
whitelist 만 주면 다른 커스텀 노드도 모두 올라온다),
SheetSage2 / YuE2 모델이 models 폴더에 있어야 한다.
"""

from __future__ import annotations

import asyncio
import json
import math
import os
import re
import uuid

import aiohttp

SOUNDFONT = "GeneralUser-GS.sf2"
DEFAULT_INSTRUMENT = "GM 0 Grand Piano"
CHORD_INSTRUMENT = "GM 89 Warm Pad"
BASS_INSTRUMENT = "GM 33 Finger Bass"
# 앱의 멜로디 처리 단계 → 자유도 노드 값 (13번 문서 5-1)
MELODY_FREEDOM = {"그대로": 0.0, "다듬기": 0.5, "AI 편곡": 0.9}
# 변주 id → (노드 이름, 어울리는 쌓기 방식, 앱에 보여줄 설명). 노드 쪽 목록: melody_variation.VARIATIONS
VARIATIONS = {
    "harmony": ("화음 멜로디", "overlay", "내 멜로디보다 3도 위에서 함께 부르는 둘째 목소리"),
    "mood": ("분위기 바꾸기", "append", "밝은 장조는 쓸쓸한 단조로, 단조는 밝은 장조로"),
    "ornament": ("꾸밈 넣기", "append", "음 사이를 잇는 꾸밈음을 넣어 더 노래처럼"),
    "bounce": ("통통 튀게", "append", "긴 음을 쪼개고 셔플 리듬으로"),
    "inversion": ("위아래 뒤집기", "append", "올라가던 음은 내려가고, 내려가던 음은 올라가게"),
    "retrograde": ("거꾸로", "append", "멜로디를 끝에서부터 거꾸로"),
    "step_up": ("한 단계 위로", "append", "같은 멜로디를 음계 한 칸 위에서 다시"),
    "finish": ("멜로디 완성하기", "append", "음계·박자를 맞추고 프레이즈 끝을 닫아 한 곡처럼"),
    # 노드 변주가 아니라 서버(melody_ai)가 LLM 으로 다시 쓴다. 이름 칸은 앱 표시용
    "rewrite": ("AI로 멜로디 살리기", "append", "허밍은 참고만 하고 AI가 더 노래다운 8마디로 새로 써요"),
}
SERVER_VARIATIONS = {"rewrite"}
NO_MELODY_HINTS =("MelodyVoice", "멜로디 음표를 찾지 못", "ABC 텍스트가 비어", "음표가 없")
NO_VOICE_HINTS = ("음성 구간을 찾지 못",)


DOWN_WAIT = 300  # ComfyUI 재시작(모델 다시 올리기 포함)을 기다리는 최대 시간(초)
FREE_WAIT = 3  # /free 가 처리될 틈(초)
GEN_HEADROOM_DB = -6  # 노래 저장 전 음량(dB)
MAX_WAIT = 1500  # 작업 하나를 기다리는 최대 시간(초). 넘으면 ComfyUI 에서 지우고 실패로(전에는 영원히 기다려 노래 대기열 전체가 멈출 수 있었다)


class ComfyError(RuntimeError):
    """kind: "no_voice"(허밍이 안 들림) · "no_melody"(들렸지만 멜로디를 못 뽑음) · "engine"(그 밖)."""

    def __init__(self, message: str, node_type: str = ""):
        super().__init__(message)
        self.node_type = node_type
        if any(k in message for k in NO_VOICE_HINTS):
            self.kind = "no_voice"
        elif any(k in message for k in NO_MELODY_HINTS) or node_type == "SheetSage2AudioToABC":
            self.kind = "no_melody"
        else:
            self.kind = "engine"


class Comfy:
    def __init__(self, base_url: str):
        self.base = base_url.rstrip("/")
        self.client_id = "chajak-server-" + uuid.uuid4().hex[:8]
        self.session: aiohttp.ClientSession | None = None

    async def start(self):
        self.session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=600))

    async def close(self):
        await self.session.close()

    async def alive(self) -> bool:
        try:
            async with self.session.get(self.base + "/object_info/PleosAutoArrange") as r:
                return r.status == 200 and "PleosAutoArrange" in await r.json()
        except aiohttp.ClientError:
            return False

    async def instruments(self) -> list[str]:
        async with self.session.get(self.base + "/object_info/PleosSoundFontRender") as r:
            info = await r.json()
        return info["PleosSoundFontRender"]["input"]["required"]["instrument"][1]["options"]

    async def extra_custom_nodes(self) -> list[str]:
        """차작 노드(pleos_melody_instrument) 말고 올라와 있는 커스텀 노드 폴더.
        2026-11-10: ComfyUI-Manager 가 노드를 설치하며 --disable-all-custom-nodes 없이 다시 켜서 50여 개가 함께 올라왔고,
        그중 HeartMuLa 가 3B 모델을 VRAM 에 계속 붙잡아 노래 한 곡이 1시간 넘게 걸렸다."""
        async with self.session.get(self.base + "/object_info") as r:
            info = await r.json()
        mods = {v.get("python_module", "") for v in info.values()}
        return sorted(m.split(".", 1)[1] for m in mods
                      if m.startswith("custom_nodes.") and m != "custom_nodes.pleos_melody_instrument")

    async def upload(self, data: bytes, filename: str) -> str:
        form = aiohttp.FormData()
        form.add_field("image", data, filename=filename, content_type="application/octet-stream")
        form.add_field("type", "input")
        form.add_field("subfolder", "chajak_server")
        form.add_field("overwrite", "true")
        async with self.session.post(self.base + "/upload/image", data=form) as r:
            r.raise_for_status()
            j = await r.json()
        return f"{j['subfolder']}/{j['name']}" if j.get("subfolder") else j["name"]

    async def _call(self, method: str, path: str, **kw):
        """ComfyUI 요청. 재시작 중(연결 끊김)이면 DOWN_WAIT 초까지 다시 시도한다(2026-11-10: 노드 설치로 재시작되며 노래가 끊겼다)."""
        t0 = asyncio.get_running_loop().time()
        while True:
            try:
                async with self.session.request(method, self.base + path, **kw) as r:
                    body = await r.text()
                return json.loads(body) if body.strip() else {}  # /free 처럼 빈 응답도 있다
            except (aiohttp.ClientConnectionError, asyncio.TimeoutError, OSError) as e:
                if asyncio.get_running_loop().time() - t0 > DOWN_WAIT:
                    raise ComfyError(f"ComfyUI 에 연결할 수 없습니다({DOWN_WAIT}초 기다림): {e}") from e
                await asyncio.sleep(3)

    async def free(self):
        """올려 둔 모델을 내리고 VRAM 을 비운다.
        2026-11-10 안정성 시험: 표지(Qwen-Image, 약 19.5GB)를 그린 뒤 노래 모델이 올라가면 VRAM 16GB 를 넘겨 공유 메모리로 넘쳤고,
        노래 한 곡이 20분 넘게 끝나지 않았다."""
        try:
            await self._call("POST", "/free", json={"unload_models": True, "free_memory": True})
            await asyncio.sleep(FREE_WAIT)  # ComfyUI 는 /free 를 대기열이 빈 틈에 처리한다 — 바로 다음 작업을 넣으면 비우기보다 작업이 먼저 돈다
        except Exception as e:
            print("ComfyUI 메모리 비우기 실패(계속 진행):", e, flush=True)

    async def run(self, prompt: dict, front: bool = False, poll: float = 0.15, retries: int = 1, max_wait: float = MAX_WAIT) -> dict:
        """그래프를 실행하고 history 항목의 outputs 를 돌려준다. front=True 면 대기열 맨 앞에 넣는다.
        도중에 ComfyUI 가 재시작돼 작업이 사라지면(대기열·기록 어디에도 없음) 한 번 다시 넣는다 — 전에는 기록을 영원히 기다려 대기열 전체가 멈췄다."""
        for attempt in range(retries + 1):
            j = await self._call("POST", "/prompt", json={"prompt": prompt, "client_id": self.client_id, "front": front})
            if "prompt_id" not in j:
                raise ComfyError(f"ComfyUI 가 요청을 거부했습니다: {j.get('error', j)}")
            pid = j["prompt_id"]
            try:
                item = await self._wait(pid, poll, max_wait)
            except asyncio.CancelledError:  # 노래 만들기 취소 — 엔진에서 돌던 작업도 멈춘다
                await self._cancel(pid)
                raise
            if item is not None:
                break
            if attempt == retries:
                raise ComfyError("ComfyUI 가 다시 시작되어 작업이 끊겼습니다")
            print(f"ComfyUI 작업 {pid[:8]} 이 사라짐(재시작?) — 다시 넣는다", flush=True)
        for kind, msg in item["status"].get("messages", []):
            if kind == "execution_error":
                text = msg.get("exception_message", "")
                raise ComfyError(text.strip(), msg.get("node_type", ""))
        return item["outputs"]

    async def _wait(self, pid: str, poll: float, max_wait: float = MAX_WAIT) -> dict | None:
        """기록에 나오면 그 항목, 대기열·기록 어디에도 없으면(재시작·대기열 비우기) None. max_wait 를 넘기면 지우고 ComfyError."""
        loop = asyncio.get_running_loop()
        t0 = loop.time()
        next_check = t0 + 5
        while True:
            h = await self._call("GET", f"/history/{pid}")
            if pid in h:
                return h[pid]
            now = loop.time()
            if now - t0 > max_wait:
                await self._cancel(pid)
                raise ComfyError(f"ComfyUI 작업이 {int(max_wait)}초 안에 끝나지 않아 멈췄습니다")
            if now >= next_check:
                next_check = now + 5
                q = await self._call("GET", "/queue")
                ids = {t[1] for t in q.get("queue_running", []) + q.get("queue_pending", []) if len(t) > 1}
                if pid not in ids:
                    # 막 끝났거나 실패한 경우 기록이 조금 늦게 보일 수 있다 — 2초 동안 몇 번 더 본다
                    # (11-11: 실패한 작업을 '사라짐'으로 보고 한 번 더 넣었다)
                    for _ in range(5):
                        await asyncio.sleep(0.4)
                        h = await self._call("GET", f"/history/{pid}")
                        if pid in h:
                            return h[pid]
                    return None
            await asyncio.sleep(poll)

    async def _cancel(self, pid: str):
        """기다리다 포기한 작업을 ComfyUI 에서도 멈춘다(돌고 있으면 interrupt, 대기 중이면 대기열에서 지움)."""
        try:
            q = await self._call("GET", "/queue")
            if any(len(t) > 1 and t[1] == pid for t in q.get("queue_running", [])):
                await self._call("POST", "/interrupt")
            else:
                await self._call("POST", "/queue", json={"delete": [pid]})
        except Exception as e:
            print("ComfyUI 작업 멈추기 실패:", e, flush=True)

    async def fetch_image(self, outputs: dict, node: str) -> bytes:
        return await self.fetch_audio(outputs, node, kind="images")

    async def fetch_audio(self, outputs: dict, node: str, kind: str = "audio") -> bytes:
        a = outputs[node][kind][0]
        params = {"filename": a["filename"], "subfolder": a.get("subfolder", ""), "type": a.get("type", "output")}
        async with self.session.get(self.base + "/view", params=params) as r:
            r.raise_for_status()
            return await r.read()


def text(outputs: dict, node: str) -> str:
    t = outputs.get(node, {}).get("text")
    return t[0] if t else ""


def melody_graph(upload_name: str, melody: str, music: bool = False, prep_prefix: str | None = None) -> dict:
    """악보만 뽑는 그래프. 같은 입력으로 track_graph 를 이어 돌리면 ComfyUI 캐시로 앞부분은 다시 계산하지 않는다.
    prep_prefix 를 주면 허밍 정리 뒤 소리도 저장한다(세밀 악보용, fine_melody)."""
    p = _melody_front(upload_name, MELODY_FREEDOM[melody], music)
    if prep_prefix:
        p["prep_save"] = {"class_type": "SaveAudioAdvanced", "inputs": {"audio": ["prep", 0], "filename_prefix": prep_prefix, "format": "flac"}}
    return p


def fit_tempo(song_tempo: float, hummed_tempo: float) -> int:
    """쌓기 트랙을 곡 박자에 맞추되, 부른 빠르기와 가장 가까운 배수(½·1·2배)를 고른다."""
    cands = [song_tempo * k for k in (0.5, 1, 2)]
    return round(min(cands, key=lambda c: abs(math.log(hummed_tempo / c))))


PITCH = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def _relative_major(key: str) -> int:
    m = re.match(r"([A-G])([#b]?)(m?)", key)
    if not m:
        return 0
    pc = PITCH[m.group(1)] + {"#": 1, "b": -1, "": 0}[m.group(2)]
    return (pc + (3 if m.group(3) else 0)) % 12


def fit_key(song_key: str, track_key: str) -> int:
    """쌓기 트랙을 곡 조성으로 옮길 반음 수(-6~+5). 장·단조는 나란한조끼리 같은 조로 본다(11번 문서 스마트 보정)."""
    shift = (_relative_major(song_key) - _relative_major(track_key)) % 12
    return shift - 12 if shift > 5 else shift


def _melody_front(upload_name: str, freedom: float, music: bool = False) -> dict:
    """녹음 → 허밍 정리 → SheetSage2 악보 → 자유도(멜로디를 Ins 성부로 옮기고 다듬기).
    music: 듣던 음악을 녹음한 것 — 허밍 정리의 음성 구간 잘라내기·잡음 제거·음량 맞춤을 건너뛰고 짧으면 반복만 한다.
    완성곡은 조용한 구간이 없어 잘라내기 기준(잡음 바닥 + 18dB)에 걸리고, SheetSage2 는 원래 반주 섞인 곡에서 멜로디를 뽑는다."""
    prep_mode = "반복만" if music else "전처리 + 반복 (권장)"
    return {
        "load": {"class_type": "LoadAudio", "inputs": {"audio": upload_name}},
        "enc": {"class_type": "AudioEncoderLoader", "inputs": {"audio_encoder_name": "sheetsage2_bf16.safetensors"}},
        "prep": {"class_type": "PleosPrepareHum", "inputs": {"audio": ["load", 0], "mode": prep_mode, "min_seconds": 12.0, "target_db": -22.0, "denoise": True, "vad_margin_hi": 18}},
        "sheet": {"class_type": "SheetSage2AudioToABC", "inputs": {"audio_encoder": ["enc", 0], "audio": ["prep", 0], "mode": "melody"}},
        "free": {"class_type": "PleosMelodyFreedom", "inputs": {"abc": ["sheet", 0], "freedom": freedom}},
        "prep_info": {"class_type": "PreviewAny", "inputs": {"source": ["prep", 1]}},
        "abc_out": {"class_type": "PreviewAny", "inputs": {"source": ["free", 0]}},
    }


def _save(p: dict, audio_ref: list, prefix: str) -> dict:
    p["save"] = {"class_type": "SaveAudioAdvanced", "inputs": {"audio": audio_ref, "filename_prefix": prefix, "format": "flac"}}
    return p


def _render(p: dict, abc_ref: list, instrument: str, accompaniment: bool, tempo: int, transpose: int, prefix: str) -> dict:
    """반주를 붙이면 자동 반주(멜로디+화음+베이스, 악보 템포·조성 그대로), 아니면 악기 하나로 tempo·transpose 에 맞춰 연주."""
    if accompaniment:
        p["render"] = {"class_type": "PleosAutoArrange", "inputs": {"abc": abc_ref, "soundfont": SOUNDFONT, "melody_instrument": instrument, "chord_instrument": CHORD_INSTRUMENT, "bass_instrument": BASS_INSTRUMENT, "reverb_mix": 0.2}}
    else:
        p["render"] = {"class_type": "PleosSoundFontRender", "inputs": {"abc_text": abc_ref, "soundfont": SOUNDFONT, "instrument": instrument, "voice": "auto", "tempo": int(tempo), "transpose": int(transpose), "velocity": 100, "gain_db": -6.0, "loop_count": 1, "reverb_mix": 0.18, "reverb_room": 0.9, "samplerate": 44100}}
    p["render_info"] = {"class_type": "PreviewAny", "inputs": {"source": ["render", 1]}}
    return _save(p, ["render", 0], prefix)


def track_graph(upload_name: str, instrument: str, melody: str, accompaniment: bool, tempo: int, prefix: str,
                transpose: int = 0, music: bool = False) -> dict:
    """한 트랙. 쌓기 트랙은 tempo·transpose 로 곡 박자·조성에 맞춘다. tempo 0 이면 악보 템포."""
    p = _melody_front(upload_name, MELODY_FREEDOM[melody], music)
    return _render(p, ["free", 0], instrument, accompaniment, tempo, transpose, prefix)


def variation_graph(abc: str, variation: str, instrument: str, accompaniment: bool, tempo: int, transpose: int,
                    prefix: str) -> dict:
    """이미 뽑은 악보로 변주 멜로디를 만들어 연주한다(허밍 전사 없이)."""
    p = {
        "abc_in": {"class_type": "PrimitiveStringMultiline", "inputs": {"value": abc}},
        "var": {"class_type": "PleosMelodyVariation", "inputs": {"abc": ["abc_in", 0], "variation": VARIATIONS[variation][0]}},
        "abc_out": {"class_type": "PreviewAny", "inputs": {"source": ["var", 0]}},
        "var_info": {"class_type": "PreviewAny", "inputs": {"source": ["var", 1]}},
    }
    return _render(p, ["var", 0], instrument, accompaniment, tempo, transpose, prefix)


def abc_graph(abc: str, instrument: str, accompaniment: bool, tempo: int, transpose: int, prefix: str) -> dict:
    """이미 만든 악보(예: AI 가 다시 쓴 멜로디)를 그대로 연주한다."""
    p = {
        "abc_in": {"class_type": "PrimitiveStringMultiline", "inputs": {"value": abc}},
        "abc_out": {"class_type": "PreviewAny", "inputs": {"source": ["abc_in", 0]}},
    }
    return _render(p, ["abc_in", 0], instrument, accompaniment, tempo, transpose, prefix)


def ai_graph(abc: str, use_lyrics: bool, lyrics: str, seed: int, prefix: str) -> dict:
    """AI 편곡: 다듬은 악보 → 가사 토글(멜로디 성부 배치) → YuE2 melody → 다시 전사해 보컬 확인."""
    p = {
        "abc_in": {"class_type": "PrimitiveStringMultiline", "inputs": {"value": abc}},
        "free": {"class_type": "PleosMelodyFreedom", "inputs": {"abc": ["abc_in", 0], "freedom": MELODY_FREEDOM["AI 편곡"]}},
        "eta": {"class_type": "PreviewAny", "inputs": {"source": ["free", 2]}},
        "lyr": {"class_type": "PleosLyricsToggle", "inputs": {"abc": ["free", 0], "use_lyrics": use_lyrics, "style": "warm indie pop, felt piano lead melody, warm bass, soft drums, gentle acoustic guitar, night drive home atmosphere, clean modern production", "lyrics": lyrics}},
        "ckpt": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "yue2_3b_int8_convrot.safetensors"}},
        "yue": {"class_type": "YuE2GenerateMusic", "inputs": {"clip": ["ckpt", 1], "style": ["lyr", 1], "lyrics": ["lyr", 2], "abc": ["lyr", 0], "seed": seed, "mode": "melody", "max_duration": 30.0, "temperature": 0.7, "top_p": 0.95, "top_k": 100, "repetition_penalty": 1.2, "cfg_scale": 1.0}},
        "neg": {"class_type": "ConditioningZeroOut", "inputs": {"conditioning": ["yue", 0]}},
        "lat": {"class_type": "EmptyYuE2LatentAudio", "inputs": {"seconds": ["yue", 1], "batch_size": 1}},
        "ks": {"class_type": "KSampler", "inputs": {"model": ["ckpt", 0], "seed": seed, "steps": 32, "cfg": 1.0, "sampler_name": "dpm_2", "scheduler": "sgm_uniform", "positive": ["yue", 0], "negative": ["neg", 0], "latent_image": ["lat", 0], "denoise": 1.0}},
        "dec": {"class_type": "VAEDecodeAudio", "inputs": {"samples": ["ks", 0], "vae": ["ckpt", 2]}},
        "enc": {"class_type": "AudioEncoderLoader", "inputs": {"audio_encoder_name": "sheetsage2_bf16.safetensors"}},
        "resheet": {"class_type": "SheetSage2AudioToABC", "inputs": {"audio_encoder": ["enc", 0], "audio": ["dec", 0], "mode": "melody"}},
        "vcheck": {"class_type": "PleosVocalCheck", "inputs": {"abc": ["resheet", 0], "use_lyrics": ["lyr", 3]}},
        "vcheck_out": {"class_type": "PreviewAny", "inputs": {"source": ["vcheck", 0]}},
    }
    return _save(p, ["dec", 0], prefix)


def song_form_graph(abc: str, seconds: float, sung: bool = True) -> dict:
    """허밍 악보 → 약 2분 곡 악보(화음 표기) + 계획 JSON. 규칙 기반이라 1초 안쪽."""
    return {
        "abc_in": {"class_type": "PrimitiveStringMultiline", "inputs": {"value": abc}},
        "form": {"class_type": "PleosSongForm", "inputs": {"abc": ["abc_in", 0], "target_seconds": float(seconds), "sung": sung}},
        "abc_out": {"class_type": "PreviewAny", "inputs": {"source": ["form", 0]}},
        "plan_out": {"class_type": "PreviewAny", "inputs": {"source": ["form", 1]}},
    }


COVER_UNET = "Zimage\\z_image_turbo_nvfp4.safetensors"
COVER_CLIP = "qwen_3_4b_fp8_mixed.safetensors"
COVER_VAE = "z_image_ae.safetensors"


# 2026-11-08 비교(리서치 39 6절): Qwen-Image-2512 + Lightning 4단계가 글자 새는 일·장면 반영이 더 낫다.
# 곡 끝까지 재 보니(노래 모델 YuE2 와 교대 포함) 표지 15→19초, 곡 전체 약 159→161초.
# 2026-11-10 안정성 시험 뒤 기본을 다시 Z-Image 로: Qwen 은 본체 19.5GB+글 모델 7.9GB 로 VRAM 16GB 를 넘겨 나눠 올리므로,
# 사용자가 다른 GPU 작업을 하거나 에뮬레이터가 VRAM 을 잡고 있으면 가장 먼저 느려진다(Z-Image 는 4.3GB+5.4GB).
# 시연 품질을 우선하려면 CHAJAK_COVER_MODEL=qwen2512
COVER_MODEL = os.environ.get("CHAJAK_COVER_MODEL", "zimage")


def cover_graph(prompt: str, seed: int, prefix: str) -> dict:
    """앨범 표지: Z-Image Turbo(8단계) 또는 Qwen-Image-2512(4단계). 글자 없는 정사각형 그림."""
    if COVER_MODEL == "qwen2512":
        return cover_graph_qwen(prompt, seed, prefix)
    return {
        "unet": {"class_type": "UNETLoader", "inputs": {"unet_name": COVER_UNET, "weight_dtype": "default"}},
        "shift": {"class_type": "ModelSamplingAuraFlow", "inputs": {"model": ["unet", 0], "shift": 3.0}},
        "clip": {"class_type": "CLIPLoader", "inputs": {"clip_name": COVER_CLIP, "type": "lumina2", "device": "default"}},
        "pos": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["clip", 0], "text": prompt}},
        "neg": {"class_type": "ConditioningZeroOut", "inputs": {"conditioning": ["pos", 0]}},
        "lat": {"class_type": "EmptySD3LatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
        "ks": {"class_type": "KSampler", "inputs": {"model": ["shift", 0], "seed": seed, "steps": 8, "cfg": 1.0, "sampler_name": "res_multistep",
                                                  "scheduler": "simple", "positive": ["pos", 0], "negative": ["neg", 0], "latent_image": ["lat", 0], "denoise": 1.0}},
        "vae": {"class_type": "VAELoader", "inputs": {"vae_name": COVER_VAE}},
        "dec": {"class_type": "VAEDecode", "inputs": {"samples": ["ks", 0], "vae": ["vae", 0]}},
        "save": {"class_type": "SaveImage", "inputs": {"images": ["dec", 0], "filename_prefix": prefix}},
    }



def cover_graph_qwen(prompt: str, seed: int, prefix: str) -> dict:
    """앨범 표지: Qwen-Image-2512 fp8 + Lightning 4단계 LoRA(둘 다 Apache 2.0 — 모델 카드 원문 확인 필요)."""
    return {
        "unet": {"class_type": "UNETLoader", "inputs": {"unet_name": "qwen_image_2512_fp8_e4m3fn.safetensors", "weight_dtype": "default"}},
        "lora": {"class_type": "LoraLoaderModelOnly", "inputs": {"model": ["unet", 0], "lora_name": "Qwen-Image-2512-Lightning-4steps-V1.0-fp32.safetensors",
                                                               "strength_model": 1.0}},
        "shift": {"class_type": "ModelSamplingAuraFlow", "inputs": {"model": ["lora", 0], "shift": 3.1}},
        "clip": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen_2.5_vl_7b_fp8_scaled.safetensors", "type": "qwen_image", "device": "default"}},
        "pos": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["clip", 0], "text": prompt}},
        "neg": {"class_type": "ConditioningZeroOut", "inputs": {"conditioning": ["pos", 0]}},
        "lat": {"class_type": "EmptySD3LatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
        "ks": {"class_type": "KSampler", "inputs": {"model": ["shift", 0], "seed": seed, "steps": 4, "cfg": 1.0, "sampler_name": "euler",
                                                  "scheduler": "simple", "positive": ["pos", 0], "negative": ["neg", 0], "latent_image": ["lat", 0], "denoise": 1.0}},
        "vae": {"class_type": "VAELoader", "inputs": {"vae_name": "qwen_image_vae.safetensors"}},
        "dec": {"class_type": "VAEDecode", "inputs": {"samples": ["ks", 0], "vae": ["vae", 0]}},
        "save": {"class_type": "SaveImage", "inputs": {"images": ["dec", 0], "filename_prefix": prefix}},
    }

def final_graph(abc: str, style: str, lyrics: str, seed: int, seconds: float, prefix: str,
                temperature: float = 0.7, cfg_scale: float = 1.0) -> dict:
    """최종 노래: 2분 곡 악보(멜로디 + 화음) + 가사 → YuE2 full 모드. abc 가 비면 YuE2 가 악보 없이(off) 만든다."""
    return {
        "ckpt": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "yue2_3b_int8_convrot.safetensors"}},
        "yue": {"class_type": "YuE2GenerateMusic", "inputs": {"clip": ["ckpt", 1], "style": style, "lyrics": lyrics, "abc": abc, "seed": seed, "mode": "full",
                                                            "max_duration": float(seconds), "temperature": float(temperature), "top_p": 0.95, "top_k": 100,
                                                            "repetition_penalty": 1.2, "cfg_scale": float(cfg_scale)}},
        "neg": {"class_type": "ConditioningZeroOut", "inputs": {"conditioning": ["yue", 0]}},
        "lat": {"class_type": "EmptyYuE2LatentAudio", "inputs": {"seconds": ["yue", 1], "batch_size": 1}},
        "ks": {"class_type": "KSampler", "inputs": {"model": ["ckpt", 0], "seed": seed, "steps": 32, "cfg": 1.0, "sampler_name": "dpm_2", "scheduler": "sgm_uniform",
                                                  "positive": ["yue", 0], "negative": ["neg", 0], "latent_image": ["lat", 0], "denoise": 1.0}},
        "dec": {"class_type": "VAEDecodeAudio", "inputs": {"samples": ["ks", 0], "vae": ["ckpt", 2]}},
        # 저장 전에 음량을 낮춰 둔다 — 디코더 출력이 1.0 을 넘어 FLAC 로 저장될 때 잘려 지지직거렸다(2026-11-11). 서버가 -1dBFS 로 다시 맞춘다(mastering)
        "vol": {"class_type": "AudioAdjustVolume", "inputs": {"audio": ["dec", 0], "volume": GEN_HEADROOM_DB}},
        "save": {"class_type": "SaveAudioAdvanced", "inputs": {"audio": ["vol", 0], "filename_prefix": prefix, "format": "flac"}},
    }


def abc_header(abc: str):
    """(템포, 조성) — 악보 머리글에서."""
    q = re.search(r"^Q:.*?=\s*([\d.]+)", abc, re.M)
    k = re.search(r"^K:\s*(\S+)", abc, re.M)
    return (round(float(q.group(1))) if q else 120), (k.group(1) if k else "C")


def eta_seconds(summary: str):
    """자유도 노드 요약의 '약 N초 · … 최대 M초'."""
    m = re.search(r"약 (\d+)초.*?최대 (\d+)초", summary)
    return (int(m.group(1)), int(m.group(2))) if m else (None, None)
