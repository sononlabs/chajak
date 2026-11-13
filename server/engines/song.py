"""노래 엔진 — 곡 악보·가사·스타일 → 노래 소리.

엔진마다 되는 것이 다르다(52번 문서 2장). caps 로 알려 주면 서버가 안 되는 입력을 넣지 않고, 완성곡 기록에 엔진 이름을 남긴다.
  melody: "full"(2분 악보 전체를 따른다) | "60s"(앞 60초 멜로디만, MIDI) | None(멜로디를 받지 않는다 — 주제로 만든 노래)
  style: 스타일·장르 글을 받는지 · seed: 같은 시드로 다시 만들 수 있는지 · gpu: 이 PC 의 GPU 를 쓰는지(쓰면 표지 모델을 먼저 내린다)
새 엔진은 SongEngine 을 이어받아 generate 하나를 쓰고 _ENGINES 에 더한다.
"""
from __future__ import annotations

import asyncio
import re
from dataclasses import dataclass, field

import comfy as C

from . import EngineError, config


@dataclass
class SongRequest:
    abc: str             # 곡 악보(멜로디·화음). 엔진이 멜로디를 쓰지 않거나 '내 멜로디' 가 꺼져 있으면 ""
    style: str           # 스타일 태그(영어, 쉼표)
    lyrics: str          # YuE 형식 가사 "[verse]\n…" 또는 "[Instrumental]"
    sections: dict       # 구간별 가사 줄 {"verse": [...], "chorus": [...]} — 엔진이 자기 형식으로 다시 쓸 때
    plan: dict           # 곡 구성(PleosSongForm) — 구간 순서·길이
    seconds: float       # 만들 길이(끝 여운 포함)
    seed: int
    temperature: float   # YuE2 전용(멜로디 반영 가중치에서)
    cfg_scale: float     # YuE2 전용(스타일 강도에서)
    prefix: str          # ComfyUI 저장 이름


@dataclass
class SongResult:
    audio: bytes
    ext: str                      # "flac" | "mp3" | "wav"
    info: dict = field(default_factory=dict)


class SongEngine:
    name = ""
    caps = {"melody": None, "style": True, "seed": False, "gpu": False}

    def eta(self, seconds: float) -> int:
        """노래 만들기 전체(곡 구성·가사·표지·노래) 예상 초 — 진행 막대용."""
        return round(60 + seconds * 0.5)

    async def generate(self, cf: C.Comfy, req: SongRequest) -> SongResult:
        raise NotImplementedError


class Yue2(SongEngine):
    """이 PC 의 ComfyUI YuE2 3B int8. 가중치 CC BY-NC 4.0(비상업)."""
    name = "yue2"
    caps = {"melody": "full", "style": True, "seed": True, "gpu": True}

    def eta(self, seconds: float) -> int:
        # 2026-11-05 실측(RTX 5070 Ti): 멜로디 1초 · 가사 15초 · 표지 23초 · 노래 205초(곡 114초+8초) → 곡 1초당 약 1.7초
        return round(40 + (seconds + 8) * 1.7)

    async def generate(self, cf, req):
        out = await cf.run(C.final_graph(req.abc, req.style, req.lyrics, req.seed, req.seconds, req.prefix, req.temperature, req.cfg_scale))
        return SongResult(await cf.fetch_audio(out, "save"), "flac", {"model": "yue2_3b_int8_convrot"})


