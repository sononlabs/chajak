"""반주 다시 만들기 — 노래 엔진이 만든 곡에서 보컬만 남기고 반주를 새로 만들어 섞는다(51번 7-2 의 3안, 2026-11-14).

YuE2 는 내 멜로디를 잘 따라 부르지만(멜로디 F1 0.87~0.94) 반주 음질이 아쉽고, Mureka 는 반대로 멜로디를 따르지 않는다.
그래서 멜로디·보컬은 YuE2 것을 그대로 두고 반주만 바꾼다:
  ① Mureka 분리(audio-separation-1, $0.06) → 보컬 wav
  ② 보컬을 올려(files/upload purpose=audio) Mureka 트랙 만들기(generate_type=Instrumental, 스타일 프롬프트, $0.09)
  ③ 보컬 + 새 반주를 섞는다(반주는 보컬보다 조금 작게)
실패하면 부르는 쪽이 원래 곡을 그대로 쓴다.
"""
from __future__ import annotations

import asyncio
import base64
import io
import zipfile

import aiohttp
import numpy as np
import soundfile as sf

from . import EngineError, config
from .song import Mureka, _ok

ACCOMP_UNDER_VOCAL_DB = 1.5  # 반주 RMS 를 보컬 RMS 보다 이만큼 작게(노래가 묻히지 않게)


NAMES = ("none", "mureka")


def default() -> str:
    """설정의 accompaniment(없으면 'none')."""
    return config().get("accompaniment") or "none"


async def redo(song_audio: bytes, style: str, engine: str = "mureka") -> tuple[bytes, dict]:
    """반주를 다시 만든 곡(FLAC)과 기록."""
    if engine != "mureka":
        raise EngineError(f"반주 엔진 '{engine}' 은 아직 없어요(mureka 만).")
    m = Mureka()
    info = {"engine": "mureka", "steps": []}
    y, sr = sf.read(io.BytesIO(song_audio), dtype="float32", always_2d=True)
    mp3 = io.BytesIO()
    sf.write(mp3, y, sr, format="MP3")
    async with m._session() as s:
        # ① 보컬 분리 — 곡을 base64 data URL 로(10MB 까지)
        url = "data:audio/mp3;base64," + base64.b64encode(mp3.getvalue()).decode()
        async with s.post(m.API + "/v1/song/stem", json={"url": url, "model": "audio-separation-1"},
                          timeout=aiohttp.ClientTimeout(total=600)) as r:
            stem = await _ok(r)
        async with s.get(stem["zip_url"], timeout=aiohttp.ClientTimeout(total=300)) as r:
            r.raise_for_status()
            z = zipfile.ZipFile(io.BytesIO(await r.read()))
        names = z.namelist()
        info["stems"] = names
        vname = next((n for n in names if "vocal" in n.lower()), None)
        if vname is None:
            raise EngineError(f"분리 결과에 보컬이 없어요: {names}")
        voc, vsr = sf.read(io.BytesIO(z.read(vname)), dtype="float32", always_2d=True)
        info["steps"].append("stem")
        # ② 보컬에 맞춘 반주
        vbuf = io.BytesIO()
        sf.write(vbuf, voc, vsr, format="MP3")
        up = await m._upload(s, vbuf.getvalue(), "vocals.mp3", "audio", "audio/mpeg")
        body = {"upload_audio_id": up, "generate_type": "Instrumental", "prompt": (style or "pop")[:1024]}
        made = await m._run(s, "/v1/track/generate", body, {})
        info["steps"].append("instrumental")
        info["track_task"] = made.info.get("task_id")
    inst, isr = sf.read(io.BytesIO(made.audio), dtype="float32", always_2d=True)
    # ③ 섞기 — 같은 표본률·길이로 맞추고, 반주를 보컬보다 조금 작게
    if isr != vsr:
        import librosa
        inst = librosa.resample(inst.T, orig_sr=isr, target_sr=vsr).T
    if inst.shape[1] != voc.shape[1]:
        inst = np.repeat(inst[:, :1], voc.shape[1], axis=1) if inst.shape[1] == 1 else inst[:, :voc.shape[1]]
    n = max(len(voc), len(inst))
    voc = np.pad(voc, ((0, n - len(voc)), (0, 0)))
    inst = np.pad(inst, ((0, n - len(inst)), (0, 0)))
    rms = lambda x: float(np.sqrt(np.mean(x ** 2)) + 1e-9)
    gain = rms(voc) / rms(inst) * 10 ** (-ACCOMP_UNDER_VOCAL_DB / 20)
    mix = voc + inst * gain
    mix /= max(1.0, float(np.max(np.abs(mix))) / 0.98)
    info |= {"vocal_sec": round(len(voc) / vsr, 1), "inst_sec": round(len(inst) / vsr, 1), "inst_gain_db": round(20 * np.log10(gain), 1)}
    out = io.BytesIO()
    sf.write(out, mix, vsr, format="FLAC", subtype="PCM_16")
    return out.getvalue(), info
