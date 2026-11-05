"""ComfyUI 를 생성 엔진으로 쓰는 클라이언트와 차작 그래프(API 형식).

ComfyUI 실행 조건: --whitelist-custom-nodes pleos_melody_instrument (Pleos* 노드),
SheetSage2 / YuE2 모델이 models 폴더에 있어야 한다.
"""

from __future__ import annotations

import asyncio
import math
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

    async def run(self, prompt: dict, front: bool = False, poll: float = 0.15) -> dict:
        """그래프를 실행하고 history 항목의 outputs 를 돌려준다. front=True 면 대기열 맨 앞에 넣는다."""
        body = {"prompt": prompt, "client_id": self.client_id, "front": front}
        async with self.session.post(self.base + "/prompt", json=body) as r:
            j = await r.json()
        if "prompt_id" not in j:
            raise ComfyError(f"ComfyUI 가 요청을 거부했습니다: {j.get('error', j)}")
        pid = j["prompt_id"]
        while True:
            async with self.session.get(f"{self.base}/history/{pid}") as r:
                h = await r.json()
            if pid in h:
                break
            await asyncio.sleep(poll)
        item = h[pid]
        for kind, msg in item["status"].get("messages", []):
            if kind == "execution_error":
                text = msg.get("exception_message", "")
                raise ComfyError(text.strip(), msg.get("node_type", ""))
        return item["outputs"]

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


def melody_graph(upload_name: str, melody: str) -> dict:
    """악보만 뽑는 그래프. 같은 입력으로 track_graph 를 이어 돌리면 ComfyUI 캐시로 앞부분은 다시 계산하지 않는다."""
    return _melody_front(upload_name, MELODY_FREEDOM[melody])


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


def _melody_front(upload_name: str, freedom: float) -> dict:
    """녹음 → 허밍 정리 → SheetSage2 악보 → 자유도(멜로디를 Ins 성부로 옮기고 다듬기)."""
    return {
        "load": {"class_type": "LoadAudio", "inputs": {"audio": upload_name}},
        "enc": {"class_type": "AudioEncoderLoader", "inputs": {"audio_encoder_name": "sheetsage2_bf16.safetensors"}},
        "prep": {"class_type": "PleosPrepareHum", "inputs": {"audio": ["load", 0], "mode": "전처리 + 반복 (권장)", "min_seconds": 12.0, "target_db": -22.0, "denoise": True, "vad_margin_hi": 18}},
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
                transpose: int = 0) -> dict:
    """한 트랙. 쌓기 트랙은 tempo·transpose 로 곡 박자·조성에 맞춘다. tempo 0 이면 악보 템포."""
    p = _melody_front(upload_name, MELODY_FREEDOM[melody])
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


def cover_graph(prompt: str, seed: int, prefix: str) -> dict:
    """앨범 표지: Z-Image Turbo(8단계). 글자 없는 정사각형 그림."""
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
        "save": {"class_type": "SaveAudioAdvanced", "inputs": {"audio": ["dec", 0], "filename_prefix": prefix, "format": "flac"}},
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