class Mureka(SongEngine):
    """Mureka API(Kunlun) — 가사 → 노래. 곡 악보가 있으면 앞 60초 멜로디를 MIDI 로 올려(files/upload purpose=melody) 따라 부르게 한다.
    공식 문서(2026-11-13 확인): melody_id 는 다른 조절(prompt·reference·vocal)과 함께 쓸 수 없다 → 멜로디가 있으면 스타일 글 없이 성별만.
    n 은 곡 수만큼 돈이 나가 1 로. duration 은 mureka-9.5 만, 120~300초. 값: 9.5 곡당 $0.15(요금 페이지)."""
    name = "mureka"
    caps = {"melody": "60s", "style": True, "seed": False, "gpu": False}
    API = "https://api.mureka.ai"
    MODEL = "mureka-9.5"
    MELODY_MAX_SEC = 58.0
    TAGS = {"verse": "Verse", "chorus": "Chorus", "bridge": "Bridge", "intro": "Intro", "outro": "Outro", "pre-chorus": "Pre-Chorus"}

    def eta(self, seconds: float) -> int:
        return round(60 + 40)  # 곡 구성·가사·표지 약 60초 + 생성(2026-11-13 시험: 가사→노래 약 35초)

    def _session(self):
        import aiohttp
        from . import key
        return aiohttp.ClientSession(headers={"Authorization": "Bearer " + key("mureka")}, timeout=aiohttp.ClientTimeout(total=120))

    def _lyrics(self, req: SongRequest) -> str:
        return re.sub(r"\[([a-z\-]+)\d*\]", lambda m: f"[{self.TAGS.get(m.group(1), m.group(1).title())}]", req.lyrics)[:5000]

    async def _upload(self, s, data: bytes, filename: str, purpose: str, ctype: str) -> str:
        import aiohttp
        form = aiohttp.FormData()
        form.add_field("purpose", purpose)
        form.add_field("file", data, filename=filename, content_type=ctype)
        async with s.post(self.API + "/v1/files/upload", data=form) as r:
            return (await _ok(r))["id"]

    async def _run(self, s, path: str, body: dict, info: dict) -> SongResult:
        """작업 넣기 → 끝날 때까지 기다리기(3초 간격, 최대 10분) → 첫 곡 받기(FLAC 우선)."""
        import aiohttp
        async with s.post(self.API + path, json=body) as r:
            task = await _ok(r)
        info["task_id"] = task["id"]
        for _ in range(200):
            if task.get("status") in ("succeeded", "failed", "timeouted", "cancelled"):
                break
            await asyncio.sleep(3)
            async with s.get(f"{self.API}/v1/song/query/{task['id']}") as r:
                task = await _ok(r)
        if task.get("status") != "succeeded" or not task.get("choices"):
            raise EngineError(f"Mureka 노래 실패({task.get('status')}): {task.get('failed_reason', '')}")
        choice = task["choices"][0]
        info |= {"song_id": choice.get("id"), "duration_ms": choice.get("duration"), "lyrics_sections": choice.get("lyrics_sections")}
        url = choice.get("flac_url") or choice.get("url")
        async with s.get(url, timeout=aiohttp.ClientTimeout(total=300)) as r:
            r.raise_for_status()
            data = await r.read()
        return SongResult(data, "flac" if url == choice.get("flac_url") else "mp3", info)

    async def generate(self, cf, req):
        body = {"lyrics": self._lyrics(req), "model": self.MODEL, "n": 1}
        gender = "female" if "female" in req.style.lower() else ("male" if "male vocal" in req.style.lower() else None)
        if gender:
            body["gender"] = gender
        if req.seconds >= 120:
            body["duration"] = int(min(300, req.seconds))
        info = {"model": self.MODEL, "mode": "melody" if req.abc else "prompt"}
        async with self._session() as s:
            midi = _melody_midi(req.abc, self.MELODY_MAX_SEC) if req.abc else None
            if midi:
                body["melody_id"] = info["melody_id"] = await self._upload(s, midi, "melody.mid", "melody", "audio/midi")
            else:
                body["prompt"] = req.style[:1024]
            return await self._run(s, "/v1/song/generate", body, info)


class MurekaRemix(Mureka):
    """Mureka 리믹스(mureka-9.5) — 우리 곡 구성 악보를 피아노+화음+베이스로 연주한 소리를 올려(purpose=remix),
    가사·스타일을 주고 다시 부르게 한다. 9.5 는 기악곡도 받고 '멜로디를 더 충실히' 따른다고 한다(요금 페이지). 곡당 $0.5.
    (가사로 노래 만들기의 멜로디 업로드는 아이디어로만 쓰여 2026-11-13 비교에서 멜로디 F1 0.12 — 그래서 시험)"""
    name = "mureka_remix"
    caps = {"melody": "full", "style": True, "seed": False, "gpu": False}

    async def generate(self, cf, req):
        if not req.abc:  # 멜로디를 쓰지 않는 설정이면 리믹스할 소리가 없다 → 가사로 노래 만들기
            return await super().generate(cf, req)
        import io
        import numpy as np
        import soundfile as sf
        tempo, _ = C.abc_header(req.abc)
        out = await cf.run(C.abc_graph(req.abc, "GM 0 Grand Piano", True, tempo, 0, req.prefix + "_guide"))
        y, sr = sf.read(io.BytesIO(await cf.fetch_audio(out, "save")), dtype="float32")
        if len(y) < 11 * sr:  # 리믹스는 10초 이상
            y = np.concatenate([y, np.zeros((11 * sr - len(y),) + y.shape[1:], np.float32)])
        buf = io.BytesIO()
        sf.write(buf, y, sr, format="MP3")
        info = {"model": self.MODEL, "mode": "remix", "guide_sec": round(len(y) / sr, 1)}
        async with self._session() as s:
            info["upload_id"] = await self._upload(s, buf.getvalue(), "guide.mp3", "remix", "audio/mpeg")
            body = {"upload_audio_id": info["upload_id"], "lyrics": self._lyrics(req), "model": self.MODEL, "n": 1,
                    "prompt": req.style[:1024] or "pop, vocal"}
            return await self._run(s, "/v1/song/remix", body, info)


async def _ok(r) -> dict:
    j = await r.json(content_type=None)
    if r.status >= 400:
        raise EngineError(f"Mureka API {r.status}: {j}")
    return j


def _melody_midi(abc: str, max_sec: float) -> bytes | None:
    """곡 악보의 멜로디(best_voice) → 단일 트랙 MIDI(SMF 0). 첫 음부터 max_sec 초까지. 의존성 없이 직접 쓴다."""
    import melody_notes
    tempo, _ = C.abc_header(abc)
    notes = melody_notes.notes({"abc": abc, "tempo": tempo})
    if not notes:
        return None
    t0 = notes[0]["start"]
    ppq, sec_per_tick = 480, 60.0 / tempo / 480
    events = []
    for n in notes:
        a = n["start"] - t0
        if a >= max_sec:
            break
        b = min(a + n["dur"], max_sec)
        events += [(round(a / sec_per_tick), 0x90, n["midi"], 96), (round(b / sec_per_tick), 0x80, n["midi"], 0)]
    events.sort(key=lambda e: (e[0], e[1] == 0x90))  # 같은 때는 끄기 먼저

    def vlq(v: int) -> bytes:
        out = [v & 0x7F]
        while v > 0x7F:
            v >>= 7
            out.insert(0, (v & 0x7F) | 0x80)
        return bytes(out)

    us = round(60_000_000 / tempo)
    trk = bytearray(b"\x00\xff\x51\x03" + us.to_bytes(3, "big"))
    now = 0
    for tick, kind, pitch, vel in events:
        trk += vlq(tick - now) + bytes([kind, max(0, min(127, pitch)), vel])
        now = tick
    trk += b"\x00\xff\x2f\x00"
    return b"MThd" + (6).to_bytes(4, "big") + (0).to_bytes(2, "big") + (1).to_bytes(2, "big") + ppq.to_bytes(2, "big") + \
        b"MTrk" + len(trk).to_bytes(4, "big") + bytes(trk)


_ENGINES: dict[str, type[SongEngine]] = {"yue2": Yue2, "mureka": Mureka, "mureka_remix": MurekaRemix}


def names() -> list[str]:
    return list(_ENGINES)


def get(name: str | None = None) -> SongEngine:
    """name 이 없으면 설정 파일의 song. 이름이 없으면 EngineError."""
    name = name or config()["song"]
    if name not in _ENGINES:
        raise EngineError(f"노래 엔진 '{name}' 은 아직 없어요({', '.join(_ENGINES)} 중 하나).")
    return _ENGINES[name]()
